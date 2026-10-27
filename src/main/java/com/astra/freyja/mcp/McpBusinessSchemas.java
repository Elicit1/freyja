package com.astra.freyja.mcp;

import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.director.model.DirectorPlanSchema;
import com.astra.freyja.util.ShotSeedUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Component;

import java.util.*;

/** Whitelisted business inputs, separate from the wide DTOs used by the internal AI/UI. */
@Component
@RequiredArgsConstructor
public class McpBusinessSchemas {
    private final McpJson json;
    private final McpSchemaMetadata metadata;
    private static final Set<String> IDS = Set.of("episodeId", "sceneId", "shotGroupId", "resSceneId", "resKeyframeId", "characterId", "lookId", "propId", "shotId");
    public Class<?> dto(McpRecordType type) {
        return switch (type) {
            case PROJECT -> DramaDTO.class; case EPISODE -> DramaEpisodeDTO.class; case SCENE -> DramaSceneDTO.class;
            case SHOT_GROUP -> DramaShotGroupDTO.class; case SHOT -> DramaShotDTO.class;
            case CHARACTER -> ResCharacterDTO.class; case CHARACTER_LOOK -> ResCharacterLookDTO.class;
            case RESOURCE_SCENE -> ResSceneDTO.class; case PROP -> ResPropDTO.class; case KEYFRAME -> ResKeyframeDTO.class;
        };
    }
    public Set<String> fields(McpRecordType type) { return metadata.fields(type); }

    public List<ShotRefVideoDTO> videoReferences(Object value) {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<List<ShotRefVideoDTO>>() {});
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> data(McpRecordType type, boolean references, boolean create) {
        try {
            Map<String, Object> source = json.mapper().readValue(JsonSchemaGenerator.generateForType(dto(type)), Map.class);
            Map<String, Object> original = (Map<String, Object>) source.get("properties");
            Map<String, Object> properties = new TreeMap<>();
            for (String field : metadata.fields(type)) {
                if (!original.containsKey(field)) throw new IllegalStateException("DTO 缺少业务字段: " + type + "." + field);
                Object schema = original.get(field);
                if (IDS.contains(field)) schema = identifier(references, type == McpRecordType.SHOT && Set.of("resSceneId", "resKeyframeId").contains(field));
                if (metadata.contentFields().contains(field)) schema = Map.of("type", List.of("string", "null"));
                if (metadata.fieldSchema(type, field) != null) schema = metadata.fieldSchema(type, field);
                if (field.equals("characterRefs")) schema = array(30, nested(CharacterShotRefDTO.class, references, List.of("characterId")));
                if (field.equals("propRefs")) schema = array(30, nested(PropShotRefDTO.class, references, List.of("propId")));
                if (field.equals("refImages")) schema = array(9, nested(ShotRefImageDTO.class, references, List.of("sourceType", "imageUrl", "usageRole")));
                if (field.equals("refAudios")) schema = array(3, nested(ShotRefAudioDTO.class, references, List.of("audioUrl", "referenceType")));
                if (field.equals("refVideos")) schema = Map.of("type", "array", "items", nested(ShotRefVideoDTO.class, references, List.of("id", "sourceType")), "description", "有序参考视频；引用数量在预检与渲染时根据所选模型 maxVideos 配置校验");
                if (field.equals("directorPlan")) schema = directorPlan();
                if (field.equals("seed")) schema = described(schema, "1 到 " + ShotSeedUtil.MAX_SEED + " 的整数字符串");
                String description = description(type, field);
                properties.put(field, description == null ? schema : described(schema, appendChoices(schema, description)));
            }
            Map<String, Object> result = new LinkedHashMap<>(object(properties, create ? required(type) : List.of()));
            if (create) {
                for (String name : required(type)) if (!IDS.contains(name)) properties.put(name, described(nonblank(), description(type, name)));
                if (type == McpRecordType.CHARACTER) result.put("anyOf", atLeastOne("name", "canonicalName", "displayName"));
                if (type == McpRecordType.KEYFRAME) result.put("anyOf", atLeastOne("frameUrl", "description", "prompt"));
            } else {
                result.put("minProperties", 1);
                result.put("description", "补丁：仅传变化字段，省略保留。数组整体替换，[] 清空；characterRefs/propRefs 保留同身份项的未提交细节。内容文本用空字符串或 null 清空；其他 null 仅在对应字段 Schema 明确允许时可用。媒体 URL 用空字符串清空，directorPlan 清空受模式和创作者锁约束。");
                if (type == McpRecordType.SHOT) result.put("examples", List.of(Map.of("negativePrompt", "blur")));
            }
            return result;
        } catch (Exception e) { throw new IllegalStateException("业务 Schema 生成失败: " + type, e); }
    }

