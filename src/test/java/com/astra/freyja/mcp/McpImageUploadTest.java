package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import io.minio.BucketExistsArgs;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.astra.freyja.service.MediaAssetService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(McpBusinessDatabaseTest.Config.class)
@TestPropertySource(properties = "spring.ai.mcp.server.enabled=true")
class McpImageUploadTest {
    @Autowired AutowireCapableBeanFactory factory;
    @Autowired McpImageUploads uploads;
    @Autowired MediaAssetService media;
    McpBusinessDatabaseTest fixture;
    static final String WEBP = "UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoCAAMAAUAmJaACdLoB+AADsAD+8ut//NgVzXPv9//S4P0uD9Lg/9KQAAA=";

    @BeforeEach void setup() throws Exception {
        fixture = new McpBusinessDatabaseTest(); factory.autowireBean(fixture); fixture.setup();
        when(fixture.storage.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    }
    @AfterEach void noAiCalls() { fixture.noInternalAiCalls(); }
    static byte[] image(String format, boolean large) throws Exception {
        BufferedImage image = new BufferedImage(large ? 1024 : 2, large ? 1024 : 3, BufferedImage.TYPE_INT_RGB);
        if (large) {
            Random random = new Random(1);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, format, output)); return output.toByteArray();
    }
    McpImageUploads.Upload input(String key, byte[] bytes, McpImageUploads.Purpose purpose) {
        return new McpImageUploads.Upload(key, McpBusinessDatabaseTest.ROOT, purpose, Base64.getEncoder().encodeToString(bytes), null);
    }
    Map<String, Object> call(McpImageUploads.Upload input) { return fixture.map(fixture.call(McpImageUploads.TOOL_NAME, fixture.caller, input)); }

    @Test void allReferencePurposesStoreActualBytesAndReturnStableUrlOnRetry() throws Exception {
        byte[] bytes = image("png", false);
        List<String> paths = new ArrayList<>();
        doAnswer(invocation -> {
            PutObjectArgs args = invocation.getArgument(0);
            assertArrayEquals(bytes, args.stream().readAllBytes()); assertEquals("image/png", args.contentType());
            paths.add(args.object()); return null;
        }).when(fixture.storage).putObject(any(PutObjectArgs.class));
        for (var purpose : McpImageUploads.Purpose.values()) {
            var input = input("purpose-upload-" + purpose, bytes, purpose);
            Map<String, Object> result = call(input);
            assertTrue(((String) result.get("url")).contains("/mcp/" + McpBusinessDatabaseTest.ROOT + "/" + purpose.name().toLowerCase(Locale.ROOT) + "/"));
            assertEquals(2, result.get("width")); assertEquals(3, result.get("height")); assertEquals(bytes.length, result.get("size"));
            assertFalse(fixture.json.write(result).contains(input.dataBase64())); assertEquals(result, call(input));
        }
        assertEquals(6, paths.size()); assertEquals(6, fixture.count("mcp_business_operation"));
        verify(fixture.storage, times(6)).putObject(any(PutObjectArgs.class));
        assertEquals(409, assertThrows(BizException.class, () -> call(input("purpose-upload-CHARACTER", image("jpg", false), McpImageUploads.Purpose.CHARACTER))).getCode());
    }

    @Test void jpegAndWebpUseActualFormatIndependentOfFileName() throws Exception {
        var jpeg = call(new McpImageUploads.Upload("jpeg-upload-001", McpBusinessDatabaseTest.ROOT, McpImageUploads.Purpose.KEYFRAME,
                Base64.getEncoder().encodeToString(image("jpg", false)), "reference.png"));
        assertEquals("image/jpeg", jpeg.get("contentType")); assertTrue(((String) jpeg.get("url")).endsWith(".jpg"));
        var webp = call(new McpImageUploads.Upload("webp-upload-001", McpBusinessDatabaseTest.ROOT, McpImageUploads.Purpose.SCENE, WEBP, null));
        assertEquals("image/webp", webp.get("contentType")); assertEquals(2, webp.get("width")); assertEquals(3, webp.get("height"));
    }

    @Test void readOnlyAndForeignProjectCannotWriteStorage() throws Exception {
        var input = input("unauth-upload-01", image("png", false), McpImageUploads.Purpose.PROP);
        assertEquals(403, assertThrows(BizException.class, () -> uploads.upload(new McpPrincipal(false, false, false, Set.of(McpBusinessDatabaseTest.PROJECT)), input)).getCode());
        var foreign = new McpImageUploads.Upload(input.requestId(), Long.toString(McpBusinessDatabaseTest.PROJECT + 1), input.purpose(), input.dataBase64(), null);
        assertEquals(403, assertThrows(BizException.class, () -> call(foreign)).getCode());
        verifyNoInteractions(fixture.storage); assertEquals(0, fixture.count("mcp_business_operation"));
    }

