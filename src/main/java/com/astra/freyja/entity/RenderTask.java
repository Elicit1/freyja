package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 渲染任务与历史归档持久化实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("render_task")
public class RenderTask extends BaseEntity {

    /** 业务任务唯一标识 (如 RENDER_1710000000) */
    private String taskId;

    /** 任务类型: SHOT_FRAME(关键帧), SHOT_VIDEO(视频), GROUP_SERIAL(历史数据，仅用于兼容读取), ASSET_IMAGE(资产生图) */
    private String taskType;

    /** 任务展示名称 (如: [第1集 S1 镜头名] 首帧渲染) */
    private String taskName;

    /** 关联短剧 ID */
    private Long dramaId;

    /** 关联剧集 ID */
    private Long episodeId;

    /** 关联场次 ID */
    private Long sceneId;

    /** 关联分镜 ID */
    private Long shotId;

    /** 关联镜头组 ID */
    private Long shotGroupId;

    /** 资产类型: CHARACTER/SCENE/PROP */
    private String assetType;

    /** 资产目标 ID */
    private Long assetId;

    /** 资产图片槽位 */
    private String assetSlot;

    /** AI 提供商 ID */
    private Long providerId;

    /** AI 提供商名称 */
    private String providerName;

    /** AI 模型代码 */
    private String modelCode;

    /** 任务状态: QUEUED(排队中)/RENDERING(渲染中)/SUCCESS(成功)/FAILED(失败)/CANCELLED(已取消) */
    private String status;

    /** 当前进度百分比 (0-100) */
    private Integer progress;

    /** 当前渲染节点/阶段描述 */
    private String currentNode;

    /** 渲染正向提示词快照 */
    private String prompt;

    /** 渲染负向提示词快照 */
    private String negativePrompt;

    /** 参考图列表 (JSON Array) */
    private String referenceImages;

    /** 渲染产物 MinIO 访问 URL */
    private String outputUrl;

    /** 抽取尾帧 MinIO URL */
    private String lastFrameUrl;

    /** 异常简要信息 */
    private String errorMessage;

    /** 错误堆栈 */
    private String errorDetail;

    /** 任务提交时间 */
    private LocalDateTime submitTime;

    /** 任务开始执行时间 */
    private LocalDateTime startTime;

    /** 任务结束时间 */
    private LocalDateTime finishTime;

    /** 总执行耗时(毫秒) */
    private Long costMs;
}
