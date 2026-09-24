package com.astra.freyja.entity;

import com.astra.freyja.entity.enums.ComfyTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ComfyUI 渲染任务运行状态记录实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComfyRenderTask implements Serializable {

    /** 业务任务 UUID */
    private String taskId;

    /** ComfyUI 下发的 Prompt ID (UUID) */
    private String promptId;

    /** 关联的 AI 提供商 ID */
    private Long providerId;

    /** 关联的项目 ID */
    private Long projectId;

    /** 关联的分镜 ID */
    private Long shotId;

    /** 资产生图目标类型，非资产任务为空 */
    private String assetTargetType;

    /** 资产生图目标 ID，非资产任务为空 */
    private Long assetTargetId;

    /** 资产图片槽位，非资产任务为空 */
    private String assetSlot;

    /** 任务状态 */
    private ComfyTaskStatus status;

    /** 渲染进度百分比 (0-100) */
    private Integer progress;

    /** 当前正在执行的节点 ID / 节点类型 */
    private String currentNode;

    /** 正向提示词 */
    private String prompt;

    /** 负向提示词 */
    private String negativePrompt;

    /** 使用的随机种子 */
    private Long seed;

    /** 产物 MinIO 存储持久化访问 URL */
    private String outputUrl;

    /** 产物视频末帧图 MinIO 访问 URL (由归档服务抽帧产生, 供组内下一镜首帧续接) */
    private String lastFrameUrl;

    /** 生成的原始文件名 */
    private String outputFilename;

    /** 错误信息摘要 */
    private String errorMessage;

    /** 错误详细调用栈/跟踪 */
    private String errorDetail;

    /** 任务下发时间 */
    private LocalDateTime createTime;

    /** 任务开始执行时间 */
    private LocalDateTime startTime;

    /** 任务完成/结束时间 */
    private LocalDateTime finishTime;

    /** 执行耗时 (毫秒) */
    private Long executionTimeMs;
}
