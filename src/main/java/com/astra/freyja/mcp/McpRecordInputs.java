package com.astra.freyja.mcp;

import org.springframework.ai.tool.annotation.ToolParam;
import java.util.List;
import java.util.Map;

/** Each tool fixes its business object; callers never pass a record-type discriminator. */
public final class McpRecordInputs {
    private McpRecordInputs() {}
    public record Get(@ToolParam(required = false) String dramaId, String recordId,
                      @ToolParam(required = false) List<String> fields) {
        public Get(String dramaId, String recordId) { this(dramaId, recordId, null); }
    }
    public record Create(String requestId, String dramaId, Map<String, Object> data) {}
    public record Update(String requestId, @ToolParam(required = false) String dramaId, String recordId, String expectedFingerprint, Map<String, Object> data,
                         @ToolParam(required = false) List<String> fields) {
        public Update(String requestId, String dramaId, String recordId, String expectedFingerprint, Map<String, Object> data) {
            this(requestId, dramaId, recordId, expectedFingerprint, data, null);
        }
    }
    public record Delete(String requestId, String dramaId, String recordId, String expectedFingerprint) {}
    public record UpdateItem(String recordId, String expectedFingerprint, Map<String, Object> data) {}
    public record BatchUpdate(String requestId, @ToolParam(required = false) String dramaId, List<UpdateItem> items,
                              @ToolParam(required = false) List<String> fields) {
        public BatchUpdate(String requestId, String dramaId, List<UpdateItem> items) { this(requestId, dramaId, items, null); }
    }
}
