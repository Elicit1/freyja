package com.astra.freyja.agent.event;

import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.runtime.AgentStore.MessageView;
import com.astra.freyja.dao.AgentRunMapper;
import com.astra.freyja.entity.AgentStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Bounded, process-local previews. Only the completed model reply is durable. */
@Component
@Slf4j
public class AgentStreamPublisher {
    private final AgentRunMapper runs;
    private final Map<Long, Channel> active = new ConcurrentHashMap<>();
    private final List<Consumer<StreamEvent>> listeners = new CopyOnWriteArrayList<>();
    public AgentStreamPublisher(AgentRunMapper runs, AgentEventPublisher events) {
        this.runs = runs;
        events.addListener(this::invalidate);
    }
    public void addListener(Consumer<StreamEvent> listener) { listeners.add(listener); }
    public Channel open(Long runId, Long sessionId, String owner, int epoch, AgentStep step) {
        var channel = new Channel(runId, sessionId, owner, epoch, step);
        var previous = active.put(runId, channel);
        if (previous != null) previous.close();
        return channel;
    }
    /** Snapshot reads never take a channel lock: subscription replay already holds the socket monitor. */
    public List<StreamEvent> current(Long sessionId) {
        return active.values().stream().filter(c -> sessionId.equals(c.sessionId) && c.valid())
                .map(c -> c.latest).filter(Objects::nonNull).toList();
    }
    private void invalidate(Event event) {
        if (event.runId() == null) return;
        Channel channel = active.get(event.runId());
        if (channel == null) return;
        if (Set.of("RUN_STARTED", "USER_INPUT_SAVED", "MODEL_RETRY", "RUN_COMPLETED", "RUN_CANCELLED", "RUN_FAILED").contains(event.type())) {
            channel.close();
        } else if ("MESSAGE_SAVED".equals(event.type()) && event.payload() instanceof MessageView view
                && "ASSISTANT".equals(view.messageType()) && view.body() instanceof MessageBody body
                && channel.stepId.equals(body.modelStepId())) channel.close();
    }
    private void publish(StreamEvent event) {
        for (var listener : listeners) {
            try { listener.accept(event); }
            catch (RuntimeException e) { log.warn("Agent 流式预览发送失败", e); }
        }
    }
    public final class Channel implements AutoCloseable, Consumer<String> {
        private final Long runId, sessionId, stepId;
        private final String owner, streamId = UUID.randomUUID().toString();
        private final int epoch, constraintVersion;
        private final StringBuilder text = new StringBuilder();
        private volatile StreamEvent latest;
        private int sequence;
        private boolean closed;
        private long lastSent;
        private Channel(Long runId, Long sessionId, String owner, int epoch, AgentStep step) {
            this.runId = runId; this.sessionId = sessionId; this.owner = owner; this.epoch = epoch;
            this.stepId = step.getId(); this.constraintVersion = step.getConstraintVersion();
        }
        private boolean valid() {
            var run = runs.selectById(runId);
            return run != null && "RUNNING".equals(run.getStatus()) && !Boolean.TRUE.equals(run.getCancelRequested())
                    && owner.equals(run.getLeaseOwner()) && run.getLeaseEpoch() == epoch
                    && Objects.equals(run.getConstraintVersion(), constraintVersion)
                    && run.getLeaseUntil() != null && run.getLeaseUntil().isAfter(LocalDateTime.now());
        }
        @Override public synchronized void accept(String delta) {
            if (closed || delta == null || delta.isEmpty()) return;
            // Max configured output is 32768 tokens. Refuse unbounded provider output.
            if (text.length() + delta.length() > 262144) throw new IllegalStateException("模型流式输出超出长度限制");
            text.append(delta);
            long now = System.nanoTime();
            if (lastSent != 0 && now - lastSent < 100_000_000L) return;
            if (!valid()) { close(); return; }
            lastSent = now;
            latest = new StreamEvent(sessionId, runId, stepId, streamId, ++sequence, "SNAPSHOT", text.toString());
            publish(latest);
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true; latest = null;
            active.remove(runId, this);
            publish(new StreamEvent(sessionId, runId, stepId, streamId, ++sequence, "CLOSED", ""));
        }
    }
}
