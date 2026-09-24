package com.astra.freyja.dto.drama;

import lombok.Data;

/**
 * 单分镜触发 ComfyUI 渲染请求参数 DTO。
 */
@Data
public class DramaShotRenderRequestDTO {

    /** 指定 AI 提供商 ID (为空则使用系统默认) */
    private Long providerId;

    /** 指定工作流模板 ID (如 SDXL_TXT2IMG / WAN21_I2V) */
    private String workflowTemplateId;

    /** 自定义完整 ComfyUI API 格式工作流 JSON (若未指定模板则必传) */
    private String workflowJson;

    /** 自定义随机种子 (为空则随机) */
    private Long seed;

    /** 业务渲染任务唯一标识 (用于网关关联与精准取消) */
    private String taskId;

    /** 画面/视频渲染画幅尺寸 (如 960x544, 1280x768, 720x1280) */
    private String size;
}
