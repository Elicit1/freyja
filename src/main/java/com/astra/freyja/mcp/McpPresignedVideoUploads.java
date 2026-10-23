package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.ReferenceVideoProperties;
import com.astra.freyja.service.ResVideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Semaphore;

@Component
@RequiredArgsConstructor
public class McpPresignedVideoUploads {
    public static final String PREPARE_TOOL = "prepare_asset_video_upload";
    public static final String COMPLETE_TOOL = "complete_asset_video_upload";
    private static final int EXPIRY_SECONDS = 15 * 60;
    public record Prepare(String requestId, String dramaId, String shotId, String fileName, String contentType,
                          Integer size, String sha256, String referenceDescription) { }
    public record Complete(String requestId, String dramaId, String uploadId) { }
    record Pending(String uploadId, String dramaId, String shotId, String fileName, String contentType,
                   Integer size, String sha256, String referenceDescription, String bucket, String objectKey, String expiresAt) { }

    private final McpImageTransferStorage signing;
    private final McpVideoTransferStorage transfer;
    private final ResVideoService videos;
    private final ReferenceVideoProperties limits;
    private final McpOperationStore journal;
    private final McpScope scope;
    private final McpJson json;
    private final Semaphore permits = new Semaphore(2);

    public McpTool<Prepare> prepareTool() {
        return new McpTool<>(PREPARE_TOOL,
                "申请参考视频直传凭证。提供授权 dramaId、fileName、真实 contentType、size、客户端计算的 SHA-256、稳定 requestId；可选同项目 shotId 和 referenceDescription。支持 MP4/WebM/MOV/MKV，大小和时长遵循视频素材配置（默认256MiB、600秒）。返回15分钟有效的uploadId/uploadUrl/fields；用HTTP能力将真实文件作为最后一个file字段直传，不传本地路径或Base64。再调用complete_asset_video_upload校验并自动登记视频素材。一次申请对应一个文件，可逐个上传多个文件；分镜引用数量在预检和渲染时按所选模型maxVideos校验。", Prepare.class, McpTool.Effect.WRITE, prepareSchema(), this::prepare);
    }
    public McpTool<Complete> completeTool() {
        return new McpTool<>(COMPLETE_TOOL,
                "确认参考视频直传：提供原uploadId、授权dramaId和独立稳定requestId。服务端按ETag固定文件，流式校验大小/SHA-256，FFprobe验证真实视频及元数据后自动入库，返回videoAssetId/url/reference。reference可加入分镜refVideos，在REFERENCE_MODE编写<Video N>；不会自动覆盖分镜或启动渲染。相同uploadId即使换确认requestId也不会重复创建素材；超时先以原requestId重试。", Complete.class, McpTool.Effect.WRITE,
                Map.of("type", "object", "additionalProperties", false, "required", List.of("requestId", "dramaId", "uploadId"),
                        "properties", Map.of("requestId", keySchema(), "dramaId", idSchema(), "uploadId", keySchema())), this::complete);
    }

    public Object prepare(McpPrincipal caller, Prepare input) {
        requireWrite(caller);
        if (input == null || input.fileName() == null) throw new BizException(400, "必须提供视频上传参数和文件名");
        Long project = scope.project(caller, input.dramaId());
        if (input.shotId() != null) scope.shot(McpScope.id(input.shotId()), project);
        McpOperationStore.key(input.requestId()); McpImageAssets.validateName(input.fileName());
        if (input.size() == null || input.size() < 1 || input.size() > limits.getMaxSourceBytes()) throw new BizException(413, "视频大小超出素材上传配置");
        String extension = input.fileName().substring(input.fileName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String expected = Map.of("mp4", "video/mp4", "webm", "video/webm", "mov", "video/quicktime", "mkv", "video/x-matroska").get(extension);
        if (expected == null || !expected.equals(input.contentType())) throw new BizException(400, "视频扩展名与Content-Type不匹配，支持MP4/WebM/MOV/MKV");
        if (input.sha256() == null || !input.sha256().matches("[a-fA-F0-9]{64}")) throw new BizException(400, "sha256必须为客户端计算的64位十六进制摘要");
        if (input.referenceDescription() != null && input.referenceDescription().length() > 1000) throw new BizException(400, "参考说明不能超过1000字");
        Prepare normalized = new Prepare(input.requestId(), project.toString(), input.shotId(), input.fileName(), input.contentType(), input.size(), input.sha256().toLowerCase(Locale.ROOT), input.referenceDescription());
        Object saved = journal.execute(input.requestId(), PREPARE_TOOL, normalized, () -> new McpOperationStore.Outcome(
                new Pending(input.requestId(), project.toString(), input.shotId(), input.fileName(), input.contentType(), input.size(), normalized.sha256(), input.referenceDescription(), signing.bucket(),
                        "mcp/" + project + "/video/" + UUID.randomUUID(), Instant.now().plusSeconds(EXPIRY_SECONDS).truncatedTo(ChronoUnit.SECONDS).toString()), project, false));
        Pending upload = json.read(saved, Pending.class); requireUnexpired(upload);
        Map<String, Object> result = new LinkedHashMap<>(signing.authorize(upload.bucket(), upload.objectKey(), upload.contentType(), upload.size(), upload.expiresAt()));
        result.put("uploadId", upload.uploadId()); result.put("dramaId", upload.dramaId()); result.put("fileName", upload.fileName());
        result.put("contentType", upload.contentType()); result.put("size", upload.size()); result.put("sha256", upload.sha256()); result.put("expiresAt", upload.expiresAt());
        return result;
    }

    public Object complete(McpPrincipal caller, Complete input) {
        requireWrite(caller);
        if (input == null) throw new BizException(400, "必须提供视频上传确认参数");
        Long project = scope.project(caller, input.dramaId());
        McpOperationStore.key(input.requestId()); McpOperationStore.key(input.uploadId());
        var receipt = (Map<?, ?>) journal.read(caller, input.uploadId(), PREPARE_TOOL);
        Pending upload = json.read(receipt.get("data"), Pending.class);
        scope.belongs(McpScope.id(upload.dramaId()), project);
        Object result = journal.execute(input.requestId(), COMPLETE_TOOL, input, () -> {
            // A separate durable key serializes ALL confirmations for the same upload, including different request IDs.
            Object stored = journal.execute(finalizationKey(upload.uploadId()), "finalize_asset_video_upload", upload,
                    () -> new McpOperationStore.Outcome(archive(upload, project), project, false));
            Map<String, Object> response = new LinkedHashMap<>((Map<String, Object>) stored);
            response.put("requestId", input.requestId()); response.put("uploadId", upload.uploadId());
            return new McpOperationStore.Outcome(response, project, false);
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { transfer.discard(upload); } });
        } else transfer.discard(upload);
        return result;
    }

