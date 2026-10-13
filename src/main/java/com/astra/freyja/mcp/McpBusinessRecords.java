package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.astra.freyja.director.service.DirectorPlanStateService;
import com.astra.freyja.director.model.DirectorPlan;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;

import java.util.*;

/** Only CRUD methods are used. No Agent, model, Prompt or Skill operation is delegated. */
@Service
@RequiredArgsConstructor
public class McpBusinessRecords {
    private final DramaMapper dramas;
    private final DramaEpisodeMapper episodes;
    private final DramaSceneMapper scenes;
    private final DramaShotGroupMapper groups;
    private final DramaShotMapper shots;
    private final DramaService dramaService;
    private final DramaEpisodeService episodeService;
    private final DramaSceneService sceneService;
    private final DramaShotGroupService groupService;
    private final DramaShotService shotService;
    private final ResCharacterService characterService;
    private final ResCharacterLookService lookService;
    private final ResSceneService resourceSceneService;
    private final ResPropService propService;
    private final ResKeyframeService keyframeService;
    private final McpAssets assets;
    private final McpScope scope;
    private final McpBusinessView views;
    private final McpBusinessSchemas schemas;
    private final DirectorPlanStateService directorPlans;
    private final ObjectMapper binding = new ObjectMapper().findAndRegisterModules();

    public BaseEntity require(McpPrincipal caller, McpRecordType type, String recordId, String dramaId, boolean write) {
        if (type == null) throw new BizException(400, "必须指定业务记录类型");
        Long id = McpScope.id(recordId);
        if (type.assetType() != null) {
            var entry = assets.resolve(caller, type.assetType(), recordId, dramaId, write);
            return assets.raw(entry.type(), entry.id());
        }
        Long project = scope.project(caller, type == McpRecordType.PROJECT ? recordId : dramaId);
        if (type == McpRecordType.PROJECT && dramaId != null) scope.belongs(id, McpScope.id(dramaId));
        return switch (type) {
            case PROJECT -> dramas.selectById(project); case EPISODE -> scope.episode(id, project);
            case SCENE -> scope.scene(id, project); case SHOT_GROUP -> scope.group(id, project); case SHOT -> scope.shot(id, project);
            default -> throw new BizException(400, "不支持的业务类型");
        };
    }

    public BaseEntity lock(McpRecordType type, Long id) {
        BaseEntity value = mapper(type).selectOne(new QueryWrapper<BaseEntity>().eq("id", id).last("FOR UPDATE"));
        if (value == null) throw new BizException(404, "业务记录不存在"); return value;
    }

    public BaseEntity lockScoped(McpPrincipal caller, McpRecordType type, String id, String project) {
        require(caller, type, id, project, true);
        BaseEntity current = lock(type, McpScope.id(id));
        // Recheck ownership after acquiring the lock in case another editor moved the record.
        require(caller, type, id, project, true);
        return current;
    }

    public void resetCloneMedia(Long id) {
        // Takes and cached frames belong to the original shot, never to its copy.
        UpdateWrapper<DramaShot> clear = new UpdateWrapper<DramaShot>().eq("id", id)
                .set("current_video_take_id", null).set("last_frame_url", null)
                .set("last_frame_source_video_url", null).set("last_frame_source_take_id", null)
                .set("first_frame_source_type", null).set("first_frame_source_shot_id", null)
                .set("first_frame_source_video_url", null).set("first_frame_source_video_take_id", null);
        DramaShot clone = shots.selectById(id);
        if (clone.getResKeyframeId() != null) {
            var keyframe = (ResKeyframe) assets.raw(McpInputs.AssetType.KEYFRAME, clone.getResKeyframeId());
            if (keyframe != null && keyframe.getShotId() != null) clear.set("res_keyframe_id", null);
        }
        shots.update(null, clear);
    }

    public void clearVideoTail(Long id) {
        // Explicit SQL NULL is required: ordinary updateById ignores null fields.
        shots.update(null, new UpdateWrapper<DramaShot>().eq("id", id)
                .set("last_frame_url", null).set("last_frame_source_video_url", null).set("last_frame_source_take_id", null));
    }

    public Map<String, Object> read(McpPrincipal caller, McpRecordType type, String id, String project) {
        return views.project(type.viewKind(), require(caller, type, id, project, false));
    }

