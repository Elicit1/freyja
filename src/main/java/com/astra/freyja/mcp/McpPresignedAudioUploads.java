package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefAudioDTO;
import com.astra.freyja.util.ShotAudioReferenceValidator;
import com.astra.freyja.service.impl.ResAudioServiceImpl;
import com.astra.freyja.service.ResAudioService;
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
public class McpPresignedAudioUploads {
    public static final String PREPARE_TOOL = "prepare_asset_audio_upload";
    public static final String COMPLETE_TOOL = "complete_asset_audio_upload";
    private static final int EXPIRY_SECONDS = 15 * 60;
    public record Prepare(String requestId, String dramaId, String shotId, String fileName, String contentType,
                          Integer size, String sha256, String audioKind, String referenceType, String referenceDescription, String text, String language) { }
    public record Complete(String requestId, String dramaId, String uploadId) { }
    record Pending(String uploadId, String dramaId, String shotId, String fileName, String contentType,
                   Integer size, String sha256, String audioKind, String referenceType, String referenceDescription, String text, String language, String bucket, String objectKey, String expiresAt) { }

    private final McpImageTransferStorage signing;
    private final McpAudioTransferStorage transfer;
    private final ResAudioService audios;
    private final McpOperationStore journal;
    private final McpScope scope;
    private final McpJson json;
    private final Semaphore permits = new Semaphore(2);

    public McpTool<Prepare> prepareTool() {
        return new McpTool<>(PREPARE_TOOL,
                "申请参考音频直传凭证。提供授权dramaId、fileName、contentType、size、客户端SHA-256、requestId，以及明确选择的audioKind和referenceType（fully_copy/partially_copy/reference/weak_reference）。可选shotId、referenceDescription、原文text和language。支持MP3/WAV/FLAC/M4A/OGG，最大30MiB；参考音频单段2–15秒。返回15分钟有效uploadId/uploadUrl/fields，用HTTP将实际文件作为最后一个file字段直传，再调用complete_asset_audio_upload。禁止本地路径或Base64；不会生成声音或自动修改镜头。", Prepare.class, McpTool.Effect.WRITE, prepareSchema(), this::prepare);
    }
    public McpTool<Complete> completeTool() {
        return new McpTool<>(COMPLETE_TOOL,
                "确认参考音频直传：提供原uploadId、授权dramaId和独立稳定requestId。校验ETag、大小、SHA-256及真实音轨/时长后自动登记音频素材，返回audioAssetId/url/reference。将reference加入有序refAudios，第N项对应<Audio N>；不会覆盖镜头或启动渲染。音乐与音效无需角色。相同uploadId换确认requestId也不会重复建档；超时使用原requestId重试。", Complete.class, McpTool.Effect.WRITE,
                Map.of("type", "object", "additionalProperties", false, "required", List.of("requestId", "dramaId", "uploadId"),
                        "properties", Map.of("requestId", keySchema(), "dramaId", idSchema(), "uploadId", keySchema())), this::complete);
    }

