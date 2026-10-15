package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.service.MediaAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Shared image validation and archival for MCP binary uploads. */
@Component
@RequiredArgsConstructor
public class McpImageAssets {
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000;
    public enum Purpose { CHARACTER, CHARACTER_LOOK, SCENE, PROP, KEYFRAME, SHOT_REFERENCE }
    record Image(byte[] bytes, String extension, String contentType, int width, int height, String hash) {}
    private final MediaAssetService media;

    static void validateName(String name) {
        if (name != null && (name.isBlank() || name.length() > 200 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)))
            throw new BizException(400, "fileName 应为不带路径的文件名，最多200字符");
    }
    Map<String, Object> store(Image image, String path, String name, Purpose purpose) {
        com.astra.freyja.dto.res.AssetUploadResultVO stored;
        try { stored = media.uploadImage(image.bytes(), path, name == null ? "image." + image.extension() : name, image.contentType()); }
        catch (RuntimeException e) { throw new BizException(502, "IMAGE_UPLOAD_FAILED: 图片归档失败，请使用原 requestId 和原内容重试"); }
        return new LinkedHashMap<>(Map.of("purpose", purpose, "url", stored.getUrl(), "contentType", image.contentType(), "size", image.bytes().length,
                "width", image.width(), "height", image.height(), "sha256", image.hash()));
    }

    static Image validateImage(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new BizException(413, "图片必须为1字节到10 MiB");
        String extension; int width; int height;
        if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) {
            int[] dimensions = webpDimensions(bytes); extension = "webp"; width = dimensions[0]; height = dimensions[1];
        } else {
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new BizException(400, "仅支持 PNG、JPEG 和 WebP 图片");
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("png", "jpeg", "jpg").contains(format)) throw new BizException(400, "仅支持 PNG、JPEG 和 WebP 图片");
                    extension = format.equals("png") ? "png" : "jpg";
                    reader.setInput(stream, true, true);
                    width = reader.getWidth(0); height = reader.getHeight(0); dimensions(width, height);
                    if (reader.read(0) == null) throw new BizException(400, "图片内容无效");
                } finally { reader.dispose(); }
            } catch (BizException e) { throw e; }
            catch (Exception e) { throw new BizException(400, "图片内容损坏或不完整"); }
        }
        dimensions(width, height);
        try { return new Image(bytes, extension, extension.equals("jpg") ? "image/jpeg" : "image/" + extension, width, height,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void dimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 16384 || height > 16384 || (long) width * height > MAX_PIXELS)
            throw new BizException(400, "图片尺寸无效：最长边16384像素，总像素最多4000万");
    }
    private static boolean ascii(byte[] bytes, int start, String value) {
        return start + value.length() <= bytes.length && value.equals(new String(bytes, start, value.length(), StandardCharsets.US_ASCII));
    }
    private static long little(byte[] bytes, int offset, int count) {
        long result = 0; for (int i = 0; i < count; i++) result |= (bytes[offset + i] & 255L) << (8 * i); return result;
    }
    /** Validate the RIFF container and dimensions without a native WebP decoder dependency. */
    private static int[] webpDimensions(byte[] bytes) {
        if (little(bytes, 4, 4) + 8 != bytes.length) throw new BizException(400, "WebP 文件长度无效");
        int[] canvas = null; int[] frame = null;
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            long length = little(bytes, offset + 4, 4); long next = offset + 8L + length + (length & 1);
            if (next > bytes.length) throw new BizException(400, "WebP 图片内容不完整");
            int start = offset + 8;
            if (ascii(bytes, offset, "VP8X") && length == 10) {
                if ((bytes[start] & 2) != 0) throw new BizException(400, "请上传静态 WebP 图片");
                canvas = new int[]{(int) little(bytes, start + 4, 3) + 1, (int) little(bytes, start + 7, 3) + 1};
            } else if (ascii(bytes, offset, "VP8 ") && length > 10 && (bytes[start] & 1) == 0
                    && (bytes[start + 3] & 255) == 0x9d && bytes[start + 4] == 1 && bytes[start + 5] == 0x2a) {
                frame = new int[]{(int) little(bytes, start + 6, 2) & 0x3fff, (int) little(bytes, start + 8, 2) & 0x3fff};
            } else if (ascii(bytes, offset, "VP8L") && length > 5 && (bytes[start] & 255) == 0x2f) {
                long bits = little(bytes, start + 1, 4);
                if ((bits >>> 29) != 0) throw new BizException(400, "WebP 版本无效");
                frame = new int[]{(int) (bits & 0x3fff) + 1, (int) ((bits >>> 14) & 0x3fff) + 1};
            }
            offset = (int) next;
        }
        if (offset != bytes.length || frame == null || (canvas != null && !Arrays.equals(canvas, frame))) throw new BizException(400, "WebP 缺少有效静态图像数据");
        return frame;
    }

}
