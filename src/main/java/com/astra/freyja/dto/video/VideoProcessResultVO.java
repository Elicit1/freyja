package com.astra.freyja.dto.video;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 视频后处理任务视图对象。
 */
@Data
public class VideoProcessResultVO {

    /** 实体 ID (雪花 ID 字符串) */
    private String id;

    /** 业务任务唯一标识 (如 VP_1710000000) */
    private String taskId;

    /** 操作类型: VIDEO_UPSCALE / FRAME_INTERPOLATION */
    private String operation;

    /** 来源类型: DIRECT_URL / SHOT_CURRENT / SHOT_VIDEO_TAKE */
    private String sourceType;

    /** 源视频 URL (快照) */
    private String sourceVideoUrl;

    /** 来源短剧 ID */
    private String sourceDramaId;

    /** 来源剧集 ID */
    private String sourceEpisodeId;

    /** 来源场次 ID */
    private String sourceSceneId;

    /** 来源分镜 ID */
    private String sourceShotId;

    /** 来源指定 Take ID */
    private String sourceVideoTakeId;

    /** 模型中心模型 ID */
    private String modelId;

    /** 来源分镜序号 */
    private Integer sourceShotNo;

    /** 来源分镜标识名称 (如 S01-01) */
    private String sourceShotName;

    /** 来源短剧标题 */
    private String sourceDramaTitle;

    /** 来源剧集标题 */
    private String sourceEpisodeTitle;

    /** 来源场次名称 */
    private String sourceSceneName;

    /** 输出视频 MinIO 访问 URL */
    private String outputVideoUrl;

    /** 视频封面/抽帧图 MinIO 访问 URL */
    private String coverImageUrl;

    /** AI 提供商 ID (雪花 ID 字符串) */
    private String providerId;

    /** AI 提供商名称 */
    private String providerName;

    /** 模型代码 */
    private String modelCode;

    /** 任务状态: QUEUED/PROCESSING/SUCCESS/FAILED/CANCELLED */
    private String status;

    /** 进度百分比 (0-100) */
    private Integer progress;

    /** 当前执行节点描述 */
    private String currentNode;

    /** 输入视频帧率 (FPS) */
    private Integer sourceFps;

    /** 目标视频帧率 (FPS) */
    private Integer targetFps;

    /** 超分倍率 */
    private Integer scale;

    /** 补帧倍率 */
    private Integer multiplier;

    /** 质量压缩系数 CRF */
    private Integer crf;

    /** 是否保留音频 */
    private Boolean preserveAudio;

    /** 补帧显存清理帧数 */
    private Integer clearCacheFrames;

    /** 宽度 */
    private Integer width;

    /** 高度 */
    private Integer height;

    /** 视频时长 (秒) */
    private BigDecimal duration;

    /** 执行总耗时 (毫秒) */
    private Long costMs;

    /** 异常简要信息 */
    private String errorMessage;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 关联的渲染中心任务 ID */
    private String renderTaskId;

    /** 是否配置了处理完成自动录入分镜 Take */
    private Boolean autoSaveToShot;

    /** 自动录入时是否设为分镜当前视频 */
    private Boolean setAsCurrent;

    /** 已保存的目标 Take ID (若已录入) */
    private String savedTakeId;

    /** 已保存的目标 Take 编号 (如 Take #3) */
    private Integer savedTakeNo;

    /** 该 Take 当前是否作为分镜的主视频 */
    private Boolean isCurrentTake;
}
