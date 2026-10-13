package com.astra.freyja.agent.runtime;

import com.astra.freyja.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentExecutionScheduler {
    private final AgentStore store;
    private final AgentRuntime runtime;
    private final ExecutorService agentExecutionExecutor;
    private final SysConfigService systemConfig;
    private final String owner = UUID.randomUUID().toString();
    private final Map<Long, Running> active = new ConcurrentHashMap<>();
    private record Running(int epoch, String code, Future<?> future) {}
    private volatile boolean stopping;
    private boolean schemaWarning;

    @Scheduled(fixedDelayString = "${freyja.agent.scan-delay-ms:2000}", initialDelayString = "${freyja.agent.scan-initial-delay-ms:2000}")
    public synchronized void scan() {
        if (stopping) return;
        try {
            active.entrySet().removeIf(entry -> entry.getValue().future().isDone());
            for (var entry : active.entrySet()) {
                if (!store.heartbeat(entry.getKey(), owner, entry.getValue().epoch())) entry.getValue().future().cancel(true);
            }
            int cap = Math.clamp(systemConfig.getConfigInt("agent.runtime.maxConcurrentRuns", 4), 1, 4);
            if (active.size() >= cap) return;
            for (var candidate : store.candidates()) {
                if (active.size() >= cap) break;
                if (active.containsKey(candidate.getId())) continue;
                var snapshot = store.snapshot(candidate);
                long sameProfile = active.values().stream().filter(r -> r.code().equals(snapshot.agentCode())).count();
                if (sameProfile >= snapshot.config().getRuntime().getMaxConcurrentRuns()) continue;
                var claimed = store.claim(candidate.getId(), owner);
                if (claimed == null) continue;
                Future<?> future = agentExecutionExecutor.submit(() -> runtime.execute(claimed.getId(), owner, claimed.getLeaseEpoch()));
                active.put(claimed.getId(), new Running(claimed.getLeaseEpoch(), snapshot.agentCode(), future));
            }
            schemaWarning = false;
        } catch (RuntimeException error) {
            if (!schemaWarning) log.warn("Agent 调度暂不可用，请确认 agent-foundation.sql 已执行: {}", error.getMessage());
            schemaWarning = true;
        }
    }
    public void interrupt(Long runId) {
        Running running = active.get(runId);
        if (running != null) running.future().cancel(true);
    }
    @PreDestroy public synchronized void stop() {
        stopping = true;
        active.values().forEach(r -> r.future().cancel(true));
    }
}
