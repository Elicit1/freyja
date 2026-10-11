package com.astra.freyja.agent.model;

import com.astra.freyja.dto.agent.AgentConfigDTO;
import java.util.List;
import java.util.Map;

public final class AgentProtocol {
    private AgentProtocol() {}
    public enum RunStatus {
        QUEUED, RUNNING, WAITING_TASK, WAITING_USER, CANCELLING,
        COMPLETED, PARTIAL_SUCCESS, FAILED, CANCELLED;
        public boolean terminal() {
            return this == COMPLETED || this == PARTIAL_SUCCESS || this == FAILED || this == CANCELLED;
        }
    }
    public enum RecoveryMode { REPLAY_SAFE, TRANSACTIONAL, RECONCILE }
    public enum Effect { READ, WRITE }
    public record Snapshot(String agentCode, int configRevision, int profileVersion,
                           AgentConfigDTO config, String fixedInstructions,
                           Map<String, Long> skillVersions, Map<String, Integer> toolVersions,
                           com.astra.freyja.entity.AiModel pinnedModel, String runType) {}
    public record ToolCall(String id, String name, String arguments) {}
    public record ModelReply(String content, List<ToolCall> toolCalls, Map<String, Object> properties,
                             Integer promptTokens, Integer completionTokens, String responseId) {}
    public record MessageBody(String content, List<ToolCall> toolCalls, Map<String, Object> properties,
                              String toolName, Object result, Long modelStepId) {
        public static MessageBody text(String content) {
            return new MessageBody(content, List.of(), Map.of(), null, null, null);
        }
    }
    public record WaitHandle(String adapter, String taskId, long deadlineEpochMillis) {}
    public record ToolResult(String kind, Object data, String errorCode, String message, WaitHandle waitHandle) {
        public static ToolResult success(Object data) { return new ToolResult("IMMEDIATE", data, null, null, null); }
        public static ToolResult error(String code, String message) {
            return new ToolResult("IMMEDIATE", null, code, message, null);
        }
        public static ToolResult deferred(WaitHandle handle) {
            return new ToolResult("DEFERRED", null, null, null, handle);
        }
        public static ToolResult needUser(String question) {
            return new ToolResult("NEED_USER", null, null, question, null);
        }
        public static ToolResult needUser(UserQuestions questions) {
            return new ToolResult("NEED_USER", questions, null, "请确认以下问题后继续", null);
        }
    }
    public record ToolContext(Long sessionId, Long runId, Long stepId, Long dramaId,
                              Long sourceMessageId, String idempotencyKey, Snapshot snapshot) {}
    public record ToolDescriptor(String name, int version, String description, Effect effect,
                                 RecoveryMode recoveryMode, boolean projectRequired, String inputSchema) {}
    public record Event(Long sessionId, Long runId, int seq, String type, Object payload) {}
    /** Transient cumulative preview; sequence is local to streamId, independent of durable message seq. */
    public record StreamEvent(Long sessionId, Long runId, Long modelStepId, String streamId,
                              int sequence, String type, String text) {}
    public record CompletionDecision(boolean complete, String problem, boolean needsUser) {
        public static CompletionDecision done() { return new CompletionDecision(true, null, false); }
    }
}
