package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Request validation is separate from schema construction and storage mappings. */
@Component
@RequiredArgsConstructor
public class McpBusinessInputValidator {
    private final McpBusinessSchemas schemas;
    private final McpBusinessOptions options;
    private final McpSchemaMetadata metadata;
    private final DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();
    public void validate(McpRecordType type, Map<String, Object> data, boolean references, boolean create) {
        if (type == null || data == null || (!create && data.isEmpty())) throw new BizException(400, "必须提供记录类型与业务 data，更新补丁不能为空");
        if (create && type == McpRecordType.PROJECT && (!(data.get("aspectRatio") instanceof String ratio) || ratio.isBlank()))
            throw new BizException(400, "ASPECT_RATIO_REQUIRED: 创建短剧前请用户选择系统已有画幅；明确交给 AI 决定时优先16:9，并提交 aspectRatio");
        Map<String, Object> schema = options.apply(schemas.data(type, references, create));
        options.validateChoices(schema, data);
        if (type == McpRecordType.SHOT) {
            validateMedia(data.get("refImages"), true);
            validateMedia(data.get("refAudios"), false);
            // Named batch references are resolved before the normal DTO/save validators run.
            if (!references && data.get("refVideos") != null && validator.validate(schema, data).valid())
                com.astra.freyja.util.ShotVideoReferenceValidator.validate(schemas.videoReferences(data.get("refVideos")));
        }
        if (!validator.validate(schema, data).valid()) throw new BizException(400, "业务字段不符合 Schema：检查必填字段、媒体 URL 和来源 ID；画幅、景别、运镜等选项请通过 get_business_options 读取当前启用值；运行状态、模型及 Skill 等内部字段不能写入，ID 必须为字符串");
    }
    private void validateMedia(Object value, boolean image) {
        if (!(value instanceof List<?> items)) return;
        for (int index = 0; index < items.size(); index++) {
            String path = (image ? "refImages" : "refAudios") + "[" + index + "]";
            if (!(items.get(index) instanceof Map<?, ?> item)) throw new BizException(400, path + " 必须为对象");
            Set<String> allowed = image ? metadata.referenceFields("IMAGE") : metadata.referenceFields("AUDIO");
            if (!allowed.containsAll(item.keySet())) throw new BizException(400, path + " 包含未知字段；图片使用 imageUrl/sourceType/sourceId，声音使用 audioUrl/referenceType");
            String source = image && item.get("sourceType") instanceof String text ? text : null;
            if (image && source == null) throw new BizException(400, path + ".sourceType 必填");
            if (image) com.astra.freyja.util.ShotImageReferenceValidator.validateUsageRole(
                    item.get("usageRole") instanceof String role ? role : null, "data." + path + ".usageRole");
            if (!image && !(item.get("referenceType") instanceof String type && com.astra.freyja.util.ShotAudioReferenceValidator.REFERENCE_TYPES.contains(type))) throw new BizException(400, path + ".referenceType 必须为 H3 四种参考关系之一");
            String urlField = image ? "imageUrl" : "audioUrl";
            boolean sceneLink = image && "ASSET_LINK".equals(item.get("bindingMode"));
            if (sceneLink && (!"SCENE".equals(source)
                    || !Set.of("SCENE_REFERENCE", "SCENE_COVER").contains(Objects.toString(item.get("sourceSlot"), ""))))
                throw new BizException(400, path + " 动态图片引用必须指定 SCENE 来源及明确的 sourceSlot");
            if (!sceneLink && (!(item.get(urlField) instanceof String url) || url.isBlank()))
                throw new BizException(400, path + "." + urlField + " 必填");
            if (image && !"UPLOAD".equals(source) && item.get("sourceId") == null)
                throw new BizException(400, path + ".sourceId 必填；造型图传造型 ID，人物图传人物 ID");
            if (image && "UPLOAD".equals(source) && item.get("sourceId") != null)
                throw new BizException(400, path + ".sourceId 在 UPLOAD 时必须省略或为 null");
            if ((image && Set.of("CHARACTER_LOOK", "CHARACTER_REFERENCE").contains(source) || !image && item.get("audioId") != null) && item.get("characterId") == null)
                throw new BizException(400, path + ".characterId 必填");
            if (image && "KEYFRAME".equals(source) && item.get("usageRole") != null
                    && Arrays.stream(com.astra.freyja.dto.drama.manifest.KeyImageRole.values()).noneMatch(role -> role.name().equals(item.get("usageRole"))))
                throw new BizException(400, path + ".usageRole 对 KEYFRAME 来源仅支持 " + Arrays.toString(com.astra.freyja.dto.drama.manifest.KeyImageRole.values()));
            if (image && "NON_SUBJECT".equals(item.get("usageRole")) && !Set.of("CHARACTER", "CHARACTER_LOOK", "CHARACTER_REFERENCE", "UPLOAD").contains(source))
                throw new BizException(400, path + ".usageRole 的 NON_SUBJECT 仅用于人物或上传参考图");
        }
    }
}
