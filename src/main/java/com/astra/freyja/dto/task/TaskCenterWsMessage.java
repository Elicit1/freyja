package com.astra.freyja.dto.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 任务中心 WebSocket 事件广播模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCenterWsMessage implements Serializable {

    /** 事件类型: INITIAL_STATE, TASK_UPDATED, PONG */
    private String event;

    /** 时间戳 (毫秒) */
    private Long timestamp;

    /** 当前在线或活跃总数 */
    private Integer activeCount;

    /** 关联的任务明细数据 (TaskCenterItemVO 或 List<TaskCenterItemVO>) */
    private Object data;
}
