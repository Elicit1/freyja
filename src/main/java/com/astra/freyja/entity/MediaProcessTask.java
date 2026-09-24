package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 视频后处理任务持久化实体 (视频超分、补帧等)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("media_process_task")
public class MediaProcessTask extends BaseEntity {

    /** 业务任务唯一标识 (如 VP_1710000000) */
    private String taskId;

    /** 操作类型: VIDEO_UPSCALE(视频超分) / FRAME_INTERPOLATION(视频补帧) */
    private String operation;

    /** 源视频类型: DIRECT_URL/SHOT_CURRENT/SHOT_VIDEO_TAKE */
    private String sourceType;

    /** 源视频 URL (MinIO 或网络源地址，不可变快照) */
    private String sourceVideoUrl;

    /** 源视频所属短剧ID */
    private Long sourceDramaId;

    /** 源视频所属剧集ID */
    private Long sourceEpisodeId;

    /** 源视频所属场次ID */
    private Long sourceSceneId;

    /** 源视频所属分镜ID */
    private Long sourceShotId;

    /** 指定来源视频Take ID，SHOT_VIDEO_TAKE时使用 */
    private Long sourceVideoTakeId;

    /** 模型中心AI模型ID */
    private Long modelId;

    /** 输出视频 MinIO 访问 URL */
    private String outputVideoUrl;

    /** 视频封面/末帧抽帧 MinIO 访问 URL */
    private String coverImageUrl;

    /** AI 提供商 ID */
    private Long providerId;

    /** AI 提供商名称 */
    private String providerName;

    /** 模型代码 (如 realesrgan-x2-video / rife49-video-48fps) */
    private String modelCode;

    /** 任务状态: QUEUED(排队中)/PROCESSING(处理中)/SUCCESS(成功)/FAILED(失败)/CANCELLED(已取消) */
    private String status;

    /** 进度百分比 (0-100) */
    private Integer progress;

    /** 当前执行节点/阶段描述 */
    private String currentNode;

    /** 输入视频帧率 (FPS) */
    private Integer sourceFps;

    /** 目标视频帧率 (FPS) */
    private Integer targetFps;

    /** 超分放大倍率 (如 2) */
    private Integer scale;

    /** 补帧倍率 (如 2) */
    private Integer multiplier;

    /** 输出画质质量压缩系数 (CRF) */
    private Integer crf;

    /** 是否保留原始音频 (1-是 0-否) */
    private Integer preserveAudio;

    /** 补帧显存清理帧数 */
    private Integer clearCacheFrames;

    /** 输出视频宽度 */
    private Integer width;

    /** 输出视频高度 */
    private Integer height;

    /** 视频时长 (秒) */
    private BigDecimal duration;

    /** 执行总耗时 (毫秒) */
    private Long costMs;

    /** 异常简要信息 */
    private String errorMessage;

    /** 异常堆栈明细 */
    private String errorDetail;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 关联的渲染中心任务 ID */
    private Long renderTaskId;
}