    @Test void invalidBase64UnsupportedContentAndUnsafeNameNeverReachStorage() throws Exception {
        for (String value : List.of("", "%%%%", "data:image/png;base64," + Base64.getEncoder().encodeToString(image("png", false)),
                Base64.getEncoder().encodeToString("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(Arrays.copyOf(image("png", false), 28)),
                Base64.getEncoder().encodeToString(Arrays.copyOf(Base64.getDecoder().decode(WEBP), 18)))) {
            assertThrows(BizException.class, () -> call(new McpImageUploads.Upload("invalid-upload01", McpBusinessDatabaseTest.ROOT, McpImageUploads.Purpose.KEYFRAME, value, null)));
        }
        var safe = input("invalid-upload01", image("png", false), McpImageUploads.Purpose.KEYFRAME);
        for (String name : List.of("../image.png", "F:\\image.png", "  ", "image\n.png")) {
            assertEquals(400, assertThrows(BizException.class, () -> call(new McpImageUploads.Upload(safe.requestId(), safe.dramaId(), safe.purpose(), safe.dataBase64(), name))).getCode());
        }
        verifyNoInteractions(fixture.storage); assertEquals(0, fixture.count("mcp_business_operation"));
    }

    @Test void byteLimitAndPixelLimitRejectOversizedImagesBeforeStorage() throws Exception {
        byte[] huge = new byte[McpImageUploads.MAX_IMAGE_BYTES + 1];
        assertEquals(413, assertThrows(BizException.class, () -> call(input("large-upload-001", huge, McpImageUploads.Purpose.KEYFRAME))).getCode());
        // A lossless WebP canvas of 16384 x 16384 exceeds the bound before any pixel allocation.
        byte[] canvas = new byte[]{'R','I','F','F',18,0,0,0,'W','E','B','P','V','P','8','L',6,0,0,0,0x2f,(byte) 0xff,(byte) 0xff,(byte) 0xff,15,0};
        assertEquals(400, assertThrows(BizException.class, () -> call(input("pixels-upload01", canvas, McpImageUploads.Purpose.KEYFRAME))).getCode());
        verifyNoInteractions(fixture.storage);
    }

    @Test void storageFailureRollsBackJournalAndRetryUsesSameObjectPath() throws Exception {
        var input = input("retry-upload-001", image("png", false), McpImageUploads.Purpose.CHARACTER_LOOK);
        List<String> paths = new ArrayList<>();
        doAnswer(invocation -> {
            paths.add(((PutObjectArgs) invocation.getArgument(0)).object());
            if (paths.size() == 1) throw new IllegalStateException("test storage unavailable");
            return null;
        }).when(fixture.storage).putObject(any(PutObjectArgs.class));
        assertEquals(502, assertThrows(BizException.class, () -> call(input)).getCode()); assertEquals(0, fixture.count("mcp_business_operation"));
        Map<String, Object> result = call(input); assertEquals(2, paths.size()); assertEquals(paths.get(0), paths.get(1));
        assertEquals(1, fixture.count("mcp_business_operation")); assertEquals(result, fixture.map(fixture.journal.read(fixture.caller, input.requestId())).get("data"));
    }

    @Test void existingMultipartUploadKeepsItsBusinessStoragePath() throws Exception {
        byte[] bytes = image("png", false);
        var result = media.upload(new MockMultipartFile("file", "look.png", "image/png", bytes), "outfit");
        assertTrue(result.getObjectPath().startsWith("outfit/")); assertEquals("look.png", result.getOriginalFilename()); assertEquals((long) bytes.length, result.getSize());
        verify(fixture.storage).putObject(any(PutObjectArgs.class));
    }

    McpImageUploads.BatchUpload batch(String requestId, List<McpImageUploads.UploadItem> items) {
        return new McpImageUploads.BatchUpload(requestId, McpBusinessDatabaseTest.ROOT, items);
    }
    McpImageUploads.UploadItem batchItem(String key, byte[] bytes) {
        return new McpImageUploads.UploadItem(key, McpImageUploads.Purpose.CHARACTER_LOOK, Base64.getEncoder().encodeToString(bytes), key + ".png");
    }
    Map<String, Object> callBatch(McpImageUploads.BatchUpload input) {
        return fixture.map(fixture.call(McpImageUploads.BATCH_TOOL_NAME, fixture.caller, input));
    }
    @Test void batchPreservesClientKeysAndOrderWithOneJournalAndNoBase64OnReplay() throws Exception {
        byte[] png = image("png", false), jpeg = image("jpg", false);
        var input = batch("batch-upload-001", List.of(batchItem("hero", png), batchItem("scene", jpeg)));
        List<byte[]> stored = new ArrayList<>();
        doAnswer(invocation -> { stored.add(((PutObjectArgs) invocation.getArgument(0)).stream().readAllBytes()); return null; })
                .when(fixture.storage).putObject(any(PutObjectArgs.class));
        var result = callBatch(input); assertEquals(2, result.get("count"));
        List<?> records = (List<?>) result.get("records");
        assertEquals("hero", fixture.map(records.get(0)).get("clientKey")); assertEquals("scene", fixture.map(records.get(1)).get("clientKey"));
        assertEquals(fixture.map(records.get(0)).get("url"), fixture.map(result.get("urlMap")).get("hero"));
        assertArrayEquals(png, stored.get(0)); assertArrayEquals(jpeg, stored.get(1));
        assertEquals(result, callBatch(input)); assertEquals(2, stored.size()); assertEquals(1, fixture.count("mcp_business_operation"));
        assertFalse(fixture.json.write(result).contains(input.items().get(0).dataBase64()));
        assertEquals(409, assertThrows(BizException.class, () -> callBatch(batch(input.requestId(), List.of(batchItem("hero", jpeg), batchItem("scene", png))))).getCode());
    }
    @Test void batchRejectsInvalidLaterImageDuplicateKeysAndLimitsBeforeAnyUpload() throws Exception {
        var good = batchItem("hero", image("png", false));
        var bad = new McpImageUploads.UploadItem("bad", good.purpose(), "%%%%", null);
        var error = assertThrows(BizException.class, () -> callBatch(batch("batch-invalid-01", List.of(good, bad))));
        assertTrue(error.getMessage().startsWith("items[1]"));
        assertThrows(BizException.class, () -> callBatch(batch("batch-duplicate1", List.of(good, good))));
        assertThrows(BizException.class, () -> callBatch(batch("batch-count-001", Collections.nCopies(21, good))));
        byte[] large = image("png", true); List<McpImageUploads.UploadItem> overTotal = new ArrayList<>();
        for (int i = 0; i < 7; i++) overTotal.add(batchItem("item" + i, large));
        assertEquals(413, assertThrows(BizException.class, () -> callBatch(batch("batch-total-001", overTotal))).getCode());
        verifyNoInteractions(fixture.storage); assertEquals(0, fixture.count("mcp_business_operation"));
    }
    @Test void batchStorageFailureRetriesStablePathsAndDoesNotCommitIncompleteResults() throws Exception {
        var input = batch("batch-retry-001", List.of(batchItem("hero", image("png", false)), batchItem("prop", image("jpg", false))));
        List<String> paths = new ArrayList<>();
        doAnswer(invocation -> {
            paths.add(((PutObjectArgs) invocation.getArgument(0)).object());
            if (paths.size() == 2) throw new IllegalStateException("storage unavailable"); return null;
        }).when(fixture.storage).putObject(any(PutObjectArgs.class));
        var error = assertThrows(BizException.class, () -> callBatch(input)); assertEquals(502, error.getCode()); assertTrue(error.getMessage().contains("items[1]"));
        assertEquals(0, fixture.count("mcp_business_operation"));
        callBatch(input); assertEquals(paths.subList(0, 2), paths.subList(2, 4)); assertEquals(1, fixture.count("mcp_business_operation"));
        callBatch(input); assertEquals(4, paths.size());
    }
    @Test void batchWritePermissionsAndProjectScopeAreEnforcedBeforeStorage() throws Exception {
        var input = batch("batch-scope-001", List.of(batchItem("hero", image("png", false))));
        assertEquals(403, assertThrows(BizException.class, () -> uploads.batchUpload(new McpPrincipal(false, false, false, Set.of(McpBusinessDatabaseTest.PROJECT)), input)).getCode());
        var foreign = new McpImageUploads.BatchUpload(input.requestId(), Long.toString(McpBusinessDatabaseTest.PROJECT + 1), input.items());
        assertEquals(403, assertThrows(BizException.class, () -> callBatch(foreign)).getCode()); verifyNoInteractions(fixture.storage);
    }
}