    public Object prepare(McpPrincipal caller, Prepare input) {
        requireWrite(caller);
        if (input == null || input.fileName() == null) throw new BizException(400, "必须提供音频上传参数和文件名");
        Long project = scope.project(caller, input.dramaId());
        if (input.shotId() != null) scope.shot(McpScope.id(input.shotId()), project);
        McpOperationStore.key(input.requestId()); McpImageAssets.validateName(input.fileName());
        if (input.size() == null || input.size() < 1 || input.size() > ResAudioServiceImpl.MAX_BYTES) throw new BizException(413, "音频大小超过30MiB限制");
        String extension = input.fileName().substring(input.fileName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String expected = Map.of("mp3", "audio/mpeg", "wav", "audio/wav", "flac", "audio/flac", "m4a", "audio/mp4", "ogg", "audio/ogg").get(extension);
        if (expected == null || !expected.equals(input.contentType())) throw new BizException(400, "音频扩展名与Content-Type不匹配，支持MP3/WAV/FLAC/M4A/OGG");
        if (input.sha256() == null || !input.sha256().matches("[a-fA-F0-9]{64}")) throw new BizException(400, "sha256必须为客户端计算的64位十六进制摘要");
        if (input.referenceDescription() != null && input.referenceDescription().length() > 1000) throw new BizException(400, "参考说明不能超过1000字");
        if (!ShotAudioReferenceValidator.AUDIO_KINDS.contains(input.audioKind() == null ? "" : input.audioKind())) throw new BizException(400, "请选择有效的audioKind");
        if (!ShotAudioReferenceValidator.REFERENCE_TYPES.contains(input.referenceType() == null ? "" : input.referenceType())) throw new BizException(400, "请选择H3四种referenceType之一");
        if (input.text() != null && input.text().length() > 10000) throw new BizException(400, "原文text不能超过10000字符");
        if (input.language() != null && !ShotAudioReferenceValidator.LANGUAGES.contains(input.language())) throw new BizException(400, "language无效");
        Prepare normalized = new Prepare(input.requestId(), project.toString(), input.shotId(), input.fileName(), input.contentType(), input.size(), input.sha256().toLowerCase(Locale.ROOT), input.audioKind(), input.referenceType(), input.referenceDescription(), input.text(), input.language());
        Object saved = journal.execute(input.requestId(), PREPARE_TOOL, normalized, () -> new McpOperationStore.Outcome(
                new Pending(input.requestId(), project.toString(), input.shotId(), input.fileName(), input.contentType(), input.size(), normalized.sha256(), input.audioKind(), input.referenceType(), input.referenceDescription(), input.text(), input.language(), signing.bucket(),
                        "mcp/" + project + "/audio/" + UUID.randomUUID(), Instant.now().plusSeconds(EXPIRY_SECONDS).truncatedTo(ChronoUnit.SECONDS).toString()), project, false));
        Pending upload = json.read(saved, Pending.class); requireUnexpired(upload);
        Map<String, Object> result = new LinkedHashMap<>(signing.authorize(upload.bucket(), upload.objectKey(), upload.contentType(), upload.size(), upload.expiresAt()));
        result.put("uploadId", upload.uploadId()); result.put("dramaId", upload.dramaId()); result.put("fileName", upload.fileName());
        result.put("contentType", upload.contentType()); result.put("size", upload.size()); result.put("sha256", upload.sha256()); result.put("expiresAt", upload.expiresAt());
        return result;
    }

    public Object complete(McpPrincipal caller, Complete input) {
        requireWrite(caller);
        if (input == null) throw new BizException(400, "必须提供音频上传确认参数");
        Long project = scope.project(caller, input.dramaId());
        McpOperationStore.key(input.requestId()); McpOperationStore.key(input.uploadId());
        var receipt = (Map<?, ?>) journal.read(caller, input.uploadId(), PREPARE_TOOL);
        Pending upload = json.read(receipt.get("data"), Pending.class);
        scope.belongs(McpScope.id(upload.dramaId()), project);
        Object result = journal.execute(input.requestId(), COMPLETE_TOOL, input, () -> {
            // A separate durable key serializes ALL confirmations for the same upload, including different request IDs.
            Object stored = journal.execute(finalizationKey(upload.uploadId()), "finalize_asset_audio_upload", upload,
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
        if (!permits.tryAcquire()) throw new BizException(429, "音频确认繁忙，请使用原requestId稍后重试");
        Path file = null;
        try {
            file = Files.createTempFile("freyja_mcp_audio_", ".media"); transfer.download(upload, file);
            var material = audios.upload(new DiskMultipart(file, upload.fileName(), upload.contentType(), upload.size()), upload.fileName(), project, McpScope.optionalId(upload.shotId()), "SHOT_UPLOAD", upload.audioKind(), upload.text(), upload.language());
            if (upload.referenceDescription() != null) {
                var dto = new com.astra.freyja.dto.res.ResAudioDTO(); dto.setId(material.getId()); dto.setName(material.getName()); dto.setDramaId(project); dto.setShotId(material.getShotId()); dto.setDescription(upload.referenceDescription()); dto.setAudioKind(material.getAudioKind()); dto.setText(material.getText()); dto.setLanguage(material.getLanguage()); audios.update(dto);
            }
            var reference = ShotRefAudioDTO.builder().id("audio_asset_" + material.getId())
                    .resAudioId(material.getId()).audioKind(material.getAudioKind()).audioUrl(material.getAudioUrl())
                    .duration(material.getDuration()).name(material.getName()).text(material.getText()).language(material.getLanguage())
                    .referenceType(upload.referenceType()).referenceDescription(upload.referenceDescription()).build();
            ShotAudioReferenceValidator.validateForSave(List.of(reference));
            var result = new LinkedHashMap<String, Object>(); result.put("audioAssetId", material.getId().toString()); result.put("dramaId", project.toString()); result.put("url", material.getAudioUrl());
            result.put("name", material.getName()); result.put("size", upload.size()); result.put("sha256", upload.sha256());
            result.put("audioKind", material.getAudioKind()); result.put("duration", material.getDuration()); result.put("reference", json.value(reference));
            return result;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(502, "AUDIO_UPLOAD_ARCHIVE_FAILED: 音频归档失败，请使用原请求重试"); }
        finally { if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { } permits.release(); }
    }

    private record DiskMultipart(Path path, String filename, String type, long size) implements MultipartFile {
        public String getName() { return "file"; } public String getOriginalFilename() { return filename; }
        public String getContentType() { return type; } public boolean isEmpty() { return size == 0; } public long getSize() { return size; }
        public byte[] getBytes() throws IOException { throw new IOException("Audio uploads require streaming access"); }
        public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        public void transferTo(File target) throws IOException { Files.copy(path, target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    private String finalizationKey(String id) { try { return "audio-finalize-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static void requireWrite(McpPrincipal caller) { if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED"); }
    private static void requireUnexpired(Pending upload) { if (!Instant.now().isBefore(Instant.parse(upload.expiresAt()))) throw new BizException(410, "AUDIO_UPLOAD_EXPIRED: 请使用新requestId重新申请上传凭证"); }
    private Map<String, Object> prepareSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("requestId", keySchema()); properties.put("dramaId", idSchema()); properties.put("shotId", idSchema());
        properties.put("fileName", Map.of("type", "string", "minLength", 1, "maxLength", 200));
        properties.put("contentType", Map.of("type", "string", "enum", List.of("audio/mpeg", "audio/wav", "audio/flac", "audio/mp4", "audio/ogg")));
        properties.put("size", Map.of("type", "integer", "minimum", 1, "maximum", (int) ResAudioServiceImpl.MAX_BYTES));
        properties.put("sha256", Map.of("type", "string", "pattern", "^[a-fA-F0-9]{64}$"));
        properties.put("audioKind", Map.of("type", "string", "enum", ShotAudioReferenceValidator.AUDIO_KINDS.stream().sorted().toList()));
        properties.put("referenceType", Map.of("type", "string", "enum", ShotAudioReferenceValidator.REFERENCE_TYPES.stream().sorted().toList()));
        properties.put("referenceDescription", Map.of("type", "string", "maxLength", 1000));
        properties.put("text", Map.of("type", "string", "maxLength", 10000));
        properties.put("language", Map.of("type", "string", "enum", ShotAudioReferenceValidator.LANGUAGES.stream().sorted().toList()));
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("requestId", "dramaId", "fileName", "contentType", "size", "sha256", "audioKind", "referenceType"), "properties", properties);
    }
    private static Map<String, Object> keySchema() { return Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{8,80}$"); }
    private static Map<String, Object> idSchema() { return Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$"); }
}
