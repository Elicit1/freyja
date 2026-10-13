package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.director.model.DirectorPlanSchema;
import com.astra.freyja.util.ShotSeedUtil;
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
    public static final Set<String> CHARACTER_REFERENCE_FIELDS = fields("characterId,lookId,designDesc,actionPrompt,emotionPrompt,positionTag");
    public static final Set<String> PROP_REFERENCE_FIELDS = fields("propId,propName,propType,propPrompt");
    public static final Set<String> IMAGE_REFERENCE_FIELDS = fields("id,sourceType,sourceId,characterId,lookId,name,imageUrl,usageRole,referenceRole");
    public static final Set<String> AUDIO_REFERENCE_FIELDS = fields("id,sourceType,characterId,characterName,audioUrl,duration,name,text,usageMode,language");
    public static final Map<String, String> CONTENT_COLUMNS = Map.ofEntries(Map.entry("prompt", "prompt"), Map.entry("firstFramePrompt", "first_frame_prompt"),
            Map.entry("endFramePrompt", "end_frame_prompt"), Map.entry("negativePrompt", "negative_prompt"), Map.entry("videoPrompt", "video_prompt"),
            Map.entry("customScenePrompt", "custom_scene_prompt"), Map.entry("appearancePrompt", "appearance_prompt"), Map.entry("outfitPrompt", "outfit_prompt"),
            Map.entry("scenePrompt", "scene_prompt"), Map.entry("propPrompt", "prop_prompt"));
    private static final Set<String> IDS = Set.of("episodeId", "sceneId", "shotGroupId", "resSceneId", "resKeyframeId", "characterId", "lookId", "propId", "shotId");
    private static final Map<McpRecordType, Set<String>> FIELDS = Map.ofEntries(
            Map.entry(McpRecordType.PROJECT, fields("title,coverUrl,genre,targetEpisodes,aspectRatio,stylePreset,styleTone,synopsis,status,sortOrder,remark")),
            Map.entry(McpRecordType.EPISODE, fields("episodeNo,title,summary,scriptContent,targetDuration,status,sortOrder,remark")),
            Map.entry(McpRecordType.SCENE, fields("episodeId,sceneNo,name,resSceneId,sceneType,timeOfDay,weatherAtmosphere,locationName,summary,scriptContent,sortOrder,remark")),
            Map.entry(McpRecordType.SHOT_GROUP, fields("sceneId,groupNo,name,purpose,sortOrder,remark")),
            Map.entry(McpRecordType.SHOT, fields("sceneId,shotGroupId,shotNo,shotName,shotType,cameraMovement,shotTypeLocked,cameraMovementLocked,duration,scriptContent,actionDescription,dialogue,dialogueSpeaker,voiceover,soundEffect,resSceneId,resKeyframeId,characterRefs,propRefs,stylePreset,sortOrder,remark,prompt,firstFramePrompt,endFramePrompt,negativePrompt,videoPrompt,customScenePrompt,directorPlan,directorPlanMode,refImages,refAudios,generationMode,seed,previewImageUrl,endFrameImageUrl,audioUrl")),
            Map.entry(McpRecordType.CHARACTER, fields("name,canonicalName,displayName,gender,ageGroup,roleType,personality,appearanceDesc,referenceImageUrl,voiceSampleUrl,sortOrder,status,firstAppearance,lastAppearance,remark,aliases,appearancePrompt,negativePrompt")),
            Map.entry(McpRecordType.CHARACTER_LOOK, fields("characterId,lookName,isDefault,lookType,designDesc,referenceImageUrl,sortOrder,status,remark,outfitPrompt,appearancePrompt,negativePrompt")),
            Map.entry(McpRecordType.RESOURCE_SCENE, fields("name,description,coverUrl,referenceImageUrl,sceneType,timeOfDay,weatherAtmosphere,sortOrder,status,remark,scenePrompt,negativePrompt")),
            Map.entry(McpRecordType.PROP, fields("name,propType,description,coverUrl,sortOrder,status,remark,propPrompt")),
            Map.entry(McpRecordType.KEYFRAME, fields("shotId,name,frameType,description,frameUrl,clearFrameUrl,sourceType,aspectRatio,sortOrder,status,remark,prompt,negativePrompt"))
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
    public Map<String, Object> data(McpRecordType type, boolean references, boolean create) {
        try {
            Map<String, Object> source = json.mapper().readValue(JsonSchemaGenerator.generateForType(dto(type)), Map.class);
            Map<String, Object> original = (Map<String, Object>) source.get("properties");
            Map<String, Object> properties = new TreeMap<>();
            for (String field : FIELDS.get(type)) {
                if (!original.containsKey(field)) throw new IllegalStateException("DTO 缺少业务字段: " + type + "." + field);
                Object schema = original.get(field);
                if (IDS.contains(field)) schema = identifier(references, type == McpRecordType.SHOT && Set.of("resSceneId", "resKeyframeId").contains(field));
                if (CONTENT_COLUMNS.containsKey(field)) schema = Map.of("type", List.of("string", "null"));
                if (field.equals("characterRefs")) schema = array(30, nested(CharacterShotRefDTO.class, references, List.of("characterId")));
                if (field.equals("propRefs")) schema = array(30, nested(PropShotRefDTO.class, references, List.of("propId")));
                if (field.equals("refImages")) schema = described(array(9, nested(ShotRefImageDTO.class, references, List.of("sourceType", "imageUrl"))),
                        "按提交顺序保存；第1项对应 <Picture 1>。与 prompt 一起更新以保持图号一致，不会根据文字猜测主体绑定。");
                if (field.equals("refAudios")) schema = described(array(3, nested(ShotRefAudioDTO.class, references, List.of("sourceType", "audioUrl"))),
                        "按提交顺序保存；第1项对应 <Audio 1>。只关联已有音频，不生成声音。");
                if (field.equals("directorPlan")) schema = directorPlan();
                if (field.equals("directorPlanMode")) schema = Map.of("type", "string", "enum", List.of("AUTO", "MANUAL"));
                if (field.equals("generationMode")) schema = Map.of("type", "string", "enum", List.of("FIRST_LAST_FRAME", "REFERENCE_MODE"));
                if (field.equals("seed")) schema = Map.of("type", "string", "pattern", "^[1-9][0-9]{0,15}$", "description", "1 到 " + ShotSeedUtil.MAX_SEED + " 的整数字符串");
                if (field.equals("aliases")) schema = Map.of("type", "array", "maxItems", 50, "items", Map.of("type", "string", "maxLength", 200));
                if (type == McpRecordType.PROJECT && field.equals("genre")) schema = Map.of("type", "string", "maxLength", 64);
                String description = description(type, field);
                properties.put(field, description == null ? schema : described(schema, description));
            }
            Map<String, Object> result = new LinkedHashMap<>(object(properties, create ? required(type) : List.of()));
            if (create) {
                for (String name : required(type)) if (!IDS.contains(name)) properties.put(name, described(nonblank(), description(type, name)));
                if (type == McpRecordType.CHARACTER) result.put("anyOf", atLeastOne("name", "canonicalName", "displayName"));
                if (type == McpRecordType.KEYFRAME) result.put("anyOf", atLeastOne("frameUrl", "description", "prompt"));
            } else result.put("minProperties", 1);
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
    private static List<String> required(McpRecordType type) {
        return switch (type) {
            case PROJECT -> List.of("title", "aspectRatio"); case SCENE -> List.of("episodeId");
            case SHOT_GROUP, SHOT -> List.of("sceneId");
            case CHARACTER_LOOK -> List.of("characterId", "lookName", "designDesc");
            case RESOURCE_SCENE, PROP, KEYFRAME -> List.of("name");
            case EPISODE, CHARACTER -> List.of();
        };
    }
    private static List<Object> atLeastOne(String... fields) {
        return Arrays.stream(fields).map(name -> (Object) Map.of("required", List.of(name), "properties", Map.of(name, nonblank()))).toList();
    }
    private static String description(McpRecordType type, String field) {
        if (field.equals("directorPlan")) return "可选的手工导演计划。外部 Agent 通常省略，直接保存提示词工作台内容；服务端不会补生成计划。";
        if (field.equals("directorPlanMode")) return "可选高级编辑；不使用导演计划时省略，更新时保留已有计划。AUTO 仅重置，不启动 AI。";
        if (type == McpRecordType.PROJECT && field.equals("genre")) return "题材为最长64字符的自由文本；drama_genre 字典仅提供建议，可使用已有字典值或自定义题材名称（如都市复仇、悬疑轻喜剧）。未收录的题材直接填写清晰名称，无需编造枚举代码。剧集继承项目题材。";
        if (type == McpRecordType.PROJECT && field.equals("aspectRatio")) return "创建必填；先读取 get_business_options(dictType=drama_aspect_ratio)。用户未指定时请用户选择已有比例，再创建；仅在用户明确交给 AI 决定时优先选启用的16:9，不自行默认9:16或编造比例。修改时省略保留原值。";
        if (type == McpRecordType.SHOT) return switch (field) {
            case "prompt" -> "REFERENCE_MODE 的最终 H3 六段式视频卡片：subject_definitions、summary、retention_analysis、detailed_description、overall_soundscape、non_diegetic_music。先读取 h3-prompt-writing Skill 及 references/ref-en.txt；<Picture N>/<Audio N> 对应媒体数组第 N 项。";
            case "videoPrompt" -> "FIRST_LAST_FRAME 的最终 H3 三段式视频卡片：integrated_multimodal_description、overall_soundscape、non_diegetic_music。先读取 h3-prompt-writing Skill 及 references/base-en.txt；不能仅填写图片提示词后宣称视频卡片完成。";
            case "firstFramePrompt", "endFramePrompt" -> "首尾帧模式的图片提示词，可省略；已有图片可直接保存对应 URL。";
            case "generationMode" -> "创建时默认 FIRST_LAST_FRAME；多模态参考卡片使用 REFERENCE_MODE。";
            case "sceneId" -> "创建必填的归属场次；剧集和项目由服务端校验确定。";
            case "shotType", "cameraMovement" -> "基本规格与机位卡片；允许 AUTO（自动），外部 Agent 无明确选择时填写 AUTO，并保持对应 Locked=false；具体枚举读取业务字典。";
            case "characterRefs" -> "出镜人物业务关联：characterId 为人物 ID，lookId 为明确选定的造型 ID；先读取默认造型。动作/情绪/站位由外部 Agent 按剧本填写，不由 Java 猜测。";
            case "propRefs" -> "关联镜头实际使用的道具资产，propId 为真实道具 ID。";
            case "resSceneId" -> "镜头场景资产；可继承场次设置。与 resKeyframeId 互斥。";
            case "resKeyframeId" -> "主视觉关键图资产 ID；与 resSceneId 互斥，额外场景/关键图可通过 refImages 关联。";
            default -> null;
        };
        if ((type == McpRecordType.CHARACTER || type == McpRecordType.CHARACTER_LOOK) && field.equals("referenceImageUrl"))
            return "人物/造型设定图 URL；生成图片前先读 character-sheet-design Skill，遵循用户的三视图/头身分区/细节/透明或白底要求，上传后填写返回 URL。已有图片可直接关联。";
        if (type == McpRecordType.CHARACTER && field.equals("appearancePrompt")) return "可选的稳定身份外貌 Prompt；设定图版式和导出要求按 character-sheet-design Skill 写入图片生成指令，不混入稳定身份描述。";
        if (type == McpRecordType.CHARACTER_LOOK) return switch (field) {
            case "designDesc" -> "创建必填的造型视觉概念描述，与可选英文 Prompt 不同。";
            case "outfitPrompt" -> "可选的服装专属 Prompt，已有图片时也不要求填写。";
            case "appearancePrompt" -> "可选的该造型特有妆发/状态；无明确状态可以省略。";
            default -> null;
        };
        return null;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> nested(Class<?> type, boolean refs, List<String> required) throws Exception {
        Map<String, Object> source = json.mapper().readValue(JsonSchemaGenerator.generateForType(type), Map.class);
        Map<String, Object> properties = new TreeMap<>((Map<String, Object>) source.get("properties"));
        Set<String> allowed = type == CharacterShotRefDTO.class ? CHARACTER_REFERENCE_FIELDS : type == PropShotRefDTO.class ? PROP_REFERENCE_FIELDS
                : type == ShotRefImageDTO.class ? IMAGE_REFERENCE_FIELDS : AUDIO_REFERENCE_FIELDS;
        properties.keySet().retainAll(allowed);
        for (String name : properties.keySet()) {
            if (IDS.contains(name) || name.equals("sourceId")) properties.put(name, identifier(refs, !required.contains(name)));
            else if (type != ShotRefAudioDTO.class || !name.equals("duration")) properties.put(name, Map.of("type", List.of("string", "null")));
        }
        Map<String, Object> result = new LinkedHashMap<>(object(properties, required));
        if (type == ShotRefImageDTO.class || type == ShotRefAudioDTO.class) {
            properties.put("id", described(Map.of("type", "string", "pattern", ".*\\S.*", "maxLength", 200), "可省略，服务端补全槽位标识；显式值须在数组中唯一，非雪花 ID。"));
        }
        if (type == ShotRefImageDTO.class) {
            properties.put("sourceType", described(Map.of("type", "string", "enum", List.of("SCENE", "CHARACTER", "CHARACTER_LOOK", "CHARACTER_REFERENCE", "PROP", "KEYFRAME", "UPLOAD")),
                    "人物身份图用 CHARACTER；人物造型图用 CHARACTER_LOOK（兼容 CHARACTER_REFERENCE，保存为 CHARACTER_REFERENCE）；二者 sourceId 含义不同。"));
            properties.put("imageUrl", described(nonblank(), "必填的已有参考图 URL；服务端不抓取外部 URL、不调用内部生图。"));
            properties.put("sourceId", described(identifier(refs, true), "除 UPLOAD 外必填；CHARACTER 为人物 ID，CHARACTER_LOOK/CHARACTER_REFERENCE 为造型 ID并补齐 lookId；SCENE/PROP/KEYFRAME 为对应资产 ID。UPLOAD 省略。"));
            properties.put("characterId", described(identifier(refs, true), "显式人物弱绑定；CHARACTER_LOOK/CHARACTER_REFERENCE 必填，UPLOAD 可按需关联，画外人物也可绑定。"));
            properties.put("usageRole", described(properties.get("usageRole"), "按需明确 SUBJECT（出镜主体）/NON_SUBJECT（画外人物）、场景、道具、风格或关键图用途；不从文字推断。"));
            properties.put("referenceRole", Map.of("type", List.of("string", "null"), "enum", Arrays.asList("IDENTITY", "LOOK", "COMBINED", "POSE", "STYLE", null)));
            // Keep items a plain object: clients render allOf/if/then as unknown intersections.
            // Conditional requirements are enforced below before any business writes.
        }
        if (type == ShotRefAudioDTO.class) {
            properties.put("sourceType", Map.of("type", "string", "enum", List.of("TTS", "UPLOAD")));
            properties.put("audioUrl", described(nonblank(), "必填的已有声音 URL；TTS 只表示来源，不执行声音生成。"));
            properties.put("duration", Map.of("type", List.of("number", "null"), "minimum", 2, "maximum", 15, "description", "可选的真实秒数；已知音频总长不超过15秒，不会猜测时长。"));
            properties.put("usageMode", described(properties.get("usageMode"), "按需指定 VOICE_TIMBRE、DIALOGUE_REUSE、BGM_REUSE、BGM_STYLE、SFX_REUSE、SFX_REFERENCE、RHYTHM_REFERENCE。"));
        }
        return result;
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
        return object(Map.of("dramaId", identifier(false, false), "recordId", identifier(false, false)),
                type.assetType() == null ? List.of("dramaId", "recordId") : List.of("recordId"));
    }
    public Map<String, Object> singleCreate(McpRecordType type) {
        return object(Map.of("requestId", requestId(), "dramaId", identifier(false, false), "data", data(type, false, true)),
                type == McpRecordType.PROJECT ? List.of("requestId", "data") : List.of("requestId", "dramaId", "data"));
    }
    public Map<String, Object> singleUpdate(McpRecordType type) {
        return object(Map.of("requestId", requestId(), "dramaId", identifier(false, false), "recordId", identifier(false, false),
                "expectedFingerprint", fingerprint(), "data", data(type, false, false)),
                List.of("requestId", "dramaId", "recordId", "expectedFingerprint", "data"));
    }
    public Map<String, Object> singleDelete() {
        return object(Map.of("requestId", requestId(), "dramaId", identifier(false, false), "recordId", identifier(false, false),
                "expectedFingerprint", fingerprint()), List.of("requestId", "dramaId", "recordId", "expectedFingerprint"));
    }
    public Map<String, Object> batchUpdate(McpRecordType type) {
        var item = object(Map.of("recordId", identifier(false, false), "expectedFingerprint", fingerprint(), "data", data(type, false, false)),
                List.of("recordId", "expectedFingerprint", "data"));
        return object(Map.of("requestId", requestId(), "dramaId", identifier(false, false),
                "items", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", item)), List.of("requestId", "dramaId", "items"));
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
                List.of("requestId", "dramaId", "items"));
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

    public void validate(McpRecordType type, Map<String, Object> data, boolean references, boolean create) {
        if (type == null || data == null || (!create && data.isEmpty())) throw new BizException(400, "必须提供记录类型与业务 data，更新补丁不能为空");
        if (create && type == McpRecordType.PROJECT && (!(data.get("aspectRatio") instanceof String ratio) || ratio.isBlank()))
            throw new BizException(400, "ASPECT_RATIO_REQUIRED: 创建短剧前请用户选择系统已有画幅；明确交给 AI 决定时优先16:9，并提交 aspectRatio");
        if (type == McpRecordType.SHOT) {
            validateMedia(data.get("refImages"), true);
            validateMedia(data.get("refAudios"), false);
        }
        if (!validator.validate(data(type, references, create), data).valid()) throw new BizException(400, "业务字段不符合 Schema：检查必填字段、媒体 URL 和来源 ID；运行状态、模型及 Skill 等内部字段不能写入，ID 必须为字符串");
    }
    private static void validateMedia(Object value, boolean image) {
        if (!(value instanceof List<?> items)) return;
        for (int index = 0; index < items.size(); index++) {
            String path = (image ? "refImages" : "refAudios") + "[" + index + "]";
            if (!(items.get(index) instanceof Map<?, ?> item)) throw new BizException(400, path + " 必须为对象");
            Set<String> allowed = image ? IMAGE_REFERENCE_FIELDS : AUDIO_REFERENCE_FIELDS;
            if (!allowed.containsAll(item.keySet())) throw new BizException(400, path + " 包含未知字段；图片使用 imageUrl/sourceType/sourceId，声音使用 audioUrl/sourceType");
            if (!(item.get("sourceType") instanceof String source)) throw new BizException(400, path + ".sourceType 必填");
            String urlField = image ? "imageUrl" : "audioUrl";
            if (!(item.get(urlField) instanceof String url) || url.isBlank()) throw new BizException(400, path + "." + urlField + " 必填");
            if (image && !"UPLOAD".equals(source) && item.get("sourceId") == null)
                throw new BizException(400, path + ".sourceId 必填；造型图传造型 ID，人物图传人物 ID");
            if (image && "UPLOAD".equals(source) && item.get("sourceId") != null)
                throw new BizException(400, path + ".sourceId 在 UPLOAD 时必须省略或为 null");
            if ((image && Set.of("CHARACTER_LOOK", "CHARACTER_REFERENCE").contains(source) || !image && "TTS".equals(source)) && item.get("characterId") == null)
                throw new BizException(400, path + ".characterId 必填");
        }
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
