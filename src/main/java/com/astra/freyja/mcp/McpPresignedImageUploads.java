package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** MCP negotiates a small upload descriptor; image bytes never enter tool arguments. */
@Component
@RequiredArgsConstructor
public class McpPresignedImageUploads {
    public static final String PREPARE_TOOL = "prepare_asset_image_upload";
    public static final String COMPLETE_TOOL = "complete_asset_image_upload";
    public static final int EXPIRY_SECONDS = 15 * 60;

    public record Prepare(String requestId, String dramaId, McpImageAssets.Purpose purpose,
                          String fileName, String contentType, Integer size, String sha256) {}
    public record Complete(String requestId, String dramaId, String uploadId) {}
    record Pending(String uploadId, String dramaId, McpImageAssets.Purpose purpose, String fileName,
                   String contentType, Integer size, String sha256, String bucket, String objectKey, String expiresAt) {}

    private final McpImageTransferStorage storage;
    private final McpImageAssets images;
    private final McpOperationStore journal;
    private final McpScope scope;
    private final McpJson json;

    public McpTool<Prepare> prepareTool() {
        return new McpTool<>(PREPARE_TOOL,
                "首选图片上传方式：申请 MinIO 预签名表单，图片二进制不经过模型或 MCP 参数。提交授权 dramaId、purpose、fileName、真实 contentType、size(1–10485760字节)、客户端计算的sha256和稳定requestId。返回uploadId、uploadUrl、method=POST、fields、fileField、expiresAt。客户端通过已有HTTP/文件能力向uploadUrl发送multipart/form-data，原样附带所有fields，file字段最后放真实文件，再调用complete_asset_image_upload。不能将本地路径当成已上传文件，不需要Base64、不需要额外上传脚本。有效期15分钟；过期使用新requestId重新申请。",
                Prepare.class, McpTool.Effect.WRITE, prepareSchema(), this::prepare);
    }

    public McpTool<Complete> completeTool() {
        return new McpTool<>(COMPLETE_TOOL,
                "确认预签名图片直传并归档。提交授权dramaId、申请返回的uploadId及独立稳定requestId。校验项目、有效期、文件大小、SHA-256和真实PNG/JPEG/静态WebP格式后返回正式url及尺寸。未确认的暂存地址不能填入资产。成功后使用业务CRUD关联referenceImageUrl、frameUrl、coverUrl或refImages，上传不自动覆盖业务记录。超时用原确认requestId重试；已提交的成功结果可重复读取。",
                Complete.class, McpTool.Effect.WRITE, completeSchema(), this::complete);
    }

    public Object prepare(McpPrincipal caller, Prepare input) {
        requireWrite(caller);
        if (input == null || input.purpose() == null || input.fileName() == null)
            throw new BizException(400, "必须提供图片用途、文件名及上传元数据");
        Long project = scope.project(caller, input.dramaId());
        McpOperationStore.key(input.requestId());
        McpImageAssets.validateName(input.fileName());
        if (input.size() == null || input.size() < 1 || input.size() > McpImageAssets.MAX_IMAGE_BYTES)
            throw new BizException(413, "图片必须为1字节到10 MiB");
        if (!Set.of("image/png", "image/jpeg", "image/webp").contains(Objects.toString(input.contentType(), "")))
            throw new BizException(400, "仅支持image/png、image/jpeg和image/webp");
        if (input.sha256() == null || !input.sha256().matches("[a-fA-F0-9]{64}"))
            throw new BizException(400, "sha256 必须为客户端计算的64位十六进制文件摘要");
        Prepare normalized = new Prepare(input.requestId(), project.toString(), input.purpose(), input.fileName(),
                input.contentType(), input.size(), input.sha256().toLowerCase(Locale.ROOT));
        Object saved = journal.execute(input.requestId(), PREPARE_TOOL, normalized, () -> {
            Pending upload = new Pending(input.requestId(), project.toString(), input.purpose(), input.fileName(),
                    input.contentType(), input.size(), normalized.sha256(), storage.bucket(),
                    "mcp/" + project + "/" + UUID.randomUUID(),
                    Instant.now().plusSeconds(EXPIRY_SECONDS).truncatedTo(ChronoUnit.SECONDS).toString());
            return new McpOperationStore.Outcome(upload, project, false);
        });
        Pending upload = json.read(saved, Pending.class);
        requireUnexpired(upload);
        Map<String, Object> result = new LinkedHashMap<>(storage.authorize(upload));
        result.put("uploadId", upload.uploadId()); result.put("dramaId", upload.dramaId());
        result.put("purpose", upload.purpose()); result.put("fileName", upload.fileName());
        result.put("contentType", upload.contentType()); result.put("size", upload.size());
        result.put("sha256", upload.sha256()); result.put("expiresAt", upload.expiresAt());
        return result;
    }

