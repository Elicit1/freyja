package com.astra.freyja.director.service;

import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.entity.DramaShot;

/**
 * 导演决策计划合并与适配服务。
 * 处理 AUTO 状态保护、多 Beat 与下游单值字段适配，保证数据库中的 AUTO 不被覆盖。
 */
public interface DirectorPlanMergeService {

    String getEffectiveShotType(DramaShot shot, DirectorPlan plan);

    String getPrimaryCameraMovement(DramaShot shot, DirectorPlan plan);

    void applyPlanToShot(DramaShot shot, DirectorPlan plan);

    DirectorPlan parsePlanFromShot(DramaShot shot);
}
