package com.astra.freyja.dto.comfy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 提交 ComfyUI 渲染任务请求参数 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComfyRenderSubmitDTO implements Serializable {

    /** AI 提供商 ID (指向 provider_type 为 COMFYUI 或包含 baseUrl 的提供商) */
    private Long providerId;

    /** 预设工作流模板 ID 或标识 (可选) */
    private String workflowTemplateId;

    /** 完整 ComfyUI API 格式工作流 JSON 字符串 (若未传 workflowTemplateId 则必传) */
    private String workflowJson;

    /** 画面正向提示词 (用于替换 PROMPT_INPUT / text) */
    private String prompt;

    /** 负向提示词 (用于替换 NEGATIVE_PROMPT / text) */
    private String negativePrompt;

    /** 视频动态运镜提示词 (I2V 专用, 用于替换 VIDEO_PROMPT_INPUT / text) */
    private String videoPrompt;

    /** 首帧图 (ComfyUI input 文件名或 MinIO URL, 用于替换 FIRST_FRAME_INPUT / IMAGE_INPUT) */
    private String firstFrameImage;

    /** 末帧图 (可选, 用于替换 LAST_FRAME_INPUT) */
    private String lastFrameImage;

    /** 随机种子 (-1 或不传时自动生成时间戳/随机数) */
    private Long seed;

    /** 采样步数 (可选，用于覆盖 KSAMPLER steps) */
    private Integer steps;

    /** 参考图文件名 (ComfyUI input 目录下) 或公网/MinIO URL */
    private String referenceImage;

    /** 归属项目 ID */
    private Long projectId;

    /** 归属分镜 ID */
    private Long shotId;

    /** 资产生图目标类型 (如 CHARACTER / SCENE)，资产任务专用 */
    private String assetTargetType;

    /** 资产生图目标 ID，资产任务专用 */
    private Long assetTargetId;

    /** 资产图片槽位，资产任务专用 */
    private String assetSlot;

    /**
     * 自定义替换映射表：
     * Key 支持以下格式：
     * 1. 节点 Title 别名: 如 "PROMPT_INPUT.text", "MY_SAMPLER.seed"
     * 2. 节点 ID.字段名: 如 "3.seed", "6.text"
     * 3. 别名: 如 "prompt", "seed", "steps"
     */
    private Map<String, Object> customParams;
}