    public Object complete(McpPrincipal caller, Complete input) {
        requireWrite(caller);
        if (input == null) throw new BizException(400, "必须提供图片上传确认参数");
        Long project = scope.project(caller, input.dramaId());
        McpOperationStore.key(input.requestId()); McpOperationStore.key(input.uploadId());
        var receipt = (Map<?, ?>) journal.read(caller, input.uploadId(), PREPARE_TOOL);
        Pending upload = json.read(receipt.get("data"), Pending.class);
        scope.belongs(McpScope.id(upload.dramaId()), project);
        Object result = journal.execute(input.requestId(), COMPLETE_TOOL, input, () -> {
            requireUnexpired(upload);
            McpImageAssets.Image image = McpImageAssets.validateImage(storage.read(upload));
            if (!image.hash().equals(upload.sha256()))
                throw new BizException(400, "IMAGE_HASH_MISMATCH: 上传文件SHA-256与申请不一致");
            if (!image.contentType().equals(upload.contentType()))
                throw new BizException(400, "IMAGE_TYPE_MISMATCH: 真实图片格式与申请不一致");
            String path = "mcp/" + project + "/" + upload.purpose().name().toLowerCase(Locale.ROOT)
                    + "/" + upload.uploadId() + "/" + image.hash() + "." + image.extension();
            Map<String, Object> stored = images.store(image, path, upload.fileName(), upload.purpose());
            stored.put("requestId", input.requestId()); stored.put("uploadId", upload.uploadId());
            stored.put("dramaId", project.toString());
            return new McpOperationStore.Outcome(stored, project, false);
        });
        // An enclosing transaction, if any, must also commit before the source is removed.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { storage.discard(upload); }
            });
        } else storage.discard(upload);
        return result;
    }

    private static void requireWrite(McpPrincipal caller) {
        if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED");
    }
    private static void requireUnexpired(Pending upload) {
        if (!Instant.now().isBefore(Instant.parse(upload.expiresAt())))
            throw new BizException(410, "IMAGE_UPLOAD_EXPIRED: 上传凭证已过期，请使用新requestId重新申请");
    }
    private static Map<String, Object> prepareSchema() {
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("requestId", "dramaId", "purpose", "fileName", "contentType", "size", "sha256"),
                "properties", Map.of(
                        "requestId", requestKey(), "dramaId", projectId(),
                        "purpose", Map.of("type", "string", "enum", Arrays.stream(McpImageAssets.Purpose.values()).map(Enum::name).toList()),
                        "fileName", Map.of("type", "string", "minLength", 1, "maxLength", 200),
                        "contentType", Map.of("type", "string", "enum", List.of("image/png", "image/jpeg", "image/webp")),
                        "size", Map.of("type", "integer", "minimum", 1, "maximum", McpImageAssets.MAX_IMAGE_BYTES),
                        "sha256", Map.of("type", "string", "pattern", "^[a-fA-F0-9]{64}$")));
    }
    private static Map<String, Object> completeSchema() {
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("requestId", "dramaId", "uploadId"),
                "properties", Map.of("requestId", requestKey(), "dramaId", projectId(), "uploadId", requestKey()));
    }
    private static Map<String, Object> requestKey() { return Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{8,80}$"); }
    private static Map<String, Object> projectId() { return Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$"); }
}
