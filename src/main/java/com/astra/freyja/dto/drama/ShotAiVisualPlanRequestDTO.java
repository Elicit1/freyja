package com.astra.freyja.dto.drama;

import lombok.Data;

import java.util.List;

/**
 * 分镜 AI 视觉方案生成请求。
 */
@Data
public class ShotAiVisualPlanRequestDTO {

    /** AI 提供商 ID，为空时使用默认配置。 */
    private Long providerId;

    /** AI 模型编码。 */
    private String modelCode;

    /** 可选的创作者补充要求。 */
    private String instruction;

    private List<String> requiredSkillNames;

    private List<String> selectedSkillNames;
}
