package com.astra.freyja.dto.comfy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * ComfyUI POST /prompt 接口请求体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComfyPromptRequest implements Serializable {

    /** 客户端唯一标识 (用于 WebSocket 关联) */
    @JsonProperty("client_id")
    private String clientId;

    /** ComfyUI 工作流 Prompt 节点结构树 */
    @JsonProperty("prompt")
    private Object prompt;

    /** 附加元数据 (可选) */
    @JsonProperty("extra_data")
    private Map<String, Object> extraData;
}
