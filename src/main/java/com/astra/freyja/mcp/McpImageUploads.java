package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.service.MediaAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Receives image content from the client; never fetches a URL or calls an AI provider. */
@Component
@RequiredArgsConstructor
public class McpImageUploads {
    public static final String TOOL_NAME = "upload_asset_image";
    public static final String BATCH_TOOL_NAME = "batch_upload_asset_images";
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_BATCH_ITEMS = 20;
    public static final int MAX_BATCH_BYTES = 20 * 1024 * 1024;
    public static final int MAX_BASE64_CHARS = ((MAX_IMAGE_BYTES + 2) / 3) * 4;
    private static final long MAX_PIXELS = 40_000_000;
    public enum Purpose { CHARACTER, CHARACTER_LOOK, SCENE, PROP, KEYFRAME, SHOT_REFERENCE }
    public record Upload(String requestId, String dramaId, Purpose purpose, String dataBase64,
                         @ToolParam(required = false) String fileName) {}
    public record UploadItem(String clientKey, Purpose purpose, String dataBase64,
                             @ToolParam(required = false) String fileName) {}
    public record BatchUpload(String requestId, String dramaId, List<UploadItem> items) {}
    private record Image(byte[] bytes, String extension, String contentType, int width, int height, String hash) {}
    private final MediaAssetService media;
    private final McpOperationStore journal;
    private final McpScope scope;

    public McpTool<Upload> tool() {
        return new McpTool<>(TOOL_NAME, "上传外部 Agent 的 PNG/JPEG/WebP 图片到 MinIO，单张最多10 MiB。提交纯 Base64（不含 data: 前缀）、授权 dramaId、用途 purpose 和稳定 requestId。返回 url 后用批量业务接口填写人物/造型/场景 referenceImageUrl、道具 coverUrl、关键图 frameUrl 或分镜 refImages。只上传，不调用内部生图或自动覆盖资产。", Upload.class,
                McpTool.Effect.WRITE, schema(), this::upload);
    }

    public Object upload(McpPrincipal caller, Upload input) {
        if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED");
        if (input == null || input.purpose() == null) throw new BizException(400, "必须提供图片上传参数和 purpose");
        Long project = scope.project(caller, input.dramaId());
        McpOperationStore.key(input.requestId());
        String name = input.fileName(); validateName(name);
        Image image = decode(input.dataBase64());
        // The journal hashes only small metadata; the file content is represented by its SHA-256.
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("requestId", input.requestId()); identity.put("dramaId", input.dramaId()); identity.put("purpose", input.purpose());
        identity.put("sha256", image.hash()); identity.put("fileName", name);
        return journal.execute(input.requestId(), TOOL_NAME, identity, () -> {
            // Deterministic path also covers a storage success followed by a database commit failure.
            String path = "mcp/" + project + "/" + input.purpose().name().toLowerCase(Locale.ROOT) + "/" + input.requestId() + "/" + image.hash() + "." + image.extension();
            Map<String, Object> result = store(image, path, name, input.purpose());
            result.put("requestId", input.requestId()); result.put("dramaId", project.toString());
            return new McpOperationStore.Outcome(result, project, false);
        });
    }

