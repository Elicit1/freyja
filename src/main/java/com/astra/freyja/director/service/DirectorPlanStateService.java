package com.astra.freyja.director.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.director.model.DirectorPlan;
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
        int next = dto.getDirectorPlanRevision() + 1;
        UpdateWrapper<DramaShot> update = new UpdateWrapper<DramaShot>().eq("id", id)
                .eq("director_plan_revision", dto.getDirectorPlanRevision())
                .set("director_plan_mode", mode).set("director_plan_json", value).set("director_plan_revision", next);
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
        if ("AUTO".equals(mode) && s != null && "MANUAL".equals(s.getDirectorPlanMode()))
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
        return mapper.update(null, new UpdateWrapper<DramaShot>().eq("id", dto.getShotId())
                .eq("director_plan_mode", "AUTO").eq("director_plan_revision", dto.getDirectorPlanRevision())
                .set("director_plan_json", encode(plan)).set("director_plan_revision", dto.getDirectorPlanRevision() + 1)) == 1;
    }
}
