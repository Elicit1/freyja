package com.astra.freyja.dto.video;

import lombok.Data;

/**
 * 视频后处理任务提交请求 DTO (超分/补帧)。
 */
@Data
public class VideoProcessSubmitDTO {

    /** 操作类型: VIDEO_UPSCALE(视频超分) / FRAME_INTERPOLATION(视频补帧) */
    private String operation;

    /** 来源类型: DIRECT_URL / SHOT_CURRENT / SHOT_VIDEO_TAKE (默认 DIRECT_URL) */
    private String sourceType;

    /** 源视频 URL (MinIO 或网络源地址，DIRECT_URL 必填，分镜来源时忽略) */
    private String sourceVideoUrl;

    /** SHOT_CURRENT / SHOT_VIDEO_TAKE 的来源分镜 ID */
    private Long sourceShotId;

    /** SHOT_VIDEO_TAKE 的指定 Take ID */
    private Long sourceVideoTakeId;

    /** AI 提供商 ID */
    private Long providerId;

    /** 模型中心 AI 模型 ID (新调用必填) */
    private Long modelId;

    /** 模型标识代码 (保留兼容旧调用) */
    private String modelCode;

    /** 输入源视频帧率 (FPS，如 24) */
    private Integer sourceFps;

    /** 目标输出帧率 (FPS，如 24 或 48) */
    private Integer targetFps;

    /** 超分放大倍率 (如 2) */
    private Integer scale;

    /** 补帧倍率 (如 2) */
    private Integer multiplier;

    /** 质量压缩系数 CRF (如超分默认 16，补帧默认 19) */
    private Integer crf;

    /** 是否保留原始音频 (默认 true) */
    private Boolean preserveAudio = true;

    /** 显存清理间隔帧数 (RIFE 优化) */
    private Integer clearCacheFrames = 100;

    /** 处理成功后是否自动保存为分镜历史 Take (仅分镜来源时生效，默认 true) */
    private Boolean autoSaveToShot = true;

    /** 自动保存为分镜历史 Take 时，是否设为分镜当前主视频 (默认 true) */
    private Boolean setAsCurrent = true;

    /** 补帧批处理大小 (batch_size，可选，若不传则遵循模型配置或底层工作流模板默认值) */
    private Integer batchSize;
}