    private static Map<String, Object> array(int max, Object items) { return Map.of("type", "array", "maxItems", max, "items", items); }
    private static Map<String, Object> nonblank() { return Map.of("type", "string", "pattern", ".*\\S.*"); }
    private static Map<String, Object> described(Object schema, String description) {
        @SuppressWarnings("unchecked") Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) schema);
        if (description != null) result.put("description", description);
        return result;
    }
    private List<String> required(McpRecordType type) { return metadata.required(type); }
    private static List<Object> atLeastOne(String... fields) {
        return Arrays.stream(fields).map(name -> (Object) Map.of("required", List.of(name), "properties", Map.of(name, nonblank()))).toList();
    }
    private String description(McpRecordType type, String field) { return metadata.description(type, field); }
    @SuppressWarnings("unchecked") private Map<String, Object> nested(Class<?> type, boolean refs, List<String> required) throws Exception {
        Map<String, Object> source = json.mapper().readValue(JsonSchemaGenerator.generateForType(type), Map.class);
        Map<String, Object> properties = new TreeMap<>((Map<String, Object>) source.get("properties"));
        String kind = type == CharacterShotRefDTO.class ? "CHARACTER" : type == PropShotRefDTO.class ? "PROP"
                : type == ShotRefImageDTO.class ? "IMAGE" : type == ShotRefVideoDTO.class ? "VIDEO" : "AUDIO";
        Set<String> allowed = metadata.referenceFields(kind);
        if (!properties.keySet().containsAll(allowed)) throw new IllegalStateException("DTO 缺少媒体引用业务字段: " + kind);
        properties.keySet().retainAll(allowed);
        for (String name : properties.keySet()) {
            if (IDS.contains(name) || name.equals("sourceId") || name.equals("audioId") || name.equals("resAudioId") || name.equals("sourceShotId") || name.equals("sourceVideoTakeId")) properties.put(name, identifier(refs, !required.contains(name)));
            else if (type != ShotRefAudioDTO.class || !name.equals("duration")) properties.put(name, Map.of("type", List.of("string", "null")));
        }
        properties.putAll(metadata.referenceSchemas(kind));
        for (String name : allowed) {
            String description = metadata.description(kind, name);
            if (description != null) properties.put(name, described(properties.get(name), appendChoices(properties.get(name), description)));
        }
        // Plain objects stay readable in MCP clients; conditional media rules live in the validator.
        return object(properties, required);
    }

    private static String appendChoices(Object schema, String description) {
        if (schema instanceof Map<?, ?> map && map.containsKey("x-freyja-options"))
            return description + " " + map.getOrDefault("description", null);
        return description;
    }
    @SuppressWarnings("unchecked") public Map<String, Object> directorPlan() throws Exception {
        Map<String, Object> schema = json.mapper().readValue(DirectorPlanSchema.jsonSchema(), Map.class);
        schema.remove("$schema");
        ((Map<String, Object>) schema.get("properties")).put("lockedFields", array(7, Map.of("type", "string", "enum",
                List.of("duration", "shotSize", "cameraAngle", "cameraBeats", "subjectAction", "gaze", "narrativeIntent"))));
        closeObjects(schema);
        return Map.of("oneOf", List.of(schema, Map.of("type", "null")));
    }
    @SuppressWarnings("unchecked") private void closeObjects(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("object".equals(map.get("type"))) ((Map<String, Object>) map).put("additionalProperties", false);
            new ArrayList<>(map.values()).forEach(this::closeObjects);
        } else if (value instanceof List<?> list) list.forEach(this::closeObjects);
    }

    public Map<String, Object> create() { return batchCreate(); }
    public Map<String, Object> singleGet(McpRecordType type) {
        return object(Map.of("dramaId", identifier(false, false), "recordId", identifier(false, false), "fields", projection(type)), List.of("recordId"));
    }
    public Map<String, Object> singleCreate(McpRecordType type) {
        return object(Map.of("requestId", requestId(), "dramaId", identifier(false, false), "data", data(type, false, true)),
                type == McpRecordType.PROJECT ? List.of("requestId", "data") : List.of("requestId", "dramaId", "data"));
    }
    public Map<String, Object> singleUpdate(McpRecordType type) {
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("requestId", requestId(), "dramaId", identifier(false, false), "recordId", identifier(false, false),
                "expectedFingerprint", fingerprint(), "data", data(type, false, false)));
        if (type == McpRecordType.SHOT) properties.put("fields", projection(type));
        return object(properties, List.of("requestId", "recordId", "expectedFingerprint", "data"));
    }
    public Map<String, Object> singleDelete() {
        return object(Map.of("requestId", requestId(), "dramaId", identifier(false, false), "recordId", identifier(false, false),
                "expectedFingerprint", fingerprint()), List.of("requestId", "recordId", "expectedFingerprint"));
    }
    public Map<String, Object> batchUpdate(McpRecordType type) {
        var item = object(Map.of("recordId", identifier(false, false), "expectedFingerprint", fingerprint(), "data", data(type, false, false)),
                List.of("recordId", "expectedFingerprint", "data"));
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("requestId", requestId(), "dramaId", identifier(false, false),
                "items", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", item)));
        if (type == McpRecordType.SHOT) properties.put("fields", projection(type));
        return object(properties, List.of("requestId", "items"));
    }
    public static Map<String, Object> projection(McpRecordType type) {
        return Map.of("type", "array", "items", Map.of("type", "string", "enum", new ArrayList<>(McpBusinessView.publicFields(type.viewKind()))),
                "description", "仅返回所选公开顶层字段；省略返回全量；空数组仅返回 id/editFingerprint。不支持嵌套路径。");
    }
    private static Map<String, Object> requestId() { return Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{8,80}$"); }
    private static Map<String, Object> fingerprint() { return Map.of("type", "string", "pattern", "^[a-f0-9]{64}$"); }
    public Map<String, Object> delete() {
        Map<String, Object> item = object(Map.of(
                "type", Map.of("type", "string", "enum", Arrays.stream(McpRecordType.values()).map(Enum::name).toList()),
                "recordId", identifier(false, false),
                "expectedFingerprint", Map.of("type", "string", "pattern", "^[a-f0-9]{64}$")),
                List.of("type", "recordId", "expectedFingerprint"));
        return object(Map.of("requestId", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{8,80}$"),
                "dramaId", identifier(false, false), "items", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", item)),
                List.of("requestId", "items"));
    }
    private Map<String, Object> batchCreate() {
        List<Object> alternatives = new ArrayList<>();
        for (McpRecordType type : McpRecordType.values()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("type", Map.of("type", "string", "const", type.name()));
            properties.put("data", data(type, true, true));
            properties.put("clientKey", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,80}$"));
            alternatives.add(object(properties, List.of("type", "clientKey", "data")));
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("requestId", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{8,80}$"));
        props.put("dramaId", identifier(false, false));
        props.put("items", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", Map.of("oneOf", alternatives)));
        return object(props, List.of("requestId", "items"));
    }

    private static Map<String, Object> identifier(boolean references, boolean nullable) {
        List<Object> choices = new ArrayList<>();
        choices.add(Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$"));
        if (references) choices.add(object(Map.of("ref", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,80}$")), List.of("ref")));
        if (nullable) choices.add(Map.of("type", "null"));
        return choices.size() == 1 ? (Map<String, Object>) choices.getFirst() : Map.of("oneOf", choices);
    }
    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
}
