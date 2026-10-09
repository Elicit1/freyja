package com.astra.freyja.director.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.director.model.DirectorPlanInput;
import com.astra.freyja.entity.DramaShot;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class DirectorPlanStateService {
    private final DramaShotMapper mapper;
    private final DirectorPlanValidator validator;
    private final ObjectMapper json = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.astra.freyja.service.DramaEpisodeService episodeService;

    public String encode(DirectorPlan plan) {
        try { return json.writeValueAsString(plan); }
        catch (Exception e) { throw new BizException(400, "导演计划无法序列化"); }
    }
    public DirectorPlan decode(String value) {
        try { return json.readValue(value, DirectorPlan.class); }
        catch (Exception e) { throw new BizException(400, "已保存导演计划无效，请重新编辑或恢复自动规划"); }
    }
    private DramaShot shot(Long id) {
        DramaShot s = mapper.selectById(id);
        if (s == null) throw new BizException(404, "分镜不存在");
        return s;
    }
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public DirectorPlanEditDTO save(Long id, DirectorPlanEditDTO dto) {
        DramaShot s = shot(id);
        String mode = dto.getDirectorPlanMode();
        if (!"AUTO".equals(mode) && !"MANUAL".equals(mode)) throw new BizException(400, "导演计划模式无效");
        if (dto.getDirectorPlanRevision() == null) throw new BizException(400, "缺少导演计划版本，请重新打开分镜");
        if ("MANUAL".equals(mode)) validator.validateManual(dto.getDirectorPlan(),
                dto.getDuration() == null ? s.getDuration() : BigDecimal.valueOf(dto.getDuration()),
                dto.getShotType() == null ? s.getShotType() : dto.getShotType(),
                dto.getCameraMovement() == null ? s.getCameraMovement() : dto.getCameraMovement(),
                dto.getShotTypeLocked() == null ? Boolean.TRUE.equals(s.getShotTypeLocked()) : dto.getShotTypeLocked(),
                dto.getCameraMovementLocked() == null ? Boolean.TRUE.equals(s.getCameraMovementLocked()) : dto.getCameraMovementLocked());
        if ("MANUAL".equals(mode) && dto.getDuration() != null && dto.getDuration() > 15)
            throw new BizException(400, "分镜时长最长不得超过 15 秒");
        String value = "MANUAL".equals(mode) ? encode(dto.getDirectorPlan()) : null;
        // Editing a plan must not acknowledge changes to the shot's inputs.
        String inputHash = s.getDirectorPlanInputHash();
        if (inputHash == null && s.getDirectorPlanJson() == null && "MANUAL".equals(mode))
            inputHash = DirectorPlanInput.fromShot(s).fingerprint();
        int next = dto.getDirectorPlanRevision() + 1;
        UpdateWrapper<DramaShot> update = new UpdateWrapper<DramaShot>().eq("id", id)
                .eq("director_plan_revision", dto.getDirectorPlanRevision())
                .set("director_plan_mode", mode).set("director_plan_json", value).set("director_plan_revision", next)
                .set("director_plan_input_hash", inputHash);
        // Keep the persisted plan and its physical context valid when the editor
        // saves changes from an otherwise unsaved shot form.
        if ("MANUAL".equals(mode)) {
            if (dto.getDuration() != null) update.set("duration", BigDecimal.valueOf(dto.getDuration()));
            if (dto.getShotType() != null) update.set("shot_type", dto.getShotType());
            if (dto.getCameraMovement() != null) update.set("camera_movement", dto.getCameraMovement());
            if (dto.getShotTypeLocked() != null) update.set("shot_type_locked", dto.getShotTypeLocked());
            if (dto.getCameraMovementLocked() != null) update.set("camera_movement_locked", dto.getCameraMovementLocked());
        }
        if (mapper.update(null, update) != 1) throw new BizException(409, "导演计划已被修改，请重新载入后编辑");
        if ("MANUAL".equals(mode) && dto.getDuration() != null && s.getEpisodeId() != null && episodeService != null)
            episodeService.recalculateEpisodeDuration(s.getEpisodeId());
        dto.setDirectorPlanRevision(next);
        dto.setDirectorPlanInputHash(inputHash);
        if ("AUTO".equals(mode)) dto.setDirectorPlan(null);
        return dto;
    }
    /** Resolve and copy once before creating a task. Later edits never change its input. */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void freeze(ShotPromptDeriveDTO dto) {
        if (dto.isDirectorPlanFrozen()) return;
        DramaShot s = dto.getShotId() == null ? null : shot(dto.getShotId());
        if (s != null && dto.getDuration() == null && s.getDuration() != null) dto.setDuration(s.getDuration().doubleValue());
        String mode = dto.getDirectorPlanMode();
        if (mode == null) mode = dto.getDirectorPlan() != null ? "MANUAL" : s == null ? "AUTO" : s.getDirectorPlanMode();
        if (!"AUTO".equals(mode) && !"MANUAL".equals(mode)) throw new BizException(400, "导演计划模式无效");
        int revision = s == null ? 0 : s.getDirectorPlanRevision();
        if (dto.getDirectorPlanRevision() != null && dto.getDirectorPlanRevision() != revision)
            throw new BizException(409, "导演计划已变更，请重新打开后提交");
        dto.setDirectorPlanOriginalMode(s == null ? "AUTO" : s.getDirectorPlanMode());
        if (s != null) {
            dto.setDirectorPlanSourceFields(sourceFields(s));
            dto.setDirectorPlanSourceHash(DirectorPlanInput.fromShot(s).fingerprint());
        }
        DirectorPlanInput input = dto.getDirectorPlanInput() == null ? DirectorPlanInput.fromRequest(dto) : dto.getDirectorPlanInput().normalized();
        if (Boolean.TRUE.equals(dto.getReplanOnInputChange())) {
            if (dto.getDirectorPlanInput() == null) throw new BizException(400, "缺少当前镜头输入快照");
            DirectorPlanGenerationCheckVO check = inspect(s, input, dto);
            if (check.overwriteRequired() && !Boolean.TRUE.equals(dto.getDirectorPlanOverwriteConfirmed()))
                throw new BizException(409, "镜头内容与导演计划均已修改，请确认覆盖导演计划后重新提交");
            if (check.inputsChanged()) { dto.setDirectorPlanReplanning(true); mode = "AUTO"; }
            input.applyTo(dto); // Includes explicit clears; never hydrate old text over this snapshot.
        }
        dto.setDirectorPlanInput(input);
        if ("AUTO".equals(mode) && s != null && "MANUAL".equals(s.getDirectorPlanMode()) && !Boolean.TRUE.equals(dto.getDirectorPlanReplanning()))
            throw new BizException(409, "请先显式恢复 AI 自动规划");
        DirectorPlan plan = dto.getDirectorPlan();
        if ("MANUAL".equals(mode)) {
            if (plan == null && s != null) plan = decode(s.getDirectorPlanJson());
            validator.validateManual(plan, dto.getDuration() == null && s != null ? s.getDuration() : dto.getDuration() == null ? null : BigDecimal.valueOf(dto.getDuration()),
                    dto.getShotType(), dto.getCameraMovement(), Boolean.TRUE.equals(dto.getShotTypeLocked()), Boolean.TRUE.equals(dto.getCameraMovementLocked()));
            plan = decode(encode(plan));
            if (s != null && (!"MANUAL".equals(s.getDirectorPlanMode()) || !encode(plan).equals(s.getDirectorPlanJson()))) {
                if (dto.getDirectorPlanRevision() == null) throw new BizException(400, "提交手工计划必须提供当前版本");
                DirectorPlanEditDTO edit = new DirectorPlanEditDTO();
                edit.setDirectorPlanMode(mode); edit.setDirectorPlanRevision(revision); edit.setDirectorPlan(plan);
                edit.setDuration(dto.getDuration()); edit.setShotType(dto.getShotType()); edit.setCameraMovement(dto.getCameraMovement());
                edit.setShotTypeLocked(dto.getShotTypeLocked()); edit.setCameraMovementLocked(dto.getCameraMovementLocked());
                revision = save(dto.getShotId(), edit).getDirectorPlanRevision();
            }
        } else plan = null;
        dto.setDirectorPlan(plan); dto.setDirectorPlanMode(mode); dto.setDirectorPlanRevision(revision); dto.setDirectorPlanFrozen(true);
    }
    public boolean completeAuto(ShotPromptDeriveDTO dto, DirectorPlan plan) {
        if (dto.getShotId() == null || plan == null || !"AUTO".equals(dto.getDirectorPlanMode())) return false;
        UpdateWrapper<DramaShot> update = new UpdateWrapper<DramaShot>().eq("id", dto.getShotId())
                .eq("director_plan_mode", dto.getDirectorPlanOriginalMode() == null ? "AUTO" : dto.getDirectorPlanOriginalMode())
                .eq("director_plan_revision", dto.getDirectorPlanRevision());
        if (dto.getDirectorPlanSourceFields() != null) dto.getDirectorPlanSourceFields().forEach((column, value) -> {
            if (value == null) update.isNull(column);
            else if (value instanceof String) update.apply("BINARY " + column + " = {0}", value);
            else update.eq(column, value);
        });
        return mapper.update(null, update.set("director_plan_json", encode(plan)).set("director_plan_mode", "AUTO")
                .set("director_plan_input_hash", (dto.getDirectorPlanInput() == null ? DirectorPlanInput.fromRequest(dto) : dto.getDirectorPlanInput()).fingerprint())
                .set("director_plan_revision", dto.getDirectorPlanRevision() + 1)) == 1;
    }

    public boolean manualSnapshotCurrent(ShotPromptDeriveDTO dto) {
        DramaShot current = shot(dto.getShotId());
        return "MANUAL".equals(current.getDirectorPlanMode())
                && java.util.Objects.equals(dto.getDirectorPlanRevision(), current.getDirectorPlanRevision())
                && java.util.Objects.equals(dto.getDirectorPlanSourceHash(), DirectorPlanInput.fromShot(current).fingerprint());
    }

    /** Read-only preflight. Hashing stays on the server; opening a dialog never persists changes. */
    public DirectorPlanGenerationCheckVO inspect(ShotPromptDeriveDTO dto) {
        if (dto == null || dto.getDirectorPlanInput() == null) throw new BizException(400, "缺少当前镜头输入");
        DramaShot s = dto.getShotId() == null ? null : shot(dto.getShotId());
        return inspect(s, dto.getDirectorPlanInput().normalized(), dto);
    }

    private DirectorPlanGenerationCheckVO inspect(DramaShot s, DirectorPlanInput input, ShotPromptDeriveDTO dto) {
        String currentHash = input.fingerprint();
        String baselineHash = s == null ? null : s.getDirectorPlanInputHash();
        boolean existingPlan = s != null && s.getDirectorPlanJson() != null && !s.getDirectorPlanJson().isBlank();
        // Legacy plans have no trustworthy input basis. Rebuild once, with manual overwrite confirmation.
        boolean changed = baselineHash == null ? existingPlan : !baselineHash.equals(currentHash);
        boolean manual = "MANUAL".equals(dto.getDirectorPlanMode()) || dto.getDirectorPlan() != null
                || (s != null && "MANUAL".equals(s.getDirectorPlanMode()));
        return new DirectorPlanGenerationCheckVO(changed, changed && manual, currentHash, s == null ? 0 : s.getDirectorPlanRevision());
    }

    private java.util.Map<String, Object> sourceFields(DramaShot shot) {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("shot_no", shot.getShotNo()); fields.put("shot_name", shot.getShotName());
        fields.put("duration", shot.getDuration());
        fields.put("shot_type_locked", shot.getShotTypeLocked()); fields.put("camera_movement_locked", shot.getCameraMovementLocked());
        if (Boolean.TRUE.equals(shot.getShotTypeLocked())) fields.put("shot_type", shot.getShotType());
        if (Boolean.TRUE.equals(shot.getCameraMovementLocked())) fields.put("camera_movement", shot.getCameraMovement());
        fields.put("script_content", shot.getScriptContent()); fields.put("action_description", shot.getActionDescription());
        fields.put("dialogue", shot.getDialogue()); fields.put("dialogue_speaker", shot.getDialogueSpeaker());
        fields.put("voiceover", shot.getVoiceover()); fields.put("sound_effect", shot.getSoundEffect());
        return java.util.Collections.unmodifiableMap(fields);
    }
}
