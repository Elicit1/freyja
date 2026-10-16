package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import io.minio.*;
import io.minio.credentials.Credentials;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(McpBusinessDatabaseTest.Config.class)
@TestPropertySource(properties = "spring.ai.mcp.server.enabled=true")
class McpPresignedImageUploadTest {
    @Autowired AutowireCapableBeanFactory factory;
    @Autowired McpPresignedImageUploads uploads;
    @Autowired McpImageTransferStorage transferStorage;
    @Autowired MinioProperties properties;
    @Autowired PlatformTransactionManager transactions;
    McpBusinessDatabaseTest fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new McpBusinessDatabaseTest(); factory.autowireBean(fixture); fixture.setup();
        properties.setExternalEndpoint("https://assets.example.test");
        properties.setMcpUploadEndpoint(null);
        properties.setMcpUploadBucket("freyja-mcp-uploads");
        ReflectionTestUtils.setField(transferStorage, "initializedBucket", null);
        when(fixture.storage.bucketExists(any())).thenReturn(true);
        when(fixture.storage.getPresignedPostFormData(any())).thenAnswer(call ->
                ((PostPolicy) call.getArgument(0)).formData(new Credentials("test-access-key", "test-secret-key", null, null), "us-east-1"));
    }
    @AfterEach void cleanup() {
        properties.setMcpUploadEndpoint(null);
        properties.setExternalEndpoint("http://127.0.0.1:9000"); properties.setMcpUploadBucket("freyja-mcp-uploads");
        fixture.noInternalAiCalls();
    }

    String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    McpPresignedImageUploads.Prepare input(String key, byte[] bytes) throws Exception {
        return new McpPresignedImageUploads.Prepare(key, McpBusinessDatabaseTest.ROOT, McpImageAssets.Purpose.CHARACTER_LOOK,
                "look.png", "image/png", bytes.length, hash(bytes));
    }
    Map<String, Object> prepare(McpPresignedImageUploads.Prepare input) {
        return fixture.map(fixture.call(McpPresignedImageUploads.PREPARE_TOOL, fixture.caller, input));
    }
    McpPresignedImageUploads.Pending pending(String id) {
        return fixture.json.read(fixture.map(fixture.journal.read(fixture.caller, id)).get("data"), McpPresignedImageUploads.Pending.class);
    }
    McpPresignedImageUploads.Complete completion(String id) {
        return new McpPresignedImageUploads.Complete("confirm-" + id, McpBusinessDatabaseTest.ROOT, id);
    }
    Map<String, Object> complete(String id) {
        return fixture.map(fixture.call(McpPresignedImageUploads.COMPLETE_TOOL, fixture.caller, completion(id)));
    }
    void staged(byte[] bytes, int declaredSize, String declaredType) throws Exception {
        var stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn((long) declaredSize); when(stat.contentType()).thenReturn(declaredType); when(stat.etag()).thenReturn("staging-etag");
        doReturn(stat).when(fixture.storage).statObject(any());
        doAnswer(call -> {
            GetObjectArgs args = call.getArgument(0);
            assertEquals("staging-etag", args.matchETag());
            return new GetObjectResponse(Headers.of(), args.bucket(), "us-east-1", args.object(), new ByteArrayInputStream(bytes));
        }).when(fixture.storage).getObject(any());
    }

    @Test void signsExactSizeTypeAndKeyWithoutSendingImageBytesOrPersistingCredentials() throws Exception {
        byte[] bytes = McpImageAssetsTest.image("png", false);
        var input = input("direct-upload-001", bytes);
        var result = prepare(input);
        assertEquals("POST", result.get("method")); assertEquals("file", result.get("fileField"));
        assertEquals("https://assets.example.test/freyja-mcp-uploads", result.get("uploadUrl"));
        Map<String, Object> fields = fixture.map(result.get("fields"));
        String policy = new String(Base64.getDecoder().decode((String) fields.get("policy")), StandardCharsets.UTF_8);
        var conditions = fixture.json.mapper().readTree(policy).path("conditions");
        assertTrue(conditions.toString().contains("[\"content-length-range\"," + bytes.length + "," + bytes.length + "]"));
        assertTrue(conditions.toString().contains("image/png"));
        assertTrue(conditions.toString().contains((String) fields.get("key")));
        assertTrue(fields.get("key").toString().startsWith("mcp/" + McpBusinessDatabaseTest.ROOT + "/"));
        assertEquals(64, fields.get("x-amz-signature").toString().length());
        assertTrue(Instant.parse((String) result.get("expiresAt")).isAfter(Instant.now()));
        var replay = prepare(input);
        assertEquals(fields.get("key"), fixture.map(replay.get("fields")).get("key"));
        assertEquals(result.get("expiresAt"), replay.get("expiresAt"));
        String recorded = fixture.json.write(fixture.journal.read(fixture.caller, input.requestId()));
        assertFalse(recorded.contains("x-amz-signature")); assertFalse(recorded.contains(Base64.getEncoder().encodeToString(bytes)));
        assertFalse(result.containsKey("url")); assertEquals(1, fixture.count("mcp_business_operation"));
        verify(fixture.storage, never()).putObject(any()); verify(fixture.storage, never()).getObject(any());
        assertEquals(409, assertThrows(BizException.class, () -> prepare(new McpPresignedImageUploads.Prepare(input.requestId(), input.dramaId(),
                input.purpose(), "different.png", input.contentType(), input.size(), input.sha256()))).getCode());
    }

    @Test void clientUploadEndpointIsIndependentFromInternalStorageAndFinalAssetUrls() throws Exception {
        String originalEndpoint = properties.getEndpoint();
        try {
            properties.setEndpoint("http://minio:9000");
            properties.setMcpUploadEndpoint(" http://127.0.0.1:19000/ ");
            byte[] bytes = McpImageAssetsTest.image("png", false);
            var input = input("client-endpoint-01", bytes);
            var result = prepare(input);
            assertEquals("http://127.0.0.1:19000/freyja-mcp-uploads", result.get("uploadUrl"));
            staged(bytes, bytes.length, "image/png");
            var archived = complete(input.requestId());
            assertTrue(archived.get("url").toString().startsWith("https://assets.example.test/video-assets/"));
            // A remote deployment may publish S3 through a reverse proxy with a path prefix.
            properties.setMcpUploadEndpoint("https://uploads.example.test/s3///");
            assertEquals("https://uploads.example.test/s3/freyja-mcp-uploads",
                    prepare(input("client-endpoint-02", bytes)).get("uploadUrl"));
        } finally {
            properties.setEndpoint(originalEndpoint);
        }
    }

    @Test void invalidClientEndpointNeverIssuesCredentialsOrFallsBackToInternalStorage() throws Exception {
        var input = input("invalid-endpoint-01", McpImageAssetsTest.image("png", false));
        for (String endpoint : List.of("minio:9000", "ftp://uploads.example.test", "http://user:pass@uploads.example.test",
                "https://uploads.example.test?token=value", "https://uploads.example.test/#fragment")) {
            properties.setMcpUploadEndpoint(endpoint);
            BizException error = assertThrows(BizException.class, () -> prepare(input));
            assertEquals(500, error.getCode());
            assertTrue(error.getMessage().contains("MINIO_MCP_UPLOAD_ENDPOINT"));
        }
        properties.setMcpUploadEndpoint(" ");
        properties.setExternalEndpoint("");
        assertEquals(500, assertThrows(BizException.class, () -> prepare(input)).getCode());
        verifyNoInteractions(fixture.storage);
    }

    @Test void completionArchivesValidatedSnapshotAndReplaysAfterStagingDeletionAndExpiry() throws Exception {
        byte[] bytes = McpImageAssetsTest.image("png", false);
        var input = input("direct-complete1", bytes); prepare(input); staged(bytes, bytes.length, "image/png");
        doAnswer(call -> {
            PutObjectArgs args = call.getArgument(0);
            assertArrayEquals(bytes, args.stream().readAllBytes()); assertEquals("video-assets", args.bucket());
            assertEquals("image/png", args.contentType()); return null;
        }).when(fixture.storage).putObject(any());
        var result = complete(input.requestId());
        assertTrue(result.get("url").toString().contains("/video-assets/mcp/" + input.dramaId() + "/character_look/"));
        assertTrue(result.get("url").toString().endsWith(hash(bytes) + ".png"));
        assertEquals(input.sha256(), result.get("sha256")); assertEquals(2, result.get("width")); assertEquals(3, result.get("height"));
        expire(input.requestId());
        when(fixture.storage.statObject(any())).thenThrow(new IllegalStateException("staging was removed"));
        assertEquals(result, complete(input.requestId()));
        verify(fixture.storage, times(1)).putObject(any()); verify(fixture.storage, times(1)).getObject(any());
        assertEquals(2, fixture.count("mcp_business_operation"));
        assertEquals(result, fixture.map(fixture.journal.read(fixture.caller, completion(input.requestId()).requestId())).get("data"));
    }

    @Test void invalidMetadataReadOnlyAndForeignProjectCannotIssueCredentials() throws Exception {
        byte[] bytes = McpImageAssetsTest.image("png", false); var good = input("direct-invalid1", bytes);
        assertEquals(403, assertThrows(BizException.class, () -> uploads.prepare(new McpPrincipal(false, false, false, Set.of(McpBusinessDatabaseTest.PROJECT)), good)).getCode());
        assertEquals(403, assertThrows(BizException.class, () -> prepare(new McpPresignedImageUploads.Prepare(good.requestId(),
                Long.toString(McpBusinessDatabaseTest.PROJECT + 1), good.purpose(), good.fileName(), good.contentType(), good.size(), good.sha256()))).getCode());
        for (int size : List.of(0, McpImageAssets.MAX_IMAGE_BYTES + 1))
            assertEquals(413, assertThrows(BizException.class, () -> prepare(new McpPresignedImageUploads.Prepare(good.requestId(), good.dramaId(),
                    good.purpose(), good.fileName(), good.contentType(), size, good.sha256()))).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> prepare(new McpPresignedImageUploads.Prepare(good.requestId(), good.dramaId(),
                good.purpose(), "../look.png", good.contentType(), good.size(), good.sha256()))).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> prepare(new McpPresignedImageUploads.Prepare(good.requestId(), good.dramaId(),
                good.purpose(), good.fileName(), "image/svg+xml", good.size(), good.sha256()))).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> prepare(new McpPresignedImageUploads.Prepare(good.requestId(), good.dramaId(),
                good.purpose(), good.fileName(), good.contentType(), good.size(), "guessed-hash"))).getCode());
        verifyNoInteractions(fixture.storage); assertEquals(0, fixture.count("mcp_business_operation"));
    }

    @Test void completionChecksProjectAndReceiptKindBeforeAccessingStorage() throws Exception {
        var input = input("direct-scope-01", McpImageAssetsTest.image("png", false)); prepare(input); clearInvocations(fixture.storage);
        var otherProjectCaller = new McpPrincipal(true, true, false, Set.of());
        assertEquals(403, assertThrows(BizException.class, () -> uploads.complete(otherProjectCaller,
                new McpPresignedImageUploads.Complete("confirm-foreign1", Long.toString(McpBusinessDatabaseTest.PROJECT + 1), input.requestId()))).getCode());
        assertEquals(403, assertThrows(BizException.class, () -> uploads.complete(new McpPrincipal(false, false, false, Set.of(McpBusinessDatabaseTest.PROJECT)), completion(input.requestId()))).getCode());
        fixture.journal.execute("not-an-upload-01", "create_prop", Map.of(), () -> new McpOperationStore.Outcome(Map.of(), McpBusinessDatabaseTest.PROJECT, false));
        assertEquals(409, assertThrows(BizException.class, () -> complete("not-an-upload-01")).getCode());
        verifyNoInteractions(fixture.storage);
    }

    @Test void missingOrSizeMismatchNeverArchivesAndFailedConfirmationCanRetry() throws Exception {
        byte[] bytes = McpImageAssetsTest.image("png", false); var input = input("direct-retry-01", bytes); prepare(input);
        when(fixture.storage.statObject(any())).thenThrow(new ErrorResponseException(
                new ErrorResponse("NoSuchKey", "missing", "bucket", "key", "resource", "request", "host"), null, null));
        assertEquals(404, assertThrows(BizException.class, () -> complete(input.requestId())).getCode());
        staged(bytes, bytes.length + 1, "image/png");
        assertEquals(400, assertThrows(BizException.class, () -> complete(input.requestId())).getCode());
        verify(fixture.storage, never()).getObject(any()); verify(fixture.storage, never()).putObject(any());
        assertEquals(1, fixture.count("mcp_business_operation"));
        staged(bytes, bytes.length, "image/png"); assertNotNull(complete(input.requestId()).get("url"));
    }

    @Test void hashMismatchAndActualFormatMismatchCannotCreateFinalUrls() throws Exception {
        byte[] bytes = McpImageAssetsTest.image("png", false); var good = input("direct-wronghash", bytes);
        prepare(new McpPresignedImageUploads.Prepare(good.requestId(), good.dramaId(), good.purpose(), good.fileName(), "image/png", bytes.length, "0".repeat(64)));
        staged(bytes, bytes.length, "image/png");
        assertTrue(assertThrows(BizException.class, () -> complete(good.requestId())).getMessage().contains("IMAGE_HASH_MISMATCH"));
        var wrongType = input("direct-wrongtype", bytes);
        prepare(new McpPresignedImageUploads.Prepare(wrongType.requestId(), wrongType.dramaId(), wrongType.purpose(), "look.jpg", "image/jpeg", bytes.length, good.sha256()));
        staged(bytes, bytes.length, "image/jpeg");
        assertTrue(assertThrows(BizException.class, () -> complete(wrongType.requestId())).getMessage().contains("IMAGE_TYPE_MISMATCH"));
        byte[] invalid = "not an image".getBytes(StandardCharsets.UTF_8);
        var invalidInput = input("direct-nonimage1", invalid); prepare(invalidInput); staged(invalid, invalid.length, "image/png");
        assertThrows(BizException.class, () -> complete(invalidInput.requestId()));
        verify(fixture.storage, never()).putObject(any()); verify(fixture.storage, never()).removeObject(any());
    }

    @Test void boundedReadRejectsExtraBytesAndDoesNotTrustStatAlone() throws Exception {
        byte[] bytes = McpImageAssetsTest.image("png", false); var input = input("direct-overread1", bytes); prepare(input);
        staged(Arrays.copyOf(bytes, bytes.length + 10), bytes.length, "image/png");
        assertTrue(assertThrows(BizException.class, () -> complete(input.requestId())).getMessage().contains("IMAGE_SIZE_MISMATCH"));
        verify(fixture.storage, never()).putObject(any());
    }

    void expire(String id) {
        var p = pending(id);
        var expired = new McpPresignedImageUploads.Pending(p.uploadId(), p.dramaId(), p.purpose(), p.fileName(), p.contentType(), p.size(),
                p.sha256(), p.bucket(), p.objectKey(), Instant.now().minusSeconds(1).toString());
        new JdbcTemplate(fixture.dataSource).update("UPDATE mcp_business_operation SET result_json=? WHERE request_id=?", fixture.json.write(expired), id);
    }
    @Test void expiredPendingUploadsRequireNewRequestAndNeverTouchStorage() throws Exception {
        var input = input("direct-expired1", McpImageAssetsTest.image("png", false)); prepare(input); expire(input.requestId()); clearInvocations(fixture.storage);
        assertEquals(410, assertThrows(BizException.class, () -> prepare(input)).getCode());
        assertEquals(410, assertThrows(BizException.class, () -> complete(input.requestId())).getCode());
        verifyNoInteractions(fixture.storage);
    }

    @Test void storageFailureKeepsStagingAndOuterRollbackDoesNotDeleteSource() throws Exception {
        byte[] bytes = McpImageAssetsTest.image("png", false); var input = input("direct-rollback1", bytes); prepare(input); staged(bytes, bytes.length, "image/png");
        doThrow(new IllegalStateException("storage unavailable")).when(fixture.storage).putObject(any());
        assertEquals(502, assertThrows(BizException.class, () -> complete(input.requestId())).getCode());
        verify(fixture.storage, never()).removeObject(any());
        doReturn(null).when(fixture.storage).putObject(any());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { complete(input.requestId()); tx.setRollbackOnly(); });
        verify(fixture.storage, never()).removeObject(any()); assertEquals(1, fixture.count("mcp_business_operation"));
        assertNotNull(complete(input.requestId()).get("url")); verify(fixture.storage).removeObject(any());
    }

    @Test void stagingLifecycleIsConfiguredAndCannotUsePublicAssetBucket() throws Exception {
        var input = input("direct-bucket-01", McpImageAssetsTest.image("png", false));
        properties.setMcpUploadBucket(properties.getBucketName());
        assertEquals(500, assertThrows(BizException.class, () -> prepare(input)).getCode()); verifyNoInteractions(fixture.storage);
        properties.setMcpUploadBucket("freyja-mcp-uploads"); when(fixture.storage.bucketExists(any())).thenReturn(false);
        prepare(input);
        verify(fixture.storage).makeBucket(any());
        verify(fixture.storage).setBucketLifecycle(argThat(args -> args.bucket().equals("freyja-mcp-uploads")
                && args.config().rules().getFirst().expiration().days() == 1));
        verify(fixture.storage, never()).setBucketPolicy(any());
    }
}
