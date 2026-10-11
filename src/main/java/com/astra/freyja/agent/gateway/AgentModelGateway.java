package com.astra.freyja.agent.gateway;

import com.astra.freyja.agent.model.AgentProtocol.*;
import org.springframework.ai.chat.messages.Message;
import java.util.List;

public interface AgentModelGateway {
    default ModelReply call(Snapshot snapshot, List<Message> messages, List<String> tools) {
        return call(snapshot, messages, tools, snapshot.config().getRuntime().getModelTimeoutSeconds() * 1000L);
    }
    ModelReply call(Snapshot snapshot, List<Message> messages, List<String> tools, long timeoutMillis);
}
