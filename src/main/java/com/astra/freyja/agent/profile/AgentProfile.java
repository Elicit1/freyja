package com.astra.freyja.agent.profile;

import com.astra.freyja.agent.model.AgentProtocol.CompletionDecision;
import com.astra.freyja.agent.model.AgentProtocol.ModelReply;
import com.astra.freyja.agent.model.AgentProtocol.Snapshot;
import java.util.List;

/** Business profiles register capabilities and a deterministic completion policy. */
public interface AgentProfile {
    String code();
    int version();
    String fixedInstructions();
    List<String> allowedTools();
    default List<String> requiredTools() { return List.of(); }
    default CompletionDecision checkCompletion(ModelReply reply, Snapshot snapshot) {
        return reply.content() == null || reply.content().isBlank()
                ? new CompletionDecision(false, "请给出面向用户的非空结果。", false)
                : CompletionDecision.done();
    }
}