    public McpTool<BatchUpload> batchTool() {
        return new McpTool<>(BATCH_TOOL_NAME, "批量上传 1–20 张 PNG/JPEG/静态 WebP 到授权项目，每张最多10 MiB，整批最多20 MiB。每项必填唯一 clientKey、purpose、纯 Base64 dataBase64，可选 fileName。上传前校验全部图片；返回有序 records 与 urlMap，不回显 Base64。原 requestId 和原内容可幂等重试；MinIO 失败可能保留已上传对象，重试覆盖相同路径。上传后用业务 CRUD 关联 URL。", BatchUpload.class,
                McpTool.Effect.WRITE, batchSchema(), this::batchUpload);
    }
    public Object batchUpload(McpPrincipal caller, BatchUpload input) {
        if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED");
        if (input == null || input.items() == null || input.items().isEmpty() || input.items().size() > MAX_BATCH_ITEMS)
            throw new BizException(400, "图片批次必须包含 1–20 项");
        Long project = scope.project(caller, input.dramaId()); McpOperationStore.key(input.requestId());
        Set<String> keys = new HashSet<>(); List<Image> images = new ArrayList<>(); List<Object> identities = new ArrayList<>();
        long total = 0;
        for (int index = 0; index < input.items().size(); index++) {
            var item = input.items().get(index);
            try {
                if (item == null || item.purpose() == null || item.clientKey() == null || !item.clientKey().matches("[A-Za-z0-9_-]{1,80}") || !keys.add(item.clientKey()))
                    throw new BizException(400, "每项需要 purpose 和批次内唯一 clientKey");
                validateName(item.fileName());
                // Bound aggregate decoded memory before allocating the next image.
                if (item.dataBase64() != null && total + (long) item.dataBase64().length() / 4 * 3 - 2 > MAX_BATCH_BYTES)
                    throw new BizException(413, "图片整批最多20 MiB，请拆分批次");
                Image image = decode(item.dataBase64()); total += image.bytes().length;
                if (total > MAX_BATCH_BYTES) throw new BizException(413, "图片整批最多20 MiB，请拆分批次");
                images.add(image);
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("clientKey", item.clientKey()); identity.put("purpose", item.purpose());
                identity.put("fileName", item.fileName()); identity.put("sha256", image.hash()); identities.add(identity);
            } catch (BizException e) { throw new BizException(e.getCode(), "items[" + index + "]: " + e.getMessage()); }
        }
        long totalSize = total;
        return journal.execute(input.requestId(), BATCH_TOOL_NAME, Map.of("dramaId", input.dramaId(), "items", identities), () -> {
            List<Object> results = new ArrayList<>(); Map<String, String> urls = new LinkedHashMap<>();
            for (int index = 0; index < images.size(); index++) {
                var item = input.items().get(index); Image image = images.get(index);
                String path = "mcp/" + project + "/" + item.purpose().name().toLowerCase(Locale.ROOT) + "/" + input.requestId() + "/" + item.clientKey() + "/" + image.hash() + "." + image.extension();
                try {
                    Map<String, Object> result = store(image, path, item.fileName(), item.purpose());
                    result.put("clientKey", item.clientKey()); results.add(result); urls.put(item.clientKey(), (String) result.get("url"));
                } catch (BizException e) { throw new BizException(e.getCode(), "items[" + index + "] (" + item.clientKey() + "): " + e.getMessage()); }
            }
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", project.toString(), "count", results.size(),
                    "totalSize", totalSize, "records", results, "urlMap", urls), project, false);
        });
    }
    private static void validateName(String name) {
        if (name != null && (name.isBlank() || name.length() > 200 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)))
            throw new BizException(400, "fileName 应为不带路径的文件名，最多200字符");
    }
    private Map<String, Object> store(Image image, String path, String name, Purpose purpose) {
        com.astra.freyja.dto.res.AssetUploadResultVO stored;
        try { stored = media.uploadImage(image.bytes(), path, name == null ? "image." + image.extension() : name, image.contentType()); }
        catch (RuntimeException e) { throw new BizException(502, "IMAGE_UPLOAD_FAILED: 图片归档失败，请使用原 requestId 和原内容重试"); }
        return new LinkedHashMap<>(Map.of("purpose", purpose, "url", stored.getUrl(), "contentType", image.contentType(), "size", image.bytes().length,
                "width", image.width(), "height", image.height(), "sha256", image.hash()));
    }

    private Image decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) throw new BizException(400, "dataBase64 不能为空");
        if (encoded.length() > MAX_BASE64_CHARS) throw new BizException(413, "图片最多10 MiB");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new BizException(400, "dataBase64 必须是纯 Base64，不含 data: 前缀或空白"); }
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

    private static Map<String, Object> schema() {
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("requestId", "dramaId", "purpose", "dataBase64"), "properties", Map.of(
                "requestId", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{8,80}$"),
                "dramaId", Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$"),
                "purpose", Map.of("type", "string", "enum", Arrays.stream(Purpose.values()).map(Enum::name).toList()),
                "dataBase64", Map.of("type", "string", "minLength", 1, "maxLength", MAX_BASE64_CHARS, "description", "PNG/JPEG/WebP 图片的纯 Base64；文件最多10 MiB，不含 data: 前缀，不接受 URL 或本地路径"),
                "fileName", Map.of("type", "string", "minLength", 1, "maxLength", 200, "description", "可选文件名，不含路径；存储扩展名按真实图片格式确定")));
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> batchSchema() {
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) schema().get("properties"));
        Object requestId = properties.remove("requestId"), dramaId = properties.remove("dramaId");
        properties.put("clientKey", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,80}$", "description", "批次内唯一标签，对应返回 urlMap 的键"));
        var item = Map.of("type", "object", "additionalProperties", false, "required", List.of("clientKey", "purpose", "dataBase64"), "properties", properties);
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("requestId", "dramaId", "items"), "properties",
                Map.of("requestId", requestId, "dramaId", dramaId, "items", Map.of("type", "array", "minItems", 1, "maxItems", MAX_BATCH_ITEMS, "items", item)));
    }
}
