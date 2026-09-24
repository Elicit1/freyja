package com.astra.freyja.dto.drama;

import lombok.Data;

import java.util.List;

/**
 * 分镜首帧图生成请求 DTO。
 */
@Data
public class DramaShotFirstFrameDTO {

    /** 生图引擎类型: COMFYUI-本地ComfyUI / API-云端第三方API模型 (默认 COMFYUI) */
    private String engineType;

    /** AI 提供商 ID (可选，为空则使用系统默认) */
    private Long providerId;

    /** 云端生图模型代码 (当 engineType=API 时使用，如 dall-e-3 / black-forest-labs/FLUX.1-schnell 等) */
    private String modelCode;

    /** 首帧文生图工作流模板 ID (当 engineType=COMFYUI 时使用，默认 SDXL_TXT2IMG) */
    private String workflowTemplateId;

    /** 图像尺寸 (如 1024x1024 / 1024x1792 等) */
    private String size;

    /** 随机种子 (可选，为空则系统自动生成) */
    private Long seed;

    /** 自定义生图提示词覆盖 (可选) */
    private String customPrompt;

    /** 自定义负向词 (可选) */
    private String negativePrompt;

    /** 参考图 URL 列表 (可选，用于图生图或多参考图控制) */
    private List<String> referenceImageUrls;

    /** 业务渲染任务唯一标识 (用于网关关联与精准取消) */
    private String taskId;

    /** 关键帧类型: FIRST_FRAME-首帧图 (默认) / END_FRAME-尾帧图 */
    private String frameType;
}
