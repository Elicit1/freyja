package com.astra.freyja.dto.comfy;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * ComfyUI POST /prompt 接口返回结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComfyPromptResponse implements Serializable {

    /** 任务 Prompt ID (UUID) */
    @JsonProperty("prompt_id")
    private String promptId;

    /** 队列编号 */
    @JsonProperty("number")
    private Integer number;

    /** 节点参数校验错误信息 (若存在) */
    @JsonProperty("node_errors")
    private Map<String, Object> nodeErrors;

    /** 错误信息 */
    @JsonProperty("error")
    private Object error;
}
