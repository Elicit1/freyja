package com.astra.freyja.agent.production;

import com.astra.freyja.agent.event.AgentEventPublisher;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.script.WorkerRetryDTO;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.astra.freyja.util.AfterCommitTask;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.util.*;

@Service @RequiredArgsConstructor
public class ProductionTaskRecovery {
    public record SegmentRetry(String segmentId, String instruction) {}
    private final ProductionJobs jobs;
    private final ScriptDecomposeService decomposition;
    private final AiTaskService tasks;
    private final AiTaskMapper taskMapper;
    private final ShotProductionPlanMapper plans;
    private final AgentRunMapper runs;
    private final AgentEventPublisher events;
    private final AgentJson json;

    @PostConstruct void listen() {
        events.addListener(event -> {
            if (event.runId() != null && Set.of("RUN_CANCELLED", "RUN_FAILED").contains(event.type()))
                // These events already arrive after commit; registering another afterCommit callback here would never run it.
                Thread.ofVirtual().start(() -> cancelChildren(event.runId()));
        });
    }
    public void cancelChildren(Long runId) {
        var job = jobs.find(runId);
        if (job == null) return;
        Set<Long> ids = new LinkedHashSet<>(); if (job.getDecompositionTaskId() != null) ids.add(job.getDecompositionTaskId());
        if (job.getDecompositionTaskId() != null) taskMapper.selectList(new LambdaQueryWrapper<AiTask>().eq(AiTask::getParentTaskId, job.getDecompositionTaskId())).stream().map(AiTask::getId).forEach(ids::add);
        plans.selectList(new LambdaQueryWrapper<ShotProductionPlan>().eq(ShotProductionPlan::getRunId, runId)).stream().map(ShotProductionPlan::getPromptTaskId).filter(Objects::nonNull).forEach(ids::add);
        // Workers may still live only in Redis. Include their descendants before stopping the old run.
        var all = tasks.listTasks(null);
        boolean added;
        do {
            added = false;
            for (var task : all) if (task.getParentTaskId() != null && ids.contains(task.getParentTaskId())) added |= ids.add(task.getId());
        } while (added);
        for (Long id : ids) {
            var task = tasks.getTaskById(id);
            if (task == null || !Set.of("PENDING", "RUNNING", "RETRYING").contains(task.getStatus())) continue;
            tasks.markCancelled(id, "PENDING".equals(task.getStatus()));
            AiTaskExecutionRegistry.interrupt(id);
            var stopped = tasks.getTaskById(id);
            if (stopped == null) continue;
            // Preserve the terminal status of the SQL recovery checkpoint after the Redis draft expires.
            taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiTask>()
                    .eq(AiTask::getId, id).in(AiTask::getStatus, "PENDING", "RUNNING", "RETRYING")
                    .set(AiTask::getStatus, stopped.getStatus()).set(AiTask::getFinishedAt, stopped.getFinishedAt())
                    .set(AiTask::getErrorMessage, stopped.getErrorMessage()));
        }
    }
    public ToolResult retry(SegmentRetry input, ToolContext ctx, WaitHandle handle) {
        var job = jobs.require(ctx, true);
        if (job.getDecompositionTaskId() == null || !jobs.targets(job).isEmpty()) throw new BizException("仅未入库的拆解可重试段落");
        var preview = tasks.getDecomposePreview(job.getDecompositionTaskId());
        if (preview == null || preview.getSegmentResults() == null) throw new BizException("没有可重试的分段结果");
        boolean failed = preview.getSegmentResults().stream().anyMatch(s -> Objects.equals(s.getSegmentId(), input.segmentId()) && "FAILED".equals(s.getStatus()));
        if (!failed) return ToolResult.success(Map.of("segmentId", input.segmentId(), "status", "ALREADY_SUCCEEDED"));
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getTaskType, "WORKER_RETRY").eq(AiTask::getTargetId, "agent-step:" + ctx.stepId()));
        if (task == null) {
            task = new AiTask(); task.setDramaId(job.getDramaId() == null ? 0L : job.getDramaId()); task.setTaskType("WORKER_RETRY");
            task.setTargetId("agent-step:" + ctx.stepId()); task.setParentTaskId(job.getDecompositionTaskId()); task.setStatus("PENDING");
            task.setModelCode(ctx.snapshot().config().getModel().getModelCode()); task.setInputPayload(json.write(input)); task.setRetryCount(0); taskMapper.insert(task);
            launch(task.getId(), job.getDecompositionTaskId(), input, ctx);
        } else if (handle != null && Set.of("PENDING", "RUNNING").contains(task.getStatus()) && !AiTaskExecutionRegistry.isActive(task.getId())) launch(task.getId(), job.getDecompositionTaskId(), input, ctx);
        if ("FAILED".equals(task.getStatus())) return ToolResult.error("WORKER_RETRY_FAILED", Objects.toString(task.getErrorMessage(), "分段重试失败"));
        if ("SUCCESS".equals(task.getStatus())) return ToolResult.success(Map.of("segmentId", input.segmentId(), "status", "SUCCESS"));
        return new ToolResult("DEFERRED", Map.of("eventTaskId", String.valueOf(job.getDecompositionTaskId())), null, null,
                ProductionJobs.waitFor(ctx, "production-worker-retry", task.getId(), handle));
    }
    private void launch(Long id, Long parent, SegmentRetry input, ToolContext ctx) {
        AfterCommitTask.start(() -> {
            var run = runs.selectById(ctx.runId());
            if (run == null || Boolean.TRUE.equals(run.getCancelRequested()) || RunStatus.valueOf(run.getStatus()).terminal()) { tasks.markCancelled(id, true); return; }
            try {
                tasks.markRunning(id);
                var dto = new WorkerRetryDTO(); dto.setSegmentId(input.segmentId()); dto.setCustomInstructions(input.instruction());
                dto.setProviderIdOverride(ctx.snapshot().config().getModel().getProviderId()); dto.setModelCodeOverride(ctx.snapshot().config().getModel().getModelCode());
                var result = decomposition.retryWorker(parent, dto);
                if (tasks.isCancelled(id) || tasks.isCancelled(parent)) return;
                tasks.markSuccess(id, json.write(Map.of("status", result.getStatus(), "segmentId", input.segmentId())), null);
            } catch (Exception e) { tasks.markFailed(id, e.getMessage()); }
        });
    }
}
