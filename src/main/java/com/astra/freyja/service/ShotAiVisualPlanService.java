package com.astra.freyja.service;

import com.astra.freyja.dto.drama.ShotAiVisualPlanApplyDTO;
import com.astra.freyja.dto.drama.ShotAiVisualPlanRequestDTO;
import com.astra.freyja.dto.drama.ShotAiVisualPlanVO;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import com.astra.freyja.dto.drama.ShotPromptPackageVO;
import com.astra.freyja.dto.drama.ShotPromptParseRequestDTO;
import com.astra.freyja.dto.drama.ShotPromptValidationResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 分镜 AI 首帧与运镜方案服务。
 */
public interface ShotAiVisualPlanService {

    ShotAiVisualPlanVO generate(Long shotId, ShotAiVisualPlanRequestDTO request);

    ShotAiVisualPlanVO apply(Long shotId, ShotAiVisualPlanApplyDTO request);

    /**
     * 根据当前分镜即时编辑信息、剧集剧情大纲与短剧全局风格基调，流式衍生双轨提示词。
     */
    SseEmitter derivePromptStream(ShotPromptDeriveDTO dto);

    /** Create a recoverable task and return its Snowflake ID before inference starts. */
    String startPromptTask(ShotPromptDeriveDTO dto);

    /**
     * 构建并导出完整的分镜任务 Prompt 任务包（零模型开销，可直接复制到外部 AI）。
     */
    ShotPromptPackageVO buildPromptPackage(ShotPromptDeriveDTO dto);

    /**
     * 结合结构化导演决策计划 DirectorPlan 构建并导出完整的分镜任务 Prompt 任务包。
     */
    ShotPromptPackageVO buildPromptPackage(ShotPromptDeriveDTO dto, com.astra.freyja.director.model.DirectorPlan directorPlan);

    /**
     * 解析并统一校验外部 AI 返回的提示词文本或 JSON。
     */
    ShotPromptValidationResult parseAndValidateDerivedPrompt(ShotPromptParseRequestDTO request);
}