    public Long create(McpPrincipal caller, McpRecordType type, Long project, Map<String, Object> data) {
        schemas.validate(type, data, false, true);
        if (type == McpRecordType.PROJECT) {
            if (!caller.createProjects() || project != null) throw new BizException(403, "仅允许在授权新建项目的批次首项创建一个项目");
        } else if (project == null) throw new BizException(400, "必须指定已授权项目或先创建项目");
        Object dto = patch(type, null, data);
        BeanWrapperImpl bean = new BeanWrapperImpl(dto);
        if (bean.isWritableProperty("dramaId")) bean.setPropertyValue("dramaId", project);
        references(caller, type, dto, project, null, data);
        return switch (type) {
            case PROJECT -> dramaService.create((DramaDTO) dto); case EPISODE -> episodeService.create((DramaEpisodeDTO) dto);
            case SCENE -> sceneService.create((DramaSceneDTO) dto); case SHOT_GROUP -> groupService.create((DramaShotGroupDTO) dto);
            case SHOT -> shotService.create((DramaShotDTO) dto); case CHARACTER -> characterService.create((ResCharacterDTO) dto);
            case CHARACTER_LOOK -> lookService.create((ResCharacterLookDTO) dto); case RESOURCE_SCENE -> resourceSceneService.create((ResSceneDTO) dto);
            case PROP -> propService.create((ResPropDTO) dto); case KEYFRAME -> keyframeService.create((ResKeyframeDTO) dto);
        };
    }

    public void update(McpPrincipal caller, McpRecordType type, BaseEntity current, Long project, Map<String, Object> data) {
        schemas.validate(type, data, false, false);
        Object dto = patch(type, current, data);
        references(caller, type, dto, project, current, data);
        switch (type) {
            case PROJECT -> dramaService.update((DramaDTO) dto); case EPISODE -> episodeService.update((DramaEpisodeDTO) dto);
            case SCENE -> sceneService.update((DramaSceneDTO) dto); case SHOT_GROUP -> groupService.update((DramaShotGroupDTO) dto);
            case SHOT -> {
                DramaShotDTO shot = (DramaShotDTO) dto;
                shotService.update(shot);
                if (data.containsKey("directorPlan") || data.containsKey("directorPlanMode")) {
                    DirectorPlanEditDTO edit = new DirectorPlanEditDTO(); edit.setDirectorPlanMode(shot.getDirectorPlanMode());
                    edit.setDirectorPlan(shot.getDirectorPlan()); edit.setDirectorPlanRevision(((DramaShot) current).getDirectorPlanRevision());
                    directorPlans.save(current.getId(), edit);
                }
                updateManualMedia(shot, data);
            }
            case CHARACTER -> characterService.update((ResCharacterDTO) dto);
            case CHARACTER_LOOK -> lookService.update((ResCharacterLookDTO) dto); case RESOURCE_SCENE -> resourceSceneService.update((ResSceneDTO) dto);
            case PROP -> propService.update((ResPropDTO) dto); case KEYFRAME -> keyframeService.update((ResKeyframeDTO) dto);
        }
        UpdateWrapper<BaseEntity> clear = new UpdateWrapper<BaseEntity>().eq("id", current.getId());
        McpBusinessSchemas.CONTENT_COLUMNS.forEach((field, column) -> {
            if (schemas.fields(type).contains(field) && data.containsKey(field) && data.get(field) == null) clear.set(column, null);
        });
        if (clear.getSqlSet() != null) mapper(type).update(null, clear);
    }

