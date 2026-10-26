package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import java.util.*;

/** Structured, public business failure details shared by text and structured MCP results. */
public class McpBusinessException extends BizException {
    private final Map<String, Object> details;

    public McpBusinessException(int code, String message, Map<String, Object> details) {
        super(code, message);
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static Map<String, Object> error(BizException error) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (error instanceof McpBusinessException structured) result.putAll(structured.details);
        result.put("code", error.getCode());
        result.put("message", error.getMessage());
        return result;
    }

    public static McpBusinessException batch(int index, String recordId, BizException error) {
        Map<String, Object> details = error(error);
        details.put("itemIndex", index);
        if (recordId != null) details.put("recordId", recordId);
        details.put("rolledBack", true);
        return new McpBusinessException(error.getCode(), "BATCH_ROLLED_BACK: items[" + index + "]: " + error.getMessage(), details);
    }

    public static McpBusinessException locked(List<String> fields) {
        return new McpBusinessException(409, "CREATOR_LOCKED: 请先在工作台解除锁定: " + String.join(", ", fields),
                Map.of("reason", "CREATOR_LOCKED", "lockedField", fields.getFirst(), "lockedFields", fields,
                        "action", "请先在工作台解除对应字段的创作者锁定，再读取最新指纹后重试"));
    }
}
