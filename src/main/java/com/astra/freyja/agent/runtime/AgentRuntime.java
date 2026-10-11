package com.astra.freyja.agent.runtime;

import com.astra.freyja.agent.context.AgentContextBuilder;
import com.astra.freyja.agent.gateway.AgentModelGateway;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.profile.AgentProfileRegistry;
import com.astra.freyja.agent.tool.AgentToolExecutor;
import com.astra.freyja.agent.tool.AgentToolRegistry;
import com.astra.freyja.common.BizException;
import com.astra.freyja.entity.AgentStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class AgentRuntime {
    private final AgentStore store;
    private final AgentModelGateway gateway;
    private final AgentContextBuilder context;
    private final AgentToolExecutor tools;
    private final AgentToolRegistry registry;
    private final AgentProfileRegistry profiles;
    private final AgentJson json;

    public void execute(Long id, String owner, int epoch) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var run = store.run(id);
                if (!"RUNNING".equals(run.getStatus()) || run.getLeaseEpoch() != epoch || !owner.equals(run.getLeaseOwner())) return;
                Snapshot snapshot = store.snapshot(run);
                var budget = snapshot.config().getRuntime();
                if (run.getExecutionMillis() >= budget.getExecutionTimeoutSeconds() * 1000L) throw new BizException("Agent 执行时限已用尽");
                var steps = store.steps(id);
                var pending = steps.stream().filter(s -> "TOOL".equals(s.getStepType())
                        && List.of("PENDING", "RUNNING", "WAITING_EXTERNAL").contains(s.getStatus())).findFirst();
                if (pending.isPresent()) {
                    if (run.getToolCalls() >= budget.getMaxToolCalls()) throw new BizException("工具调用预算已用尽");
                    ToolResult result = tools.execute(run, pending.get(), owner, epoch);
                    if (!"IMMEDIATE".equals(result.kind())) return;
                    continue;
                }
                var lastModel = steps.stream().filter(s -> "MODEL".equals(s.getStepType()) && "SUCCEEDED".equals(s.getStatus())).reduce((a, b) -> b);
                if (lastModel.isPresent() && Objects.equals(lastModel.get().getConstraintVersion(), run.getConstraintVersion())) {
                    ModelReply reply = json.read(lastModel.get().getResultJson(), ModelReply.class);
                    if (reply.toolCalls().isEmpty()) {
                        var decision = profiles.require(snapshot.agentCode()).checkCompletion(reply, snapshot);
                        if (decision.complete()) { store.complete(id, owner, epoch, "COMPLETED", reply.content()); return; }
                        if (decision.needsUser()) { store.askUser(id, owner, epoch, decision.problem()); return; }
                        store.addCorrection(id, owner, epoch, decision.problem());
                    }
                }
                if (run.getModelTurns() >= budget.getMaxModelTurns()) throw new BizException("模型轮数预算已用尽");
                AgentStep modelStep = store.prepareModel(id, owner, epoch);
                int attempts = modelStep.getAttempts();
                while (true) {
                    long recordedBefore = store.run(id).getExecutionMillis();
                    if (recordedBefore >= budget.getExecutionTimeoutSeconds() * 1000L)
                        throw new BizException("Agent 执行时限已用尽");
                    long start = System.nanoTime();
                    try {
                        var input = context.build(store.run(id), owner, epoch);
                        var session = store.session(run.getSessionId());
                        List<String> allowed = snapshot.config().getTools().getNames().stream()
                                .filter(n -> !registry.require(n).projectRequired() || session.getDramaId() != null).toList();
                        long remaining = budget.getExecutionTimeoutSeconds() * 1000L - store.run(id).getExecutionMillis()
                                - unrecordedElapsed(id, recordedBefore, start);
                        ModelReply reply = gateway.call(snapshot, input, allowed, remaining);
                        store.saveModel(id, owner, epoch, modelStep, reply, unrecordedElapsed(id, recordedBefore, start));
                        break;
                    } catch (AgentStore.LeaseLost lost) { throw lost; }
                    catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
                    catch (RuntimeException error) {
                        attempts++;
                        store.modelAttemptFailed(id, owner, epoch, modelStep.getId(), error.getMessage(), unrecordedElapsed(id, recordedBefore, start));
                        if (attempts > budget.getMaxRetries()) throw error;
                    }
                }
            }
        } catch (AgentStore.LeaseLost | java.util.concurrent.CancellationException ignored) {
            // Cancellation and a replacement lease are already authoritative in MySQL.
        } catch (RuntimeException error) {
            store.fail(id, owner, epoch, error.getMessage() == null ? "Agent 执行失败" : error.getMessage());
        }
    }
    private long unrecordedElapsed(Long id, long recordedBefore, long start) {
        // Context compaction commits its own model duration. Charge that duration exactly once.
        long alreadyRecorded = Math.max(0, store.run(id).getExecutionMillis() - recordedBefore);
        return Math.max(0, (System.nanoTime() - start) / 1000000 - alreadyRecorded);
    }
}
