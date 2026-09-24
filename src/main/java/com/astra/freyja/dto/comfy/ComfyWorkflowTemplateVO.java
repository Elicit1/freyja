package com.astra.freyja.dto.comfy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 预设 ComfyUI 工作流模板元数据 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComfyWorkflowTemplateVO implements Serializable {

    /** 模板唯一标识 */
    private String templateId;

    /** 模板名称 */
    private String templateName;

    /** 模板类型 (IMAGE/VIDEO) */
    private String templateType;

    /** 适用场景描述 */
    private String description;

    /** 支持的可替换参数列表 (如 ["prompt", "negativePrompt", "seed", "steps", "referenceImage"]) */
    private List<String> supportedParams;

    /** 模板默认工作流 JSON (API 格式) */
    private String defaultWorkflowJson;
}
