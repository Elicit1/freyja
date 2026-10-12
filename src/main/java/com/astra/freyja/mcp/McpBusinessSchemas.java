package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.res.*;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Component;

import java.util.*;

/** Whitelisted business inputs, separate from the wide DTOs used by the internal AI/UI. */
@Component
@RequiredArgsConstructor
public class McpBusinessSchemas {
    private final McpJson json;
    private final DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();
    private static final Set<String> IDS = Set.of("episodeId", "sceneId", "shotGroupId", "resSceneId", "resKeyframeId", "characterId", "lookId", "propId", "shotId");
    private static final Map<McpRecordType, Set<String>> FIELDS = Map.ofEntries(
            Map.entry(McpRecordType.PROJECT, fields("title,coverUrl,genre,targetEpisodes,aspectRatio,stylePreset,styleTone,synopsis,status,sortOrder,remark")),
            Map.entry(McpRecordType.EPISODE, fields("episodeNo,title,summary,scriptContent,targetDuration,status,sortOrder,remark")),
            Map.entry(McpRecordType.SCENE, fields("episodeId,sceneNo,name,resSceneId,sceneType,timeOfDay,weatherAtmosphere,locationName,summary,scriptContent,sortOrder,remark")),
            Map.entry(McpRecordType.SHOT_GROUP, fields("sceneId,groupNo,name,purpose,sortOrder,remark")),
            Map.entry(McpRecordType.SHOT, fields("sceneId,shotGroupId,shotNo,shotName,shotType,cameraMovement,shotTypeLocked,cameraMovementLocked,duration,scriptContent,actionDescription,dialogue,dialogueSpeaker,voiceover,soundEffect,resSceneId,resKeyframeId,characterRefs,propRefs,stylePreset,sortOrder,remark")),
            Map.entry(McpRecordType.CHARACTER, fields("name,canonicalName,displayName,gender,ageGroup,roleType,personality,appearanceDesc,referenceImageUrl,voiceSampleUrl,sortOrder,status,firstAppearance,lastAppearance,remark,aliases")),
            Map.entry(McpRecordType.CHARACTER_LOOK, fields("characterId,lookName,isDefault,lookType,designDesc,referenceImageUrl,sortOrder,status,remark")),
            Map.entry(McpRecordType.RESOURCE_SCENE, fields("name,description,coverUrl,referenceImageUrl,sceneType,timeOfDay,weatherAtmosphere,sortOrder,status,remark")),
            Map.entry(McpRecordType.PROP, fields("name,propType,description,coverUrl,sortOrder,status,remark")),
            Map.entry(McpRecordType.KEYFRAME, fields("shotId,name,frameType,description,frameUrl,clearFrameUrl,sourceType,aspectRatio,sortOrder,status,remark"))
    );
    public Class<?> dto(McpRecordType type) {
        return switch (type) {
            case PROJECT -> DramaDTO.class; case EPISODE -> DramaEpisodeDTO.class; case SCENE -> DramaSceneDTO.class;
            case SHOT_GROUP -> DramaShotGroupDTO.class; case SHOT -> DramaShotDTO.class;
            case CHARACTER -> ResCharacterDTO.class; case CHARACTER_LOOK -> ResCharacterLookDTO.class;
            case RESOURCE_SCENE -> ResSceneDTO.class; case PROP -> ResPropDTO.class; case KEYFRAME -> ResKeyframeDTO.class;
        };
    }
    public Set<String> fields(McpRecordType type) { return FIELDS.get(type); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> data(McpRecordType type, boolean references) {
        try {
            Map<String, Object> source = json.mapper().readValue(JsonSchemaGenerator.generateForType(dto(type)), Map.class);
            Map<String, Object> original = (Map<String, Object>) source.get("properties");
            Map<String, Object> properties = new TreeMap<>();
            for (String field : FIELDS.get(type)) {
                if (!original.containsKey(field)) throw new IllegalStateException("DTO 缺少业务字段: " + type + "." + field);
                Object schema = original.get(field);
                if (IDS.contains(field)) schema = identifier(references, type == McpRecordType.SHOT && Set.of("resSceneId", "resKeyframeId").contains(field));
                if (field.equals("characterRefs")) schema = Map.of("type", "array", "maxItems", 30, "items", object(Map.of(
                        "characterId", identifier(references, false), "lookId", identifier(references, false)), List.of("characterId")));
                if (field.equals("propRefs")) schema = Map.of("type", "array", "maxItems", 30, "items", object(Map.of("propId", identifier(references, false)), List.of("propId")));
                if (field.equals("aliases")) schema = Map.of("type", "array", "maxItems", 50, "items", Map.of("type", "string", "maxLength", 200));
                properties.put(field, schema);
            }
            return object(properties, List.of());
        } catch (Exception e) { throw new IllegalStateException("业务 Schema 生成失败: " + type, e); }
    }

    public Map<String, Object> create() { return batch(false); }
    public Map<String, Object> update() { return batch(true); }
    private Map<String, Object> batch(boolean update) {
        List<Object> alternatives = new ArrayList<>();
        for (McpRecordType type : McpRecordType.values()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("type", Map.of("type", "string", "const", type.name()));
            properties.put("data", data(type, !update));
            if (update) {
                properties.put("recordId", identifier(false, false));
                properties.put("expectedFingerprint", Map.of("type", "string", "pattern", "^[a-f0-9]{64}$"));
            } else properties.put("clientKey", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,80}$"));
            alternatives.add(object(properties, update ? List.of("type", "recordId", "expectedFingerprint", "data") : List.of("type", "clientKey", "data")));
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("requestId", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{8,80}$"));
        props.put("dramaId", identifier(false, false));
        props.put("items", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", Map.of("oneOf", alternatives)));
        return object(props, update ? List.of("requestId", "dramaId", "items") : List.of("requestId", "items"));
    }

    public void validate(McpRecordType type, Map<String, Object> data, boolean references) {
        if (data == null || data.isEmpty()) throw new BizException(400, "业务 data 不能为空");
        if (!validator.validate(data(type, references), data).valid()) throw new BizException(400, "业务字段不符合 Schema；内部 AI 字段不允许写入，ID 必须为字符串");
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
    private static Set<String> fields(String input) { return Set.of(input.split(",")); }
}
