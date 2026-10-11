package com.astra.freyja.agent.context;

import com.astra.freyja.agent.model.AgentProtocol.Snapshot;
import com.astra.freyja.entity.AgentSession;

public interface ContextContributor {
    boolean supports(String agentCode);
    String contribute(AgentSession session, Snapshot snapshot);
}
