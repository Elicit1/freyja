package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dto.video.VideoProbeInfoVO;
import com.astra.freyja.service.VideoFrameExtractService;
import com.astra.freyja.service.NativeProcessExecutor;
import io.minio.*;
import io.minio.credentials.Credentials;
import okhttp3.Headers;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(McpBusinessDatabaseTest.Config.class)
@TestPropertySource(properties = "spring.ai.mcp.server.enabled=true")
class McpPresignedVideoUploadTest {
    @Autowired AutowireCapableBeanFactory factory;
    @Autowired McpPresignedVideoUploads uploads;
    @Autowired McpImageTransferStorage signing;
    @Autowired MinioProperties properties;
    @Autowired VideoFrameExtractService probe;
    @Autowired PlatformTransactionManager transactions;
    McpBusinessDatabaseTest fixture;
    final byte[] bytes = "reference-video-test-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @BeforeEach void setup() throws Exception {
        fixture = new McpBusinessDatabaseTest(); factory.autowireBean(fixture); fixture.setup(); reset(probe);
        var shot = new com.astra.freyja.entity.DramaShot(); shot.setId(80L); shot.setDramaId(McpBusinessDatabaseTest.PROJECT);
        shot.setEpisodeId(20L); shot.setSceneId(30L); shot.setShotGroupId(40L); shot.setShotNo(1); shot.setShotName("S01"); shot.setSortOrder(0);
        shot.setDuration(BigDecimal.valueOf(4)); shot.setGenerationMode("REFERENCE_MODE"); fixture.shots.insert(shot);
        properties.setExternalEndpoint("https://assets.example.test"); properties.setMcpUploadEndpoint(null); properties.setMcpUploadBucket("freyja-mcp-uploads");
        ReflectionTestUtils.setField(signing, "initializedBucket", null);
        when(fixture.storage.bucketExists(any())).thenReturn(true);
        when(fixture.storage.getPresignedPostFormData(any())).thenAnswer(call -> ((PostPolicy) call.getArgument(0)).formData(new Credentials("test-key", "test-secret", null, null), "us-east-1"));
        var info = new VideoProbeInfoVO(); info.setContainerFormat("mov,mp4,m4a"); info.setDuration(new BigDecimal("4.25")); info.setWidth(640); info.setHeight(360); info.setFps(24.0); info.setHasAudio(true);
        when(probe.probeVideoFile(any(Path.class), eq(false))).thenReturn(info);
    }
    @AfterEach void restore() { properties.setExternalEndpoint("http://127.0.0.1:9000"); properties.setMcpUploadEndpoint(null); }
    String hash(byte[] content) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
    McpPresignedVideoUploads.Prepare input(String key, byte[] content) throws Exception { return new McpPresignedVideoUploads.Prepare(key, McpBusinessDatabaseTest.ROOT, "80", "motion.mp4", "video/mp4", content.length, hash(content), "参考推进运镜"); }
    Map<String, Object> prepare(McpPresignedVideoUploads.Prepare input) { return fixture.map(fixture.call(McpPresignedVideoUploads.PREPARE_TOOL, fixture.caller, input)); }
    Map<String, Object> complete(String key, String id) { return fixture.map(fixture.call(McpPresignedVideoUploads.COMPLETE_TOOL, fixture.caller, new McpPresignedVideoUploads.Complete(key, McpBusinessDatabaseTest.ROOT, id))); }
    McpPresignedVideoUploads.Pending pending(String id) { return fixture.json.read(fixture.map(fixture.journal.read(fixture.caller, id)).get("data"), McpPresignedVideoUploads.Pending.class); }
    void staged(byte[] content, long size, String type) throws Exception {
        var stat = mock(StatObjectResponse.class); when(stat.size()).thenReturn(size); when(stat.contentType()).thenReturn(type); when(stat.etag()).thenReturn("immutable-etag");
        doReturn(stat).when(fixture.storage).statObject(any());
        doAnswer(call -> { GetObjectArgs args = call.getArgument(0); assertEquals("immutable-etag", args.matchETag()); return new GetObjectResponse(Headers.of(), args.bucket(), "us-east-1", args.object(), new ByteArrayInputStream(content)); }).when(fixture.storage).getObject(any());
    }

    @Test void exactSizePresignedPolicyAndCatalogKeepBytesAndCredentialsOutOfJournal() throws Exception {
        var request = input("video-prepare-001", bytes); var result = prepare(request);
        assertEquals("https://assets.example.test/freyja-mcp-uploads", result.get("uploadUrl"));
        var fields = fixture.map(result.get("fields"));
        String policy = new String(Base64.getDecoder().decode((String) fields.get("policy")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(policy.contains("[\"content-length-range\"," + bytes.length + "," + bytes.length + "]"));
        assertTrue(policy.contains("video/mp4"));
        assertEquals(fields.get("key"), fixture.map(prepare(request).get("fields")).get("key"));
        String journal = fixture.json.write(fixture.journal.read(fixture.caller, request.requestId()));
        assertFalse(journal.contains("x-amz-signature")); assertFalse(result.containsKey("url"));
        verify(fixture.storage, never()).getObject(any()); verify(fixture.storage, never()).putObject(any());
        var arguments = new LinkedHashMap<String, Object>(); arguments.put("requestId", "video-protocol01"); arguments.put("dramaId", request.dramaId()); arguments.put("fileName", request.fileName()); arguments.put("contentType", request.contentType()); arguments.put("size", bytes.length); arguments.put("sha256", request.sha256());
        assertFalse(Boolean.TRUE.equals(fixture.protocolCall(McpPresignedVideoUploads.PREPARE_TOOL, fixture.caller, arguments).isError()));
        arguments.put("requestId", "video-protocol02"); arguments.put("size", Integer.toString(bytes.length));
        assertTrue(Boolean.TRUE.equals(fixture.protocolCall(McpPresignedVideoUploads.PREPARE_TOOL, fixture.caller, arguments).isError()));
    }

    @Test void completionRegistersMaterialOnceAcrossDifferentConfirmationKeysAndReturnsWritableReference() throws Exception {
        var request = input("video-register-01", bytes); prepare(request); staged(bytes, bytes.length, "video/mp4");
        doAnswer(call -> { PutObjectArgs args = call.getArgument(0); assertArrayEquals(bytes, args.stream().readAllBytes()); return null; }).when(fixture.storage).putObject(any());
        var result = complete("video-confirm-01", request.requestId());
        assertEquals(1, fixture.count("res_video")); assertTrue(result.get("videoAssetId") instanceof String);
        assertEquals(640, result.get("width")); assertEquals(true, result.get("hasAudio"));
        var reference = fixture.map(result.get("reference"));
        assertEquals("DIRECT_URL", reference.get("sourceType")); assertEquals(result.get("url"), reference.get("sourceVideoUrl"));
        assertEquals(false, reference.get("includeAudio"));
        when(fixture.storage.statObject(any())).thenThrow(new IllegalStateException("staging already deleted"));
        assertEquals(result, complete("video-confirm-01", request.requestId()));
        assertEquals(result.get("videoAssetId"), complete("video-confirm-02", request.requestId()).get("videoAssetId"));
        verify(fixture.storage, times(1)).getObject(any()); verify(fixture.storage, times(1)).putObject(any());
        var old = fixture.records.read(fixture.caller, McpRecordType.SHOT, "80", McpBusinessDatabaseTest.ROOT);
        fixture.call("update_shot", fixture.caller, new McpRecordInputs.Update("video-bind-shot1", McpBusinessDatabaseTest.ROOT, "80", (String) fixture.map(old).get("editFingerprint"),
                Map.of("generationMode", "REFERENCE_MODE", "refVideos", List.of(reference))));
        var shot = fixture.records.read(fixture.caller, McpRecordType.SHOT, "80", McpBusinessDatabaseTest.ROOT);
        assertEquals(result.get("url"), fixture.map(((List<?>) fixture.map(shot).get("refVideos")).getFirst()).get("sourceVideoUrl"));
    }

    @Test void invalidHashSizeTypeOrBodyNeverRegistersMaterialAndSameRequestCanRetry() throws Exception {
        var request = input("video-validation", bytes); prepare(request);
        staged(bytes, bytes.length + 1L, "video/mp4"); assertThrows(BizException.class, () -> complete("video-check-fail", request.requestId()));
        staged(bytes, bytes.length, "image/png"); assertThrows(BizException.class, () -> complete("video-check-fail", request.requestId()));
        staged(Arrays.copyOf(bytes, bytes.length + 1), bytes.length, "video/mp4"); assertThrows(BizException.class, () -> complete("video-check-fail", request.requestId()));
        byte[] changed = bytes.clone(); changed[0] ^= 1;
        staged(changed, changed.length, "video/mp4"); assertTrue(assertThrows(BizException.class, () -> complete("video-check-fail", request.requestId())).getMessage().contains("HASH_MISMATCH"));
        assertEquals(0, fixture.count("res_video")); verify(fixture.storage, never()).putObject(any());
        staged(bytes, bytes.length, "video/mp4"); assertNotNull(complete("video-check-fail", request.requestId()).get("url"));
        assertEquals(1, fixture.count("res_video"));
    }

    @Test void readOnlyForeignProjectAndInvalidMetadataCannotObtainCredentials() throws Exception {
        var good = input("video-access-001", bytes);
        var reader = new McpPrincipal(false, false, false, Set.of(McpBusinessDatabaseTest.PROJECT));
        assertEquals(403, assertThrows(BizException.class, () -> uploads.prepare(reader, good)).getCode());
        assertEquals(403, assertThrows(BizException.class, () -> uploads.prepare(fixture.caller, new McpPresignedVideoUploads.Prepare(good.requestId(), Long.toString(McpBusinessDatabaseTest.PROJECT + 1), null, good.fileName(), good.contentType(), good.size(), good.sha256(), null))).getCode());
        assertThrows(BizException.class, () -> prepare(new McpPresignedVideoUploads.Prepare(good.requestId(), good.dramaId(), null, "../motion.mp4", good.contentType(), good.size(), good.sha256(), null)));
        assertThrows(BizException.class, () -> prepare(new McpPresignedVideoUploads.Prepare(good.requestId(), good.dramaId(), null, good.fileName(), "image/png", good.size(), good.sha256(), null)));
        verifyNoInteractions(fixture.storage);
    }

    @Test void expiryAndWrongReceiptTypeFailBeforeReadingVideo() throws Exception {
        var request = input("video-expiry-001", bytes); prepare(request); var p = pending(request.requestId());
        var expired = new McpPresignedVideoUploads.Pending(p.uploadId(), p.dramaId(), p.shotId(), p.fileName(), p.contentType(), p.size(), p.sha256(), p.referenceDescription(), p.bucket(), p.objectKey(), Instant.now().minusSeconds(1).toString());
        new JdbcTemplate(fixture.dataSource).update("UPDATE mcp_business_operation SET result_json=? WHERE request_id=?", fixture.json.write(expired), p.uploadId());
        assertEquals(410, assertThrows(BizException.class, () -> complete("video-expiry-end", request.requestId())).getCode());
        fixture.journal.execute("image-not-video1", McpPresignedImageUploads.PREPARE_TOOL, Map.of(), () -> new McpOperationStore.Outcome(Map.of(), McpBusinessDatabaseTest.PROJECT, false));
        assertEquals(409, assertThrows(BizException.class, () -> complete("video-wrong-kind", "image-not-video1")).getCode());
        verify(fixture.storage, never()).getObject(any()); assertEquals(0, fixture.count("res_video"));
    }

    @Test void rollbackRetainsStagingAndAllowsConfirmationRetry() throws Exception {
        var request = input("video-rollback01", bytes); prepare(request); staged(bytes, bytes.length, "video/mp4");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { complete("video-rollback02", request.requestId()); tx.setRollbackOnly(); });
        assertEquals(0, fixture.count("res_video")); verify(fixture.storage, never()).removeObject(any());
        assertNotNull(complete("video-rollback02", request.requestId()).get("url")); assertEquals(1, fixture.count("res_video"));
    }

    @Test void realNativeVideoIsProbedAndCannotBeReplacedByARenamedImage(@TempDir Path folder) throws Exception {
        Path video = folder.resolve("real.mp4");
        var process = new NativeProcessExecutor().execute(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=24", "-t", "2", "-c:v", "libx264", video.toString()), Duration.ofSeconds(30));
        assertEquals(0, process.exitCode(), process.output());
        when(probe.probeVideoFile(any(Path.class), eq(false))).thenAnswer(call -> new VideoFrameExtractService().probeVideoFile(call.getArgument(0), false));
        byte[] actual = Files.readAllBytes(video); var request = input("video-native-001", actual); prepare(request); staged(actual, actual.length, "video/mp4");
        var result = complete("video-native-end", request.requestId()); assertEquals(128, result.get("width")); assertEquals(96, result.get("height"));
        byte[] png = McpImageAssetsTest.image("png", false); var renamed = input("video-renamed-01", png); prepare(renamed); staged(png, png.length, "video/mp4");
        assertThrows(BizException.class, () -> complete("video-renamed-end", renamed.requestId())); assertEquals(1, fixture.count("res_video"));
    }
}
