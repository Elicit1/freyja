package com.astra.freyja.dto.drama;

import lombok.Data;

/**
 * 应用分镜 AI 视觉方案请求。
 */
@Data
public class ShotAiVisualPlanApplyDTO {

    private String firstFramePrompt;
    private String negativePrompt;
    private String videoPrompt;
}
