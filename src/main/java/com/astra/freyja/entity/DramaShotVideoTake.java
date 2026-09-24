package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 分镜视频抽卡候选版本实体 drama_shot_video_take。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_shot_video_take")
public class DramaShotVideoTake extends BaseEntity {

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属剧集ID */
    private Long episodeId;

    /** 归属场次ID */
    private Long sceneId;

    /** 归属镜头组ID */
    private Long shotGroupId;

    /** 所属分镜ID */
    private Long shotId;

    /** 该分镜下从1开始递增的候选编号 */
    private Integer takeNo;

    /** 来源渲染任务业务ID */
    private String taskId;

    /** 来源: AI_GENERATED/LEGACY_BACKFILL */
    private String sourceType;

    /** 资产状态: AVAILABLE/UNAVAILABLE/ARCHIVED */
    private String status;

    /** MinIO视频访问URL */
    private String videoUrl;

    /** AI提供商ID快照 */
    private Long providerId;

    /** AI提供商名称快照 */
    private String providerName;

    /** 视频模型代码快照 */
    private String modelCode;

    /** 生成模式快照 */
    private String generationMode;

    /** 随机种子快照 */
    private Long seed;

    /** 生成尺寸快照 */
    private String size;

    /** 目标/实际时长秒数 */
    private BigDecimal duration;

    /** 视频Prompt快照 */
    private String promptSnapshot;

    /** 负向Prompt快照 */
    private String negativePromptSnapshot;

    /** 生成时首帧URL快照 */
    private String firstFrameUrl;

    /** 生成时尾帧URL快照 */
    private String endFrameUrl;

    /** 生成时参考图快照 */
    private String refImagesJson;

    /** 生成时参考音频快照 */
    private String refAudiosJson;

    /** 规范化后的请求参数快照JSON */
    private String requestSnapshotJson;
}
