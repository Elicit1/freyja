package com.astra.freyja.mcp;

import java.util.List;
import java.util.Map;

/** Each tool fixes its business object; callers never pass a record-type discriminator. */
public final class McpRecordInputs {
    private McpRecordInputs() {}
    public record Get(String dramaId, String recordId) {}
    public record Create(String requestId, String dramaId, Map<String, Object> data) {}
    public record Update(String requestId, String dramaId, String recordId, String expectedFingerprint, Map<String, Object> data) {}
    public record Delete(String requestId, String dramaId, String recordId, String expectedFingerprint) {}
    public record UpdateItem(String recordId, String expectedFingerprint, Map<String, Object> data) {}
    public record BatchUpdate(String requestId, String dramaId, List<UpdateItem> items) {}
}
