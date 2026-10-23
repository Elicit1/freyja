package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit business projections. New fields in application entities are never automatically exposed. */
@Component
@RequiredArgsConstructor
public class McpBusinessView {
    public enum Kind { PROJECT, EPISODE, SCENE, SHOT_GROUP, SHOT, CHARACTER, CHARACTER_LOOK, RESOURCE_SCENE, PROP, KEYFRAME, VIDEO_TAKE }
    private static final Map<Kind, Set<String>> FIELDS = Map.ofEntries(
            Map.entry(Kind.PROJECT, fields("id,title,coverUrl,genre,targetEpisodes,aspectRatio,stylePreset,styleTone,synopsis,status,sortOrder,remark,totalEpisodes,totalScenes,totalShots,renderedShots,progressPercentage")),
            Map.entry(Kind.EPISODE, fields("id,dramaId,episodeNo,title,summary,scriptContent,targetDuration,actualDuration,status,sortOrder,remark")),
            Map.entry(Kind.SCENE, fields("id,dramaId,episodeId,sceneNo,name,resSceneId,sceneType,timeOfDay,weatherAtmosphere,locationName,summary,scriptContent,sortOrder,remark")),
            Map.entry(Kind.SHOT_GROUP, fields("id,dramaId,episodeId,sceneId,groupNo,name,purpose,sortOrder,remark")),
            Map.entry(Kind.SHOT, fields("id,dramaId,episodeId,sceneId,shotGroupId,shotNo,shotName,shotType,cameraMovement,shotTypeLocked,cameraMovementLocked,duration,scriptContent,actionDescription,soundEffect,resSceneId,resKeyframeId,stylePreset,previewImageUrl,endFrameImageUrl,videoUrl,currentVideoTakeId,sortOrder,remark,prompt,firstFramePrompt,endFramePrompt,negativePrompt,videoPrompt,customScenePrompt,directorPlanMode,directorPlanRevision,generationMode,seed")),
            Map.entry(Kind.CHARACTER, fields("id,dramaId,name,canonicalName,displayName,gender,ageGroup,roleType,personality,appearanceDesc,referenceImageUrl,voiceDesc,voiceSampleText,voiceSampleUrl,sortOrder,status,firstAppearance,lastAppearance,remark,appearancePrompt,negativePrompt")),
            Map.entry(Kind.CHARACTER_LOOK, fields("id,characterId,lookName,isDefault,lookType,designDesc,referenceImageUrl,imageStatus,sortOrder,status,remark,outfitPrompt,appearancePrompt,negativePrompt")),
            Map.entry(Kind.RESOURCE_SCENE, fields("id,dramaId,name,description,coverUrl,referenceImageUrl,sceneType,timeOfDay,weatherAtmosphere,sortOrder,status,remark,scenePrompt,negativePrompt")),
            Map.entry(Kind.PROP, fields("id,dramaId,name,propType,description,coverUrl,sortOrder,status,remark,propPrompt")),
            Map.entry(Kind.KEYFRAME, fields("id,dramaId,shotId,name,frameType,description,frameUrl,sourceType,aspectRatio,sortOrder,status,remark,prompt,negativePrompt")),
            Map.entry(Kind.VIDEO_TAKE, fields("id,shotId,takeNo,videoUrl,current,size,duration,firstFrameUrl,endFrameUrl,createTime"))
    );
    private final McpJson json;
    private final McpSchemaMetadata metadata;

    @SuppressWarnings("unchecked")
    public Map<String, Object> project(Kind kind, Object entity) {
        if (entity == null) throw new BizException(404, "业务记录不存在");
        Map<String, Object> source = (Map<String, Object>) json.value(entity);
        Map<String, Object> result = new LinkedHashMap<>();
        FIELDS.get(kind).forEach(name -> { if (source.containsKey(name)) result.put(name, source.get(name)); });
        if ("0".equals(result.get("dramaId"))) result.put("dramaId", null);
        if (kind == Kind.SHOT) {
            if (entity instanceof com.astra.freyja.entity.DramaShot shot) {
                result.put("scriptContent", com.astra.freyja.util.UnifiedShotScript.fromShot(shot));
                result.put("legacyScriptPending", com.astra.freyja.util.UnifiedShotScript.hasLegacySpeech(shot));
                result.put("refAudiosConfigured", shot.getRefAudiosJson() != null && !shot.getRefAudiosJson().isBlank());
            }
            result.put("characterRefs", references(source.get("characterRefsJson"), metadata.referenceFields("CHARACTER")));
            result.put("propRefs", references(source.get("propRefsJson"), metadata.referenceFields("PROP")));
            result.put("refImages", references(source.get("refImagesJson"), metadata.referenceFields("IMAGE")));
            result.put("refAudios", references(source.get("refAudiosJson"), metadata.referenceFields("AUDIO")));
            result.put("refVideos", references(source.get("refVideosJson"), metadata.referenceFields("VIDEO")));
            result.put("directorPlan", directorPlan(source.get("directorPlanJson")));
        }
        result.put("editFingerprint", fingerprint(entity));
        return result;
    }

    public String fingerprint(Object entity) {
        try {
            if (entity instanceof com.astra.freyja.entity.DramaShot shot) {
                Map<String, Object> basis = new LinkedHashMap<>((Map<String, Object>) json.value(entity));
                basis.put("scriptContent", com.astra.freyja.util.UnifiedShotScript.fromShot(shot));
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.write(basis).getBytes(StandardCharsets.UTF_8)));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.write(entity).getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e) { throw new IllegalStateException("业务版本指纹生成失败", e); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> references(Object raw, Set<String> allowed) {
        if (!(raw instanceof String text) || text.isBlank()) return List.of();
        try {
            List<Map<String, Object>> values = json.mapper().readValue(text, List.class);
            return values.stream().map(value -> {
                Map<String, Object> result = new LinkedHashMap<>();
                allowed.forEach(key -> {
                    Object id = value.get(key);
                    if (id != null) result.put(key, key.endsWith("Id") && id instanceof Number ? id.toString() : id);
                });
                return result;
            }).toList();
        } catch (Exception e) { throw new BizException(409, "已存储的分镜引用结构无效，请在工作台修复"); }
    }
    @SuppressWarnings("unchecked") private Object directorPlan(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) return null;
        try {
            Map<String, Object> plan = json.mapper().readValue(text, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            fields("schemaVersion,duration,shotSize,cameraAngle,subjectAction,gaze,narrativeIntent,lockedFields").forEach(k -> { if (plan.containsKey(k)) result.put(k, plan.get(k)); });
            if (plan.get("cameraBeats") instanceof List<?> beats) result.put("cameraBeats", beats.stream().map(beat -> {
                Map<String, Object> value = new LinkedHashMap<>();
                if (beat instanceof Map<?, ?> map) fields("startSec,endSec,movement,direction,speed,startCue,stopCue,narrativePurpose")
                        .forEach(k -> { if (map.containsKey(k)) value.put(k, map.get(k)); });
                return value;
            }).toList());
            return result;
        } catch (Exception e) { throw new BizException(409, "已保存导演计划结构无效，请在工作台修复"); }
    }
    private static Set<String> fields(String names) { return Set.of(names.split(",")); }
    public static Kind kind(McpInputs.AssetType type) {
        return switch (type) {
            case CHARACTER -> Kind.CHARACTER; case CHARACTER_LOOK -> Kind.CHARACTER_LOOK;
            case SCENE -> Kind.RESOURCE_SCENE; case PROP -> Kind.PROP; case KEYFRAME -> Kind.KEYFRAME;
        };
    }
}
