package com.astra.freyja.agent;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.settings.AgentAvatarMediaService;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.res.AssetUploadResultVO;
import com.astra.freyja.service.MediaAssetService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentAvatarMediaServiceTest {
    static byte[] nativeSprite() throws Exception { return Files.readAllBytes(Path.of("front/public/agent/freyja/spritesheet.webp")); }
    static byte[] nativePackage() throws Exception { return Files.readAllBytes(Path.of("front/public/agent/freyja/freyja.codex-pet.zip")); }
    static byte[] zip(Map<String, byte[]> entries) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var stream = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) { stream.putNextEntry(new ZipEntry(entry.getKey())); stream.write(entry.getValue()); stream.closeEntry(); }
        }
        return out.toByteArray();
    }
    static byte[] manifest(String path, Integer version) {
        return ("{\"id\":\"sample\",\"displayName\":\"Sample\",\"spritesheetPath\":\"" + path + "\""
                + (version == null ? "" : ",\"spriteVersionNumber\":" + version) + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    static MockMultipartFile file(byte[] bytes) { return new MockMultipartFile("file", "pet.zip", "application/zip", bytes); }
    AgentAvatarMediaService service() { return new AgentAvatarMediaService(mock(MediaAssetService.class), new AgentJson()); }

    @Test void nativePackageUploadsOnlyOriginalSpriteBytes() throws Exception {
        var storage = mock(MediaAssetService.class);
        when(storage.upload(any(), eq("agent-pet"))).thenAnswer(call -> {
            var sprite = (MultipartFile) call.getArgument(0);
            assertArrayEquals(nativeSprite(), sprite.getBytes()); assertEquals("image/webp", sprite.getContentType());
            return AssetUploadResultVO.builder().url("/sprite").objectPath("agent-pet/sprite.webp").contentType("image/webp").build();
        });
        var pet = new AgentAvatarMediaService(storage, new AgentJson()).upload(file(nativePackage()));
        assertEquals("freyja", pet.getId()); assertEquals("/sprite", pet.getSpritesheetUrl()); assertEquals(9, pet.getRows());
        verify(storage, times(1)).upload(any(), eq("agent-pet"));
    }
    @Test void wrappedPackageWithOmittedVersionIsAccepted() throws Exception {
        var pack = service().inspectPackage(file(zip(Map.of("sample/pet.json", manifest("spritesheet.webp", null), "sample/spritesheet.webp", nativeSprite()))));
        assertEquals(1, pack.pet().getSpriteVersionNumber()); assertEquals("Sample", pack.pet().getDisplayName());
    }
    @Test void smoothDownloadUsesPortableCodexGridAndPreservesArtwork() throws Exception {
        var pack = service().inspectPackage(file(Files.readAllBytes(Path.of("front/public/agent/freyja/freyja-smooth.codex-pet.zip"))));
        assertEquals("freyja", pack.pet().getId());
        assertEquals(1, pack.pet().getSpriteVersionNumber());
        assertEquals(8, pack.pet().getColumns()); assertEquals(9, pack.pet().getRows());
        assertEquals(192, pack.pet().getFrameWidth()); assertEquals(208, pack.pet().getFrameHeight());
        assertArrayEquals(Files.readAllBytes(Path.of("front/public/agent/freyja/spritesheet-smooth-codex.webp")), pack.spritesheet());
    }
    @Test void transparentPngVersionTwoIsAccepted() throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1536, 2288, BufferedImage.TYPE_INT_ARGB), "png", out);
        var pack = service().inspectPackage(file(zip(Map.of("pet.json", manifest("sheet.png", 2), "sheet.png", out.toByteArray()))));
        assertEquals(11, pack.pet().getRows()); assertEquals("image/png", pack.pet().getContentType());
    }
    @Test void unsafeArchiveAndManifestPathsAreRejected() throws Exception {
        for (String path : List.of("../evil", "/evil", "C:/evil", "nested/../evil", "nested\\..\\evil"))
            assertThrows(BizException.class, () -> service().inspectPackage(file(zip(Map.of(path, new byte[]{1})))));
        for (String path : List.of("../sheet.webp", "https://example.com/sheet.webp"))
            assertThrows(BizException.class, () -> service().inspectPackage(file(zip(Map.of("pet.json", manifest(path, 1), "sheet.webp", nativeSprite())))));
    }
    @Test void ambiguousManifestsAndCaseDuplicatePathsAreRejected() throws Exception {
        assertThrows(BizException.class, () -> service().inspectPackage(file(zip(Map.of("pet.json", manifest("sheet.webp", 1), "nested/pet.json", manifest("sheet.webp", 1))))));
        assertThrows(BizException.class, () -> service().inspectPackage(file(zip(Map.of("pet.json", manifest("sheet.webp", 1), "PET.JSON", manifest("sheet.webp", 1))))));
    }
    @Test void expansionAndEntryCountAreBounded() throws Exception {
        assertThrows(BizException.class, () -> service().inspectPackage(file(zip(Map.of("large.bin", new byte[33 * 1024 * 1024])))));
        Map<String, byte[]> entries = new LinkedHashMap<>(); for (int i = 0; i < 65; i++) entries.put("f" + i, new byte[0]);
        assertThrows(BizException.class, () -> service().inspectPackage(file(zip(entries))));
        var huge = mock(MultipartFile.class); when(huge.getSize()).thenReturn(AgentAvatarMediaService.MAX_BYTES + 1);
        assertThrows(BizException.class, () -> service().upload(huge));
    }
    @Test void missingSpriteWrongVersionAndInvalidJsonAreRejected() throws Exception {
        assertThrows(BizException.class, () -> service().inspectPackage(file(zip(Map.of("pet.json", manifest("sheet.webp", 1))))));
        assertThrows(BizException.class, () -> service().inspectPackage(file(zip(Map.of("pet.json", manifest("sheet.webp", 2), "sheet.webp", nativeSprite())))));
        assertThrows(BizException.class, () -> service().inspectPackage(file(zip(Map.of("pet.json", "{} trailing".getBytes())))));
    }
    @Test void truncatedWebpGifAndOpaquePngAreRejected() throws Exception {
        byte[] sprite = nativeSprite(); assertThrows(BizException.class, () -> AgentAvatarMediaService.inspect(Arrays.copyOf(sprite, sprite.length - 4)));
        assertThrows(BizException.class, () -> AgentAvatarMediaService.inspect(new byte[40]));
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1536, 1872, BufferedImage.TYPE_INT_RGB), "png", out);
        assertThrows(BizException.class, () -> AgentAvatarMediaService.inspect(out.toByteArray()));
    }
}
