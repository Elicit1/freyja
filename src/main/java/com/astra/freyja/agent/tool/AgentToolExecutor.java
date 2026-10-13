package com.astra.freyja.agent.tool;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.entity.AgentRun;
import com.astra.freyja.entity.AgentStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class AgentToolExecutor {
    private final AgentToolRegistry registry;
    private final AgentStore store;
    private final AgentJson json;

    public ToolResult execute(AgentRun run, AgentStep original, String owner, int epoch) {
        Snapshot snapshot = store.snapshot(run);
        AgentTool<?> tool;
        try { tool = registry.require(original.getToolName(), snapshot); }
        catch (RuntimeException unknown) {
            return store.commitTool(run.getId(), owner, epoch, original.getId(), () -> ToolResult.error("UNKNOWN_TOOL", "工具未注册"), 0);
        }
        if (!snapshot.toolVersions().containsKey(tool.name()) || snapshot.toolVersions().get(tool.name()) != tool.version())
            return store.commitTool(run.getId(), owner, epoch, original.getId(), () -> ToolResult.error("TOOL_VERSION_UNAVAILABLE", "工具未授权或版本不可用"), 0);
        var session = store.session(run.getSessionId());
        if (tool.projectRequired() && session.getDramaId() == null)
            return store.commitTool(run.getId(), owner, epoch, original.getId(), () -> ToolResult.error("NO_PROJECT_SCOPE", "请创建绑定项目的会话"), 0);
        if ("CONFIG_TEST".equals(run.getRunType()) && tool.effect() == Effect.WRITE)
            return store.commitTool(run.getId(), owner, epoch, original.getId(), () -> ToolResult.error("READ_ONLY_TEST", "测试禁止写入业务数据"), 0);
        ToolCall call = json.read(original.getInputJson(), ToolCall.class);
        Object input;
        try { input = json.read(call.arguments(), tool.inputType()); }
        catch (RuntimeException invalid) {
            return store.commitTool(run.getId(), owner, epoch, original.getId(), () -> ToolResult.error("INVALID_ARGUMENT", "工具参数不符合 Schema"), 0);
        }
        if (input == null)
            return store.commitTool(run.getId(), owner, epoch, original.getId(), () -> ToolResult.error("INVALID_ARGUMENT", "工具参数不能为 null"), 0);
        boolean recover = "RUNNING".equals(original.getStatus()) || "WAITING_EXTERNAL".equals(original.getStatus())
                || (original.getAttempts() != null && original.getAttempts() > 0 && tool.recoveryMode() == RecoveryMode.RECONCILE);
        WaitHandle wait = original.getWaitHandleJson() == null ? null : json.read(original.getWaitHandleJson(), WaitHandle.class);
        if (wait != null && System.currentTimeMillis() >= wait.deadlineEpochMillis())
            return store.commitTool(run.getId(), owner, epoch, original.getId(), () -> ToolResult.error("EXTERNAL_TIMEOUT", "等待外部任务超时"), 0);
        AgentStep started = store.beginTool(run.getId(), owner, epoch, original.getId());
        if ("SUCCEEDED".equals(started.getStatus())) return json.read(started.getResultJson(), ToolResult.class);
        var context = new ToolContext(run.getSessionId(), run.getId(), original.getId(), session.getDramaId(),
                run.getObjectiveMessageId(), original.getIdempotencyKey(), snapshot);
        Supplier<ToolResult> operation = () -> invoke(tool, input, context, recover, wait);
        if (tool.recoveryMode() == RecoveryMode.TRANSACTIONAL) {
            try { return store.commitTool(run.getId(), owner, epoch, original.getId(), operation, 0); }
            catch (com.astra.freyja.common.BizException invalid) {
                // Business writes have rolled back. Commit only the validation feedback in a new fenced transaction.
                return store.commitTool(run.getId(), owner, epoch, original.getId(), () -> ToolResult.error("BUSINESS_VALIDATION", invalid.getMessage()), 0);
            }
        }
        long start = System.nanoTime();
        // External failures remain recoverable; no uncertain write is retried here.
        ToolResult result = operation.get();
        return store.commitTool(run.getId(), owner, epoch, original.getId(), () -> result, (System.nanoTime() - start) / 1000000);
    }
    @SuppressWarnings("unchecked")
    private <I> ToolResult invoke(AgentTool<I> tool, Object input, ToolContext context, boolean recovery, WaitHandle wait) {
        if (recovery && (wait != null || tool.recoveryMode() == RecoveryMode.RECONCILE))
            return tool.reconcile((I) input, context, wait);
        return tool.execute((I) input, context);
    }
}