    private Object patch(McpRecordType type, BaseEntity current, Map<String, Object> data) {
        try {
            Object dto = schemas.dto(type).getDeclaredConstructor().newInstance();
            // Preserve fields that are intentionally not exposed through MCP.
            if (current != null) BeanUtils.copyProperties(current, dto);
            binding.readerForUpdating(dto).readValue(binding.writeValueAsString(data));
            if (dto instanceof DramaShotDTO shot) {
                if (current instanceof DramaShot existing) {
                    preserveReferenceDetails(shot, existing, data);
                    cameraLock(existing.getShotTypeLocked(), existing.getShotType(), shot.getShotTypeLocked(), shot.getShotType());
                    cameraLock(existing.getCameraMovementLocked(), existing.getCameraMovement(), shot.getCameraMovementLocked(), shot.getCameraMovement());
                }
                configureDirectorPlan(shot, (DramaShot) current, data);
                if (current == null && shot.getPreviewImageUrl() != null && !shot.getPreviewImageUrl().isBlank()) shot.setFirstFrameSourceType("MANUAL_UPLOAD");
            }
            return dto;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "业务字段无法绑定，ID 必须是有效的 64 位整数字符串"); }
    }
    private void preserveReferenceDetails(DramaShotDTO dto, DramaShot existing, Map<String, Object> data) throws Exception {
        if (data.containsKey("characterRefs")) dto.setCharacterRefs(mergeReferences(
                existing.getCharacterRefsJson(), (List<?>) data.get("characterRefs"), "characterId", CharacterShotRefDTO.class));
        if (data.containsKey("propRefs")) dto.setPropRefs(mergeReferences(
                existing.getPropRefsJson(), (List<?>) data.get("propRefs"), "propId", PropShotRefDTO.class));
    }
    private <T> List<T> mergeReferences(String raw, List<?> updates, String identity, Class<T> type) throws Exception {
        Map<String, Map<String, Object>> previous = new HashMap<>();
        if (raw != null && !raw.isBlank()) {
            List<Map<String, Object>> entries = binding.readValue(raw, binding.getTypeFactory().constructCollectionType(List.class,
                    binding.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
            for (var entry : entries) previous.put(String.valueOf(entry.get(identity)), entry);
        }
        List<T> result = new ArrayList<>();
        for (Object update : updates) {
            Map<String, Object> value = binding.convertValue(update, binding.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            Map<String, Object> merged = new LinkedHashMap<>(previous.getOrDefault(String.valueOf(value.get(identity)), Map.of()));
            merged.putAll(value);
            result.add(binding.convertValue(merged, type));
        }
        return result;
    }
    private void configureDirectorPlan(DramaShotDTO dto, DramaShot current, Map<String, Object> data) {
        if (!data.containsKey("directorPlan") && !data.containsKey("directorPlanMode")) return;
        if (data.containsKey("directorPlan") && dto.getDirectorPlan() != null) {
            if ("AUTO".equals(data.get("directorPlanMode"))) throw new BizException(400, "提交导演计划必须使用 MANUAL 模式");
            dto.setDirectorPlanMode("MANUAL");
            if (current == null && dto.getDuration() == null) dto.setDuration(dto.getDirectorPlan().getDuration());
        } else if ("MANUAL".equals(dto.getDirectorPlanMode())) {
            if (data.containsKey("directorPlan") || current == null || current.getDirectorPlanJson() == null)
                throw new BizException(400, "MANUAL 模式必须提供导演计划");
            dto.setDirectorPlan(directorPlans.decode(current.getDirectorPlanJson()));
        } else dto.setDirectorPlan(null);
        if (current != null && current.getDirectorPlanJson() != null) {
            DirectorPlan previous = directorPlans.decode(current.getDirectorPlanJson());
            if (previous.getLockedFields() != null && !previous.getLockedFields().isEmpty()) {
                DirectorPlan next = dto.getDirectorPlan();
                if (next == null || next.getLockedFields() == null || !next.getLockedFields().containsAll(previous.getLockedFields()))
                    throw new BizException(409, "CREATOR_LOCKED: 不能删除或解除导演计划锁定字段");
                Map<?, ?> before = binding.convertValue(previous, Map.class), after = binding.convertValue(next, Map.class);
                for (String field : previous.getLockedFields()) if (!Objects.equals(before.get(field), after.get(field)))
                    throw new BizException(409, "CREATOR_LOCKED: 导演计划字段已锁定: " + field);
            }
        }
    }
    private void updateManualMedia(DramaShotDTO shot, Map<String, Object> data) {
        UpdateWrapper<DramaShot> update = new UpdateWrapper<DramaShot>().eq("id", shot.getId());
        Map.of("previewImageUrl", "preview_image_url", "endFrameImageUrl", "end_frame_image_url", "audioUrl", "audio_url").forEach((field, column) -> {
            if (data.containsKey(field) && "".equals(data.get(field))) update.set(column, null);
        });
        if (data.containsKey("previewImageUrl")) update.set("first_frame_source_type", "".equals(data.get("previewImageUrl")) ? null : "MANUAL_UPLOAD")
                .set("first_frame_source_shot_id", null).set("first_frame_source_video_url", null).set("first_frame_source_video_take_id", null);
        if (update.getSqlSet() != null) shots.update(null, update);
    }
    private void cameraLock(Boolean locked, String oldValue, Boolean newLocked, String newValue) {
        if (Boolean.TRUE.equals(locked) && (!Boolean.TRUE.equals(newLocked) || !Objects.equals(oldValue, newValue)))
            throw new BizException(409, "CREATOR_LOCKED: 请先在工作台解除创作者锁定");
    }

    private void references(McpPrincipal caller, McpRecordType type, Object dto, Long project, BaseEntity old, Map<String, Object> data) {
        if (dto instanceof DramaSceneDTO scene) {
            var episode = scope.episode(scene.getEpisodeId(), project);
            if (old instanceof DramaScene current && !Objects.equals(current.getEpisodeId(), episode.getId())) throw new BizException(400, "不能通过内容更新移动场次到另一剧集");
            asset(caller, McpInputs.AssetType.SCENE, scene.getResSceneId(), project);
        }
        if (dto instanceof DramaShotGroupDTO group) {
            var scene = scope.scene(group.getSceneId(), project);
            if (old instanceof DramaShotGroup current && !Objects.equals(current.getSceneId(), scene.getId())) throw new BizException(400, "不能通过内容更新移动镜头组");
            group.setEpisodeId(scene.getEpisodeId());
        }
        if (dto instanceof DramaShotDTO shot) {
            var scene = scope.scene(shot.getSceneId(), project);
            if (old instanceof DramaShot current && !Objects.equals(current.getSceneId(), scene.getId())) throw new BizException(400, "不能通过内容更新移动分镜场次");
            shot.setEpisodeId(scene.getEpisodeId());
            if (shot.getShotGroupId() != null && !Objects.equals(scope.group(shot.getShotGroupId(), project).getSceneId(), scene.getId())) throw new BizException(400, "镜头组不属于分镜场次");
            if (data.get("resSceneId") != null && data.get("resKeyframeId") != null) throw new BizException(400, "场景资产与关键帧不能同时作为主视觉");
            if (data.get("resSceneId") != null) shot.setResKeyframeId(null);
            if (data.get("resKeyframeId") != null) shot.setResSceneId(null);
            asset(caller, McpInputs.AssetType.SCENE, shot.getResSceneId(), project);
            var keyframe = asset(caller, McpInputs.AssetType.KEYFRAME, shot.getResKeyframeId(), project);
            if (keyframe != null && keyframe.shotId() != null && !Objects.equals(keyframe.shotId(), shot.getId())) throw new BizException(403, "关键帧绑定了其他分镜");
            if (shot.getCharacterRefs() != null) {
                Set<Long> ids = new HashSet<>();
                for (var ref : shot.getCharacterRefs()) {
                    if (ref.getCharacterId() == null || !ids.add(ref.getCharacterId())) throw new BizException(400, "人物引用必须提供唯一 characterId");
                    asset(caller, McpInputs.AssetType.CHARACTER, ref.getCharacterId(), project);
                    var look = asset(caller, McpInputs.AssetType.CHARACTER_LOOK, ref.getLookId(), project);
                    if (look != null && !ref.getCharacterId().equals(look.characterId())) throw new BizException(403, "造型不属于指定人物");
                }
            }
            if (shot.getPropRefs() != null) {
                Set<Long> ids = new HashSet<>();
                for (var ref : shot.getPropRefs()) {
                    if (ref.getPropId() == null || !ids.add(ref.getPropId())) throw new BizException(400, "道具引用必须提供唯一 propId");
                    asset(caller, McpInputs.AssetType.PROP, ref.getPropId(), project);
                }
            }
            mediaReferences(caller, shot, project);
        }
        if (dto instanceof ResCharacterLookDTO look) {
            if (look.getCharacterId() == null) throw new BizException(400, "造型需要 characterId");
            assets.resolve(caller, McpInputs.AssetType.CHARACTER, look.getCharacterId().toString(), project.toString(), true);
            if (old instanceof ResCharacterLook current && !Objects.equals(current.getCharacterId(), look.getCharacterId())) throw new BizException(400, "不能移动造型所属人物");
        }
        if (dto instanceof ResKeyframeDTO keyframe && keyframe.getShotId() != null) scope.shot(keyframe.getShotId(), project);
    }
    private McpAssets.Entry asset(McpPrincipal caller, McpInputs.AssetType type, Long id, Long project) {
        return id == null ? null : assets.resolve(caller, type, id.toString(), project.toString(), false);
    }
    private void mediaReferences(McpPrincipal caller, DramaShotDTO shot, Long project) {
        Set<String> imageIds = new HashSet<>();
        if (shot.getRefImages() != null) for (ShotRefImageDTO image : shot.getRefImages()) {
            if (image == null || image.getSourceType() == null) throw new BizException(400, "参考图需要明确 sourceType");
            image.setId(slotId(image.getId(), imageIds, "参考图"));
            switch (image.getSourceType()) {
                case "SCENE" -> requiredAsset(caller, McpInputs.AssetType.SCENE, image.getSourceId(), project);
                case "PROP" -> requiredAsset(caller, McpInputs.AssetType.PROP, image.getSourceId(), project);
                case "KEYFRAME" -> requiredAsset(caller, McpInputs.AssetType.KEYFRAME, image.getSourceId(), project);
                case "CHARACTER" -> {
                    requiredAsset(caller, McpInputs.AssetType.CHARACTER, image.getSourceId(), project);
                    if (image.getCharacterId() != null && !image.getCharacterId().equals(image.getSourceId())) throw new BizException(403, "人物参考图 characterId 与 sourceId 不一致");
                    if (image.getCharacterId() == null) image.setCharacterId(image.getSourceId());
                }
                case "CHARACTER_REFERENCE" -> {
                    requiredAsset(caller, McpInputs.AssetType.CHARACTER, image.getCharacterId(), project);
                    var look = requiredAsset(caller, McpInputs.AssetType.CHARACTER_LOOK, image.getSourceId(), project);
                    if (!image.getCharacterId().equals(look.characterId()) || (image.getLookId() != null && !image.getLookId().equals(look.id())))
                        throw new BizException(403, "人物参考图不属于指定人物或造型");
                    // sourceId already identifies the look; preserve that explicit selection in downstream manifests.
                    if (image.getLookId() == null) image.setLookId(look.id());
                }
                case "UPLOAD" -> { if (image.getSourceId() != null) throw new BizException(400, "UPLOAD 参考图不能伪造 sourceId"); }
                default -> throw new BizException(400, "参考图 sourceType 无效");
            }
            if (image.getCharacterId() != null) asset(caller, McpInputs.AssetType.CHARACTER, image.getCharacterId(), project);
            if (image.getLookId() != null) {
                var look = asset(caller, McpInputs.AssetType.CHARACTER_LOOK, image.getLookId(), project);
                Long character = image.getCharacterId() != null ? image.getCharacterId() : "CHARACTER".equals(image.getSourceType()) ? image.getSourceId() : null;
                if (!Objects.equals(character, look.characterId())) throw new BizException(403, "参考图造型不属于指定人物");
            }
        }
        Set<String> audioIds = new HashSet<>();
        java.math.BigDecimal totalDuration = java.math.BigDecimal.ZERO;
        if (shot.getRefAudios() != null) for (ShotRefAudioDTO audio : shot.getRefAudios()) {
            audio.setId(slotId(audio.getId(), audioIds, "参考音频"));
            if (audio.getDuration() != null) totalDuration = totalDuration.add(audio.getDuration());
            if ("TTS".equals(audio.getSourceType())) requiredAsset(caller, McpInputs.AssetType.CHARACTER, audio.getCharacterId(), project);
            else if (audio.getCharacterId() != null) asset(caller, McpInputs.AssetType.CHARACTER, audio.getCharacterId(), project);
        }
        if (totalDuration.compareTo(java.math.BigDecimal.valueOf(15)) > 0) throw new BizException(400, "已知参考音频总长不得超过 15 秒");
    }
    private String slotId(String submitted, Set<String> ids, String kind) {
        String id = submitted == null ? "mcp-" + UUID.randomUUID() : submitted;
        if (!ids.add(id)) throw new BizException(400, kind + "槽位 id 必须唯一");
        return id;
    }
    private McpAssets.Entry requiredAsset(McpPrincipal caller, McpInputs.AssetType type, Long id, Long project) {
        if (id == null) throw new BizException(400, "参考媒体缺少关联资产 ID");
        return asset(caller, type, id, project);
    }

    @SuppressWarnings("unchecked") private BaseMapper<BaseEntity> mapper(McpRecordType type) {
        BaseMapper<?> value = switch (type) {
            case PROJECT -> dramas; case EPISODE -> episodes; case SCENE -> scenes; case SHOT_GROUP -> groups; case SHOT -> shots;
            default -> null;
        };
        if (value == null) return assets.businessMapper(type.assetType());
        return (BaseMapper<BaseEntity>) value;
    }
}
