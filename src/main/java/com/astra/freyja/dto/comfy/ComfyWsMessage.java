package com.astra.freyja.dto.comfy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * ComfyUI WebSocket 事件消息模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComfyWsMessage implements Serializable {

    /**
     * 事件类型：
     * - status: 系统状态
     * - progress: 采样进度
     * - executing: 正在执行节点
     * - executed: 节点执行完成并输出产物
     * - execution_error: 执行异常
     * - execution_start: 任务开始
     * - execution_cached: 缓存跳过
     */
    @JsonProperty("type")
    private String type;

    /** 事件数据体 (根据 type 不同具有不同属性) */
    @JsonProperty("data")
    private JsonNode data;
}
