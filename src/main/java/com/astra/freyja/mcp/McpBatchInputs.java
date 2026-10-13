package com.astra.freyja.mcp;

import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;

public final class McpBatchInputs {
    private McpBatchInputs() {}
    public record RecordRef(McpRecordType type, String recordId) {}
    public record Read(@ToolParam(required = false) String dramaId, List<RecordRef> items) {}
    public record CreateItem(String clientKey, McpRecordType type, Map<String, Object> data) {}
    public record Create(String requestId, @ToolParam(required = false) String dramaId, List<CreateItem> items) {}
    public record DeleteItem(McpRecordType type, String recordId, String expectedFingerprint) {}
    public record Delete(String requestId, String dramaId, List<DeleteItem> items) {}
    public record Operation(String requestId) {}
    public record Clone(String requestId, String dramaId, String shotId, String expectedFingerprint) {}
    public record DefaultLook(String requestId, String dramaId, String lookId, String expectedFingerprint) {}
    public record Reorder(String requestId, String dramaId, String groupId, List<String> shotIds) {}
    public record VideoTakes(String dramaId, String shotId, @ToolParam(required = false) Integer page, @ToolParam(required = false) Integer size) {}
    public record SelectVideo(String requestId, String dramaId, String shotId, String takeId, String expectedFingerprint) {}
}
