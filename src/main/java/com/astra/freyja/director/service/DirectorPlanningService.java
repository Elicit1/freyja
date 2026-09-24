package com.astra.freyja.director.service;

import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;

/**
 * 导演决策规划服务。
 * 驱动具备 Tool Calling 能力的模型按需加载 Skill 知识，输出结构化 DirectorPlan。
 */
public interface DirectorPlanningService {

    DirectorPlan plan(ShotPromptDeriveDTO dto);
}
