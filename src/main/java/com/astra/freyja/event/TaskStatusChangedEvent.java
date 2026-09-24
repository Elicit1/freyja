package com.astra.freyja.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 全局任务状态变更事件（支持 AI 流水线任务与渲染后处理任务）。
 * 用于解耦各业务模块与任务中心 WebSocket 实时广播。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusChangedEvent implements Serializable {

    /** 任务源类型：AI_TASK / RENDER_TASK */
    private String sourceType;

    /** 任务 ID */
    private String taskId;

    /** 变更后的状态，如 PENDING, RUNNING, RENDERING, SUCCESS, FAILED, RETRYING, CANCELLED */
    private String status;
}
