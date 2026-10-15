package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Binary image validation remains independent of the removed inline upload tools. */
class McpImageAssetsTest {
    static final String WEBP = "UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoCAAMAAUAmJaACdLoB+AADsAD+8ut//NgVzXPv9//S4P0uD9Lg/9KQAAA=";
    static byte[] image(String format, boolean large) throws Exception {
        BufferedImage image = new BufferedImage(large ? 1024 : 2, large ? 1024 : 3, BufferedImage.TYPE_INT_RGB);
        if (large) {
            Random random = new Random(1);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, format, output)); return output.toByteArray();
    }

    @Test void pngJpegAndWebpUseRealFormatDimensionsAndHash() throws Exception {
        for (String format : List.of("png", "jpg", "webp")) {
            byte[] bytes = format.equals("webp") ? Base64.getDecoder().decode(WEBP) : image(format, false);
            var validated = McpImageAssets.validateImage(bytes);
            assertArrayEquals(bytes, validated.bytes());
            assertEquals(format, validated.extension());
            assertEquals(format.equals("jpg") ? "image/jpeg" : "image/" + format, validated.contentType());
            assertEquals(2, validated.width()); assertEquals(3, validated.height());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), validated.hash());
        }
    }
    @Test void unsupportedOrTruncatedFilesAreRejected() throws Exception {
        for (byte[] bytes : List.of(
                "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Arrays.copyOf(image("png", false), 28),
                Arrays.copyOf(Base64.getDecoder().decode(WEBP), 18)))
            assertEquals(400, assertThrows(BizException.class, () -> McpImageAssets.validateImage(bytes)).getCode());
    }
    @Test void emptyOversizedAndExcessivePixelFilesAreRejected() {
        for (byte[] bytes : List.of(new byte[0], new byte[McpImageAssets.MAX_IMAGE_BYTES + 1]))
            assertEquals(413, assertThrows(BizException.class, () -> McpImageAssets.validateImage(bytes)).getCode());
        byte[] canvas = new byte[]{'R','I','F','F',18,0,0,0,'W','E','B','P','V','P','8','L',6,0,0,0x2f,(byte) 0xff,(byte) 0xff,(byte) 0xff,15,0};
        assertEquals(400, assertThrows(BizException.class, () -> McpImageAssets.validateImage(canvas)).getCode());
    }
    @Test void unsafeFileNamesAreRejected() {
        for (String name : List.of("../image.png", "F:\\image.png", "  ", "image\n.png"))
            assertEquals(400, assertThrows(BizException.class, () -> McpImageAssets.validateName(name)).getCode());
        assertDoesNotThrow(() -> McpImageAssets.validateName("角色造型.png"));
    }
}
