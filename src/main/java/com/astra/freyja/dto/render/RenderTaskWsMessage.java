package com.astra.freyja.dto.render;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 渲染任务 WebSocket 事件广播模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenderTaskWsMessage implements Serializable {

    /** 事件类型: INITIAL_STATE, TASK_QUEUED, TASK_PROGRESS, TASK_SUCCESS, TASK_FAILED, TASK_CANCELLED, PONG */
    private String event;

    /** 时间戳 */
    private Long timestamp;

    /** 当前活跃排队与渲染中任务总数 */
    private Integer activeCount;

    /** 关联的任务明细数据 (RenderTaskVO 或 List<RenderTaskVO>) */
    private Object data;
}
