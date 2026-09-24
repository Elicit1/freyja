package com.astra.freyja.director.service;

import com.astra.freyja.director.model.CameraBeat;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.entity.DramaShot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DirectorPlanMergeServiceImpl implements DirectorPlanMergeService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getEffectiveShotType(DramaShot shot, DirectorPlan plan) {
        if (plan != null && StringUtils.isNotBlank(plan.getShotSize())) {
            return plan.getShotSize();
        }
        if (shot != null && Boolean.TRUE.equals(shot.getShotTypeLocked())
                && StringUtils.isNotBlank(shot.getShotType()) && !"AUTO".equalsIgnoreCase(shot.getShotType())) {
            return shot.getShotType();
        }
        return null;
    }

    @Override
    public String getPrimaryCameraMovement(DramaShot shot, DirectorPlan plan) {
        if (plan != null && plan.getCameraBeats() != null && !plan.getCameraBeats().isEmpty()) {
            // 优先返回非 STATIC 的运动
            for (CameraBeat b : plan.getCameraBeats()) {
                if (!"STATIC".equalsIgnoreCase(b.getMovement())) {
                    return b.getMovement();
                }
            }
            return plan.getCameraBeats().get(0).getMovement();
        }
        if (shot != null && Boolean.TRUE.equals(shot.getCameraMovementLocked())
                && StringUtils.isNotBlank(shot.getCameraMovement()) && !"AUTO".equalsIgnoreCase(shot.getCameraMovement())) {
            return shot.getCameraMovement();
        }
        return null;
    }

    @Override
    public void applyPlanToShot(DramaShot shot, DirectorPlan plan) {
        if (shot == null || plan == null) return;

        // 仅将结构化 Plan 存入 director_plan_json，不覆盖创作者约束字段与其显式锁定标记。
        try {
            String json = objectMapper.writeValueAsString(plan);
            shot.setDirectorPlanJson(json);
            log.info("[DirectorPlanMergeService] 成功将 DirectorPlan 写入 shot: shotId={}", shot.getId());
        } catch (Exception e) {
            log.error("[DirectorPlanMergeService] 序列化 DirectorPlan 失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public DirectorPlan parsePlanFromShot(DramaShot shot) {
        if (shot == null || StringUtils.isBlank(shot.getDirectorPlanJson())) {
            return null;
        }
        try {
            return objectMapper.readValue(shot.getDirectorPlanJson(), DirectorPlan.class);
        } catch (Exception e) {
            log.warn("[DirectorPlanMergeService] 解析 shot.directorPlanJson 失败: {}", e.getMessage());
            return null;
        }
    }
}
