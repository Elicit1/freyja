package com.astra.freyja.dto.agent;

import lombok.Data;

public final class AgentRequests {
    private AgentRequests() {}
    @Data public static class CreateSession {
        private String agentCode = "core-assistant";
        private Long dramaId;
        private String title;
    }
    @Data public static class SendMessage {
        private String clientRequestId;
        private String intent = "START_RUN";
        private String content;
        private Long expectedRunId;
        private Long providerId;
        private String modelCode;
        private Long questionStepId;
        private java.util.List<com.astra.freyja.agent.model.UserQuestions.Answer> answers;
    }
    @Data public static class TestRun {
        private AgentConfigDTO config;
        private String content = "你好，请介绍你能帮助我完成哪些工作。";
        private String clientRequestId;
        private Long dramaId;
    }
    public record Submission(Long sessionId, Long messageId, Long runId) {}
    public record ValidationIssue(String field, String code, String message) {}
    public record ValidationResult(boolean valid, java.util.List<ValidationIssue> errors,
                                   java.util.List<ValidationIssue> warnings) {}
}