    private Object archive(Pending upload, Long project) {
        requireUnexpired(upload);
        if (upload.shotId() != null) scope.shot(McpScope.id(upload.shotId()), project);
        if (!permits.tryAcquire()) throw new BizException(429, "视频确认繁忙，请使用原requestId稍后重试");
        Path file = null;
        try {
            file = Files.createTempFile("freyja_mcp_video_", ".media"); transfer.download(upload, file);
            var material = videos.upload(new DiskMultipart(file, upload.fileName(), upload.contentType(), upload.size()), upload.fileName(), project, McpScope.optionalId(upload.shotId()), "SHOT_UPLOAD");
            if (upload.referenceDescription() != null) {
                var dto = new com.astra.freyja.dto.res.ResVideoDTO(); dto.setId(material.getId()); dto.setName(material.getName()); dto.setDramaId(project); dto.setShotId(material.getShotId()); dto.setDescription(upload.referenceDescription()); videos.update(dto);
            }
            var reference = new LinkedHashMap<String, Object>(); reference.put("id", "video_asset_" + material.getId()); reference.put("name", material.getName());
            reference.put("sourceType", "DIRECT_URL"); reference.put("sourceVideoUrl", material.getVideoUrl()); reference.put("startSeconds", 0); reference.put("includeAudio", false);
            if (upload.referenceDescription() != null) reference.put("referenceDescription", upload.referenceDescription());
            var result = new LinkedHashMap<String, Object>(); result.put("videoAssetId", material.getId().toString()); result.put("dramaId", project.toString()); result.put("url", material.getVideoUrl());
            result.put("name", material.getName()); result.put("size", upload.size()); result.put("sha256", upload.sha256());
            result.put("duration", material.getDuration()); result.put("width", material.getWidth()); result.put("height", material.getHeight()); result.put("fps", material.getFps()); result.put("hasAudio", material.getHasAudio()); result.put("reference", reference);
            return result;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(502, "VIDEO_UPLOAD_ARCHIVE_FAILED: 视频归档失败，请使用原请求重试"); }
        finally { if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { } permits.release(); }
    }

    private record DiskMultipart(Path path, String filename, String type, long size) implements MultipartFile {
        public String getName() { return "file"; } public String getOriginalFilename() { return filename; }
        public String getContentType() { return type; } public boolean isEmpty() { return size == 0; } public long getSize() { return size; }
        public byte[] getBytes() throws IOException { throw new IOException("Video uploads require streaming access"); }
        public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        public void transferTo(File target) throws IOException { Files.copy(path, target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    private String finalizationKey(String id) { try { return "video-finalize-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static void requireWrite(McpPrincipal caller) { if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED"); }
    private static void requireUnexpired(Pending upload) { if (!Instant.now().isBefore(Instant.parse(upload.expiresAt()))) throw new BizException(410, "VIDEO_UPLOAD_EXPIRED: 请使用新requestId重新申请上传凭证"); }
    private Map<String, Object> prepareSchema() {
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("requestId", "dramaId", "fileName", "contentType", "size", "sha256"),
                "properties", Map.of("requestId", keySchema(), "dramaId", idSchema(), "shotId", idSchema(),
                        "fileName", Map.of("type", "string", "minLength", 1, "maxLength", 200), "contentType", Map.of("type", "string", "enum", List.of("video/mp4", "video/webm", "video/quicktime", "video/x-matroska")),
                        "size", Map.of("type", "integer", "minimum", 1, "maximum", (int) Math.min(Integer.MAX_VALUE, limits.getMaxSourceBytes())),
                        "sha256", Map.of("type", "string", "pattern", "^[a-fA-F0-9]{64}$"), "referenceDescription", Map.of("type", "string", "maxLength", 1000)));
    }
    private static Map<String, Object> keySchema() { return Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{8,80}$"); }
    private static Map<String, Object> idSchema() { return Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$"); }
}
