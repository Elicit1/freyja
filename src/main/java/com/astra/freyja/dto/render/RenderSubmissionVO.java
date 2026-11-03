package com.astra.freyja.dto.render;

import com.astra.freyja.entity.enums.RenderSubmissionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 渲染提交任务状态与结果视图对象 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenderSubmissionVO implements Serializable {

    /** 业务任务 UUID */
    private String taskId;

    /** 提供商任务标识 */
    private String promptId;

    /** 关联项目 ID */
    private Long projectId;

    /** 关联分镜 ID */
    private Long shotId;

    /** 任务状态 (PENDING/RUNNING/SUCCESS/FAILED/CANCELED) */
    private RenderSubmissionStatus status;

    /** 任务状态说明 */
    private String statusDesc;

    /** 渲染进度百分比 (0 - 100) */
    private Integer progress;

    /** 当前正在执行的节点信息 */
    private String currentNode;

    /** 产物 MinIO 存储访问 URL */
    private String outputUrl;

    /** 产物视频末帧图 MinIO 访问 URL (供组内下一镜首帧续接) */
    private String lastFrameUrl;

    /** 产物文件名 */
    private String outputFilename;

    /** 错误信息 (若失败) */
    private String errorMessage;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 耗时 (毫秒) */
    private Long executionTimeMs;
}
