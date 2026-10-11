package com.astra.freyja.agent.event;

import com.astra.freyja.agent.model.AgentProtocol.Event;
import com.astra.freyja.event.TaskStatusChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AgentEventPublisher {
    private final ApplicationEventPublisher applicationEvents;
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    public AgentEventPublisher(ApplicationEventPublisher applicationEvents) { this.applicationEvents = applicationEvents; }
    public void addListener(Consumer<Event> listener) { listeners.add(listener); }
    public void publishAfterCommit(Event event, boolean testRun) {
        Runnable publish = () -> {
            for (Consumer<Event> listener : listeners) {
                try { listener.accept(event); } catch (RuntimeException e) { log.warn("Agent 实时事件发送失败，持久化回放仍可用", e); }
            }
            if (!testRun && event.runId() != null) applicationEvents.publishEvent(new TaskStatusChangedEvent("AGENT_RUN", String.valueOf(event.runId()), event.type()));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                public void afterCommit() { publish.run(); }
            });
        } else publish.run();
    }
}
