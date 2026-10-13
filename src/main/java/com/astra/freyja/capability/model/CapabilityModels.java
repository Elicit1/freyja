package com.astra.freyja.capability.model;

import com.astra.freyja.agent.model.AgentProtocol;
import java.util.*;

/** Published contracts are data; executable handlers remain registered Java capabilities. */
public final class CapabilityModels {
    private CapabilityModels() {}
    public enum Consumer { MCP, INTERNAL_AGENT }
    public enum ContextMode { EXPLICIT, SESSION_PROJECT, UNBOUND_PROJECT, AGENT_RUNTIME }
    public record Binding(String id, String capabilityId, String versionId, String handlerKey,
                          Consumer consumer, String consumerCode, String name, String description,
                          Map<String, Object> schema, boolean enabled, boolean required,
                          ContextMode contextMode, boolean projectRequired,
                          AgentProtocol.Effect effect, AgentProtocol.RecoveryMode recoveryMode) {}
    public record Release(String id, long revision, List<Binding> bindings, String instructions, String rules) {}
    public record AgentSnapshot(String releaseId, List<Binding> bindings) {}
    public record Edit(Long revision, List<Binding> bindings, String instructions, String rules) {}
    public record Publish(Long revision, String note) {}
    public record Rollback(Long revision, String releaseId) {}
    public record State(Long revision, String releaseId) {}
}
