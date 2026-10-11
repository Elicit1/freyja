package com.astra.freyja.agent.runtime;

import com.astra.freyja.agent.event.AgentEventPublisher;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.profile.AgentProfileRegistry;
import com.astra.freyja.agent.settings.AgentConfigService;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.agent.AgentConfigDTO;
import com.astra.freyja.dto.agent.AgentRequests.*;
import com.astra.freyja.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Every checkpoint, paired message and state event is committed under a session/run lock. */
@Service
@RequiredArgsConstructor
public class AgentStore {
    private final AgentSessionMapper sessions;
    private final AgentMessageMapper messages;
    private final AgentRunMapper runs;
    private final AgentStepMapper steps;
    private final DramaMapper dramas;
    private final AgentConfigService configs;
    private final AgentProfileRegistry profiles;
    private final AgentJson json;
    private final AgentEventPublisher events;
    private final TransactionTemplate transactions;

    public record MessageView(Long id, Long sessionId, Long runId, int seq, String messageType,
                              String eventType, String toolCallId, Object body, LocalDateTime createTime) {}
    public static class LeaseLost extends RuntimeException {}
    public AgentSession createSession(CreateSession request, String type) {
        return createSession(request, type, null);
    }
    public AgentSession createSession(CreateSession request, String type, String testRequestId) {
        profiles.require(request.getAgentCode());
        if (testRequestId != null) {
            var existing = sessions.selectOne(new LambdaQueryWrapper<AgentSession>().eq(AgentSession::getTestRequestId, testRequestId));
            if (existing != null) return existing;
        }
        if (request.getDramaId() != null && dramas.selectById(request.getDramaId()) == null)
            throw new BizException(404, "项目不存在");
        if (!"CONFIG_TEST".equals(type) && !configs.get(request.getAgentCode()).isEnabled())
            throw new BizException("请先在 Agent 配置页启用助手");
        AgentSession session = new AgentSession();
        session.setAgentCode(request.getAgentCode()); session.setDramaId(request.getDramaId());
        session.setTitle(request.getTitle() == null || request.getTitle().isBlank() ? "新的 Agent 会话" : request.getTitle().trim());
        if (session.getTitle().length() > 200) throw new BizException("会话标题过长");
        session.setSessionType(type); session.setTestRequestId(testRequestId); session.setNextSeq(1); session.setCoveredThroughSeq(0); session.setSummaryVersion(0);
        try { sessions.insert(session); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) {
            if (testRequestId == null) throw duplicate;
            var existing = sessions.selectOne(new LambdaQueryWrapper<AgentSession>().eq(AgentSession::getTestRequestId, testRequestId));
            if (existing == null) throw duplicate;
            return existing;
        }
        return session;
    }
    public List<AgentSession> listSessions() {
        return sessions.selectList(new LambdaQueryWrapper<AgentSession>().eq(AgentSession::getSessionType, "NORMAL")
                .orderByDesc(AgentSession::getUpdateTime).last("LIMIT 100"));
    }
    public AgentSession session(Long id) {
        AgentSession result = sessions.selectById(id);
        if (result == null) throw new BizException(404, "Agent 会话不存在");
        return result;
    }
    public AgentRun run(Long id) {
        AgentRun result = runs.selectById(id);
        if (result == null) throw new BizException(404, "Agent 任务不存在");
        return result;
    }
    public List<AgentRun> activeRuns() {
        return runs.selectList(new LambdaQueryWrapper<AgentRun>().eq(AgentRun::getRunType, "NORMAL")
                .in(AgentRun::getStatus, "QUEUED", "RUNNING", "WAITING_TASK", "WAITING_USER", "CANCELLING")
                .orderByDesc(AgentRun::getCreateTime).last("LIMIT 200"));
    }
    public List<AgentRun> historyRuns(int limit) {
        return runs.selectList(new LambdaQueryWrapper<AgentRun>().eq(AgentRun::getRunType, "NORMAL")
                .in(AgentRun::getStatus, "COMPLETED", "PARTIAL_SUCCESS", "FAILED", "CANCELLED")
                .orderByDesc(AgentRun::getCreateTime).last("LIMIT " + Math.clamp(limit, 1, 200)));
    }
    public List<AgentRun> notifications() {
        return runs.selectList(new LambdaQueryWrapper<AgentRun>().eq(AgentRun::getRunType, "NORMAL")
                .isNotNull(AgentRun::getFinishedAt).isNull(AgentRun::getReadAt)
                .orderByDesc(AgentRun::getFinishedAt).last("LIMIT 100"));
    }
    public List<MessageView> after(Long sessionId, int afterSeq) {
        session(sessionId);
        return rawAfter(sessionId, afterSeq, 500).stream().map(this::view).toList();
    }
    public List<AgentMessage> rawAfter(Long sessionId, int afterSeq, int limit) {
        return messages.selectList(new LambdaQueryWrapper<AgentMessage>().eq(AgentMessage::getSessionId, sessionId)
                .gt(AgentMessage::getSeq, afterSeq).orderByAsc(AgentMessage::getSeq).last("LIMIT " + Math.clamp(limit, 1, 5000)));
    }
    public List<AgentMessage> modelHistory(Long sessionId, int afterSeq) {
        return messages.selectList(new LambdaQueryWrapper<AgentMessage>().eq(AgentMessage::getSessionId, sessionId)
                .gt(AgentMessage::getSeq, afterSeq).ne(AgentMessage::getMessageType, "EVENT")
                .orderByAsc(AgentMessage::getSeq).last("LIMIT 5000"));
    }
    public Snapshot snapshot(AgentRun run) { return json.read(run.getProfileSnapshotJson(), Snapshot.class); }
    public List<AgentStep> steps(Long runId) {
        return steps.selectList(new LambdaQueryWrapper<AgentStep>().eq(AgentStep::getRunId, runId).orderByAsc(AgentStep::getStepOrder));
    }
    public Submission submit(Long sessionId, SendMessage request, AgentConfigDTO testConfig) {
        validateRequest(request);
        return transactions.execute(tx -> {
            AgentSession session = lockSession(sessionId);
            AgentMessage duplicate = messages.selectOne(new LambdaQueryWrapper<AgentMessage>().eq(AgentMessage::getSessionId, sessionId)
                    .eq(AgentMessage::getClientRequestId, request.getClientRequestId()));
            if (duplicate != null) return new Submission(sessionId, duplicate.getId(), duplicate.getRunId());
            if ("START_RUN".equals(request.getIntent())) {
                if (session.getActiveRunId() != null) throw new BizException(409, "ACTIVE_RUN_EXISTS: 当前会话已有活动任务");
                AgentConfigDTO config = testConfig != null ? testConfig : configs.get(session.getAgentCode());
                config = json.copy(config, AgentConfigDTO.class);
                if ((request.getProviderId() == null) != (request.getModelCode() == null))
                    throw new BizException("本次模型覆盖必须同时指定提供商和模型");
                if (request.getProviderId() != null) {
                    config.getModel().setProviderId(request.getProviderId()); config.getModel().setModelCode(request.getModelCode());
                }
                Snapshot snapshot = configs.snapshot(session.getAgentCode(), config, session.getSessionType());
                AgentRun run = new AgentRun();
                run.setId(IdWorker.getId()); run.setSessionId(sessionId); run.setRunType(session.getSessionType());
                run.setObjective(request.getContent()); run.setConfigRevision(snapshot.configRevision()); run.setProfileSnapshotJson(json.write(snapshot));
                run.setStatus("QUEUED"); run.setModelTurns(0); run.setToolCalls(0); run.setNextStepOrder(1); run.setConstraintVersion(0);
                run.setCancelRequested(false); run.setLeaseEpoch(0); run.setExecutionMillis(0L);
                run.setPromptTokens(0); run.setCompletionTokens(0); run.setUsageUnknown(false);
                AgentMessage message = append(session, run, "USER", "USER", MessageBody.text(request.getContent()), null, request.getClientRequestId(), null);
                run.setObjectiveMessageId(message.getId()); runs.insert(run);
                setActive(session, run.getId()); event(session, run, "RUN_QUEUED", Map.of("status", "QUEUED"));
                return new Submission(sessionId, message.getId(), run.getId());
            }
            if (request.getExpectedRunId() == null || !Objects.equals(session.getActiveRunId(), request.getExpectedRunId()))
                throw new BizException(409, "RUN_CHANGED: 当前活动任务已改变");
            AgentRun run = runs.lockById(request.getExpectedRunId());
            if (run == null || RunStatus.valueOf(run.getStatus()).terminal() || Boolean.TRUE.equals(run.getCancelRequested()))
                throw new BizException("任务已结束或正在取消");
            if (request.getProviderId() != null || request.getModelCode() != null) throw new BizException("活动任务不能更换模型");
            if ("ANSWER".equals(request.getIntent()) && !"WAITING_USER".equals(run.getStatus()))
                throw new BizException("当前任务未等待用户回答");
            AgentMessage message = append(session, run, "USER", "USER", MessageBody.text(request.getContent()), null, request.getClientRequestId(), null);
            run.setObjective(run.getObjective() + "\n用户补充：" + request.getContent()); run.setConstraintVersion(run.getConstraintVersion() + 1);
            run.setObjectiveMessageId(message.getId());
            if ("WAITING_USER".equals(run.getStatus())) {
                for (AgentStep step : steps(run.getId())) {
                    if ("WAITING_USER".equals(step.getStatus())) {
                        step.setStatus("SUCCEEDED"); step.setResultJson(json.write(ToolResult.success(Map.of("userAnswer", request.getContent()))));
                        steps.updateById(step);
                        toolMessage(session, run, step, json.read(step.getResultJson(), ToolResult.class));
                        run.setToolCalls(run.getToolCalls() + 1);
                    }
                }
                run.setStatus("QUEUED");
            }
            runs.updateById(run);
            event(session, run, "USER_INPUT_SAVED", Map.of("intent", request.getIntent(), "status", run.getStatus()));
            return new Submission(sessionId, message.getId(), run.getId());
        });
    }
    public List<AgentRun> candidates() {
        return runs.selectList(new LambdaQueryWrapper<AgentRun>()
                .in(AgentRun::getStatus, "QUEUED", "RUNNING", "WAITING_TASK", "CANCELLING")
                .and(q -> q.isNull(AgentRun::getLeaseUntil).or().le(AgentRun::getLeaseUntil, LocalDateTime.now()))
                .orderByAsc(AgentRun::getCreateTime).last("LIMIT 100"));
    }
    public AgentRun claim(Long id, String owner) {
        AgentRun current = run(id);
        return transactions.execute(tx -> {
            AgentSession session = lockSession(current.getSessionId());
            AgentRun run = runs.lockById(id);
            if (RunStatus.valueOf(run.getStatus()).terminal() || "WAITING_USER".equals(run.getStatus())) return null;
            boolean wasWaiting = "WAITING_TASK".equals(run.getStatus());
            if (run.getLeaseUntil() != null && run.getLeaseUntil().isAfter(LocalDateTime.now())) return null;
            if (Boolean.TRUE.equals(run.getCancelRequested())) {
                finishLocked(session, run, "CANCELLED", "已停止后续操作；已提交的数据保留。", null);
                return null;
            }
            run.setLeaseOwner(owner); run.setLeaseEpoch(run.getLeaseEpoch() + 1); run.setLeaseUntil(LocalDateTime.now().plusSeconds(30));
            run.setStatus("RUNNING"); if (run.getStartedAt() == null) run.setStartedAt(LocalDateTime.now());
            runs.updateById(run);
            if (!wasWaiting) event(session, run, "RUN_STARTED", Map.of("status", "RUNNING"));
            return run;
        });
    }
    public boolean heartbeat(Long id, String owner, int epoch) {
        return runs.update(null, new LambdaUpdateWrapper<AgentRun>().eq(AgentRun::getId, id).eq(AgentRun::getLeaseOwner, owner)
                .eq(AgentRun::getLeaseEpoch, epoch).eq(AgentRun::getStatus, "RUNNING").eq(AgentRun::getCancelRequested, false)
                .set(AgentRun::getLeaseUntil, LocalDateTime.now().plusSeconds(30))) == 1;
    }
    public AgentStep prepareModel(Long id, String owner, int epoch) {
        return fenced(id, owner, epoch, (session, run) -> {
            var existing = steps(run.getId()).stream().filter(s -> "MODEL".equals(s.getStepType())
                    && !"SUCCEEDED".equals(s.getStatus()) && !"CANCELLED".equals(s.getStatus())).findFirst();
            if (existing.isPresent()) return existing.get();
            AgentStep step = newStep(run, "MODEL", "model/" + (run.getModelTurns() + 1));
            step.setInputJson(json.write(Map.of("objective", run.getObjective()))); steps.insert(step); runs.updateById(run);
            event(session, run, "MODEL_STARTED", Map.of("stepId", String.valueOf(step.getId())));
            return step;
        });
    }
    public boolean saveModel(Long id, String owner, int epoch, AgentStep original, ModelReply reply, long elapsed) {
        return fenced(id, owner, epoch, (session, run) -> {
            AgentStep step = steps.lockById(original.getId());
            if ("SUCCEEDED".equals(step.getStatus())) return true;
            if (!Objects.equals(step.getConstraintVersion(), run.getConstraintVersion())) {
                step.setStatus("CANCELLED"); steps.updateById(step);
                // Superseded inference still consumed a model turn and provider tokens.
                run.setModelTurns(run.getModelTurns() + 1);
                run.setPromptTokens(run.getPromptTokens() + (reply.promptTokens() == null ? 0 : reply.promptTokens()));
                run.setCompletionTokens(run.getCompletionTokens() + (reply.completionTokens() == null ? 0 : reply.completionTokens()));
                run.setUsageUnknown(run.getUsageUnknown() || reply.promptTokens() == null || reply.completionTokens() == null);
                run.setExecutionMillis(run.getExecutionMillis() + elapsed); runs.updateById(run); return false;
            }
            if (reply.toolCalls() == null || reply.toolCalls().size() > 100) throw new BizException("模型工具调用数量无效");
            Set<String> callIds = new java.util.HashSet<>();
            for (ToolCall call : reply.toolCalls()) {
                if (call.id() == null || call.id().isBlank() || call.id().length() > 200 || !callIds.add(call.id())
                        || call.name() == null || call.name().isBlank() || call.name().length() > 64
                        || call.arguments() == null || call.arguments().length() > 65536) throw new BizException("模型工具调用协议无效");
            }
            step.setStatus("SUCCEEDED"); step.setResultJson(json.write(reply)); step.setProviderCallId(reply.responseId());
            steps.updateById(step);
            append(session, run, "ASSISTANT", "ASSISTANT", new MessageBody(reply.content(), reply.toolCalls(), reply.properties(), null, null, step.getId()), null, null, null);
            Snapshot snapshot = snapshot(run);
            int callIndex = 0;
            for (ToolCall call : reply.toolCalls()) {
                AgentStep tool = newStep(run, "TOOL", "tool/" + step.getId() + "/" + callIndex++);
                tool.setParentStepId(step.getId());
                tool.setToolCallId(call.id()); tool.setToolName(call.name()); tool.setToolVersion(snapshot.toolVersions().getOrDefault(call.name(), 0));
                tool.setInputJson(json.write(call)); steps.insert(tool);
            }
            run.setModelTurns(run.getModelTurns() + 1); run.setExecutionMillis(run.getExecutionMillis() + elapsed);
            run.setPromptTokens(run.getPromptTokens() + (reply.promptTokens() == null ? 0 : reply.promptTokens()));
            run.setCompletionTokens(run.getCompletionTokens() + (reply.completionTokens() == null ? 0 : reply.completionTokens()));
            run.setUsageUnknown(run.getUsageUnknown() || reply.promptTokens() == null || reply.completionTokens() == null);
            runs.updateById(run); return true;
        });
    }
    public AgentStep beginTool(Long id, String owner, int epoch, Long stepId) {
        return fenced(id, owner, epoch, (session, run) -> {
            AgentStep step = steps.lockById(stepId);
            if ("SUCCEEDED".equals(step.getStatus()) || "CANCELLED".equals(step.getStatus())) return step;
            if (!Objects.equals(step.getConstraintVersion(), run.getConstraintVersion())) {
                ToolResult result = ToolResult.error("CONSTRAINT_CHANGED", "用户补充已生效，请按最新目标重新规划");
                step.setStatus("SUCCEEDED"); step.setResultJson(json.write(result)); steps.updateById(step);
                toolMessage(session, run, step, result); return step;
            }
            step.setStatus("RUNNING"); step.setAttempts(step.getAttempts() + 1); steps.updateById(step);
            if (step.getWaitHandleJson() == null) event(session, run, "TOOL_STARTED", Map.of("tool", step.getToolName(), "stepId", String.valueOf(stepId)));
            return step;
        });
    }
    /** TRANSACTIONAL supplier executes in the same transaction as the durable success checkpoint. */
    public ToolResult commitTool(Long id, String owner, int epoch, Long stepId, Supplier<ToolResult> operation, long elapsed) {
        return fenced(id, owner, epoch, (session, run) -> {
            AgentStep step = steps.lockById(stepId);
            if ("SUCCEEDED".equals(step.getStatus())) return json.read(step.getResultJson(), ToolResult.class);
            long operationStart = System.nanoTime();
            ToolResult result = !Objects.equals(step.getConstraintVersion(), run.getConstraintVersion())
                    ? ToolResult.error("CONSTRAINT_CHANGED", "用户补充已生效，请重新规划") : operation.get();
            if (result == null) throw new BizException("工具未返回结构化结果");
            String resultJson = json.write(result);
            if (resultJson.length() > 600000) throw new BizException("工具结果过大");
            step.setResultJson(resultJson);
            run.setExecutionMillis(run.getExecutionMillis() + elapsed + (System.nanoTime() - operationStart) / 1000000);
            switch (result.kind()) {
                case "DEFERRED" -> {
                    if (result.waitHandle() == null) throw new BizException("异步工具缺少等待句柄");
                    long hardDeadline = System.currentTimeMillis() + snapshot(run).config().getRuntime().getExternalWaitTimeoutSeconds() * 1000L;
                    WaitHandle handle = new WaitHandle(result.waitHandle().adapter(), result.waitHandle().taskId(),
                            Math.min(result.waitHandle().deadlineEpochMillis(), hardDeadline));
                    // Preserve the first deadline: reconciliation must not extend an existing wait.
                    boolean firstWait = step.getWaitHandleJson() == null;
                    if (firstWait) step.setWaitHandleJson(json.write(handle));
                    step.setStatus("WAITING_EXTERNAL"); run.setStatus("WAITING_TASK"); releaseLease(run);
                    run.setLeaseUntil(LocalDateTime.now().plusSeconds(5));
                    if (firstWait) event(session, run, "WAITING_TASK", Map.of("tool", step.getToolName(), "status", "WAITING_TASK"));
                }
                case "NEED_USER" -> {
                    step.setStatus("WAITING_USER"); run.setStatus("WAITING_USER"); releaseLease(run);
                    event(session, run, "WAITING_USER", Map.of("question", result.message(), "status", "WAITING_USER"));
                }
                case "IMMEDIATE" -> {
                    step.setStatus("SUCCEEDED"); run.setToolCalls(run.getToolCalls() + 1);
                    toolMessage(session, run, step, result);
                    event(session, run, "TOOL_COMPLETED", Map.of("tool", step.getToolName(), "hasError", result.errorCode() != null));
                }
                default -> throw new BizException("工具结果类型无效");
            }
            steps.updateById(step); updateRun(run); return result;
        });
    }
    public void complete(Long id, String owner, int epoch, String status, String content) {
        fenced(id, owner, epoch, (session, run) -> { finishLocked(session, run, status, content, null); return null; });
    }
    public void fail(Long id, String owner, int epoch, String error) {
        try { fenced(id, owner, epoch, (session, run) -> { finishLocked(session, run, "FAILED", null, error); return null; }); }
        catch (LeaseLost ignored) { }
    }
    public void askUser(Long id, String owner, int epoch, String question) {
        fenced(id, owner, epoch, (session, run) -> {
            run.setStatus("WAITING_USER"); releaseLease(run); updateRun(run);
            event(session, run, "WAITING_USER", Map.of("status", "WAITING_USER", "question", question)); return null;
        });
    }
    public void addCorrection(Long id, String owner, int epoch, String problem) {
        fenced(id, owner, epoch, (session, run) -> {
            append(session, run, "SYSTEM", "SYSTEM", MessageBody.text(problem), null, null, null); return null;
        });
    }
    public void modelAttemptFailed(Long id, String owner, int epoch, Long stepId, String error, long elapsed) {
        fenced(id, owner, epoch, (session, run) -> {
            AgentStep step = steps.lockById(stepId);
            step.setAttempts(step.getAttempts() + 1); step.setStatus("PENDING");
            step.setResultJson(json.write(Map.of("error", Objects.toString(error, "模型调用失败")))); steps.updateById(step);
            run.setUsageUnknown(true); run.setExecutionMillis(run.getExecutionMillis() + elapsed); runs.updateById(run);
            event(session, run, "MODEL_RETRY", Map.of("attempts", step.getAttempts(), "message", Objects.toString(error, "模型调用失败")));
            return null;
        });
    }
    public void cancel(Long id) {
        AgentRun current = run(id);
        transactions.executeWithoutResult(tx -> {
            AgentSession session = lockSession(current.getSessionId());
            AgentRun run = runs.lockById(id);
            if (RunStatus.valueOf(run.getStatus()).terminal()) return;
            run.setCancelRequested(true);
            // All production writes hold this same lock, so a completed cancellation fences every subsequent write.
            finishLocked(session, run, "CANCELLED", "已停止后续操作；已提交的数据保留。", null);
        });
    }
    public void resume(Long id) {
        AgentRun current = run(id);
        transactions.executeWithoutResult(tx -> {
            AgentSession session = lockSession(current.getSessionId());
            AgentRun run = runs.lockById(id);
            if (!"FAILED".equals(run.getStatus())) throw new BizException("仅失败任务可直接重试；等待回答时请提交 ANSWER");
            if (session.getActiveRunId() != null) throw new BizException(409, "当前会话已有其他活动任务");
            var latest = runs.selectOne(new LambdaQueryWrapper<AgentRun>().eq(AgentRun::getSessionId, session.getId())
                    .orderByDesc(AgentRun::getCreateTime).orderByDesc(AgentRun::getId).last("LIMIT 1"));
            if (latest == null || !id.equals(latest.getId())) throw new BizException("会话已有后续任务，请新建目标继续");
            // Interrupted tool calls were sealed when the run failed. Recovery reuses the same logical calls.
            for (AgentStep step : steps(id)) {
                if ("CANCELLED".equals(step.getStatus()) && "TOOL".equals(step.getStepType())) {
                    step.setStatus("PENDING"); steps.updateById(step);
                }
                if ("MODEL".equals(step.getStepType()) && "PENDING".equals(step.getStatus())) {
                    step.setAttempts(0); steps.updateById(step);
                }
            }
            if (runs.update(null, new LambdaUpdateWrapper<AgentRun>().eq(AgentRun::getId, id)
                    .set(AgentRun::getStatus, "QUEUED").set(AgentRun::getErrorMessage, null)
                    .set(AgentRun::getFinalResultJson, null).set(AgentRun::getFinishedAt, null).set(AgentRun::getReadAt, null)) != 1)
                throw new BizException("恢复任务失败");
            setActive(session, id);
            event(session, run, "RUN_QUEUED", Map.of("status", "QUEUED", "resumed", true));
        });
    }
    public void markRead(Long id) {
        runs.update(null, new LambdaUpdateWrapper<AgentRun>().eq(AgentRun::getId, id).isNotNull(AgentRun::getFinishedAt)
                .set(AgentRun::getReadAt, LocalDateTime.now()));
    }
    public void saveSummary(Long id, String owner, int epoch, int expectedCovered, int through, ModelReply summary, long elapsed) {
        fenced(id, owner, epoch, (session, run) -> {
            if (session.getCoveredThroughSeq() != expectedCovered) return null;
            session.setSummary(summary.content()); session.setCoveredThroughSeq(through); session.setSummaryVersion(session.getSummaryVersion() + 1);
            sessions.updateById(session);
            AgentStep step = newStep(run, "SUMMARY", "summary/" + expectedCovered + "/" + through);
            step.setStatus("SUCCEEDED"); step.setResultJson(json.write(summary)); steps.insert(step);
            run.setExecutionMillis(run.getExecutionMillis() + elapsed);
            run.setPromptTokens(run.getPromptTokens() + (summary.promptTokens() == null ? 0 : summary.promptTokens()));
            run.setCompletionTokens(run.getCompletionTokens() + (summary.completionTokens() == null ? 0 : summary.completionTokens()));
            run.setUsageUnknown(run.getUsageUnknown() || summary.promptTokens() == null || summary.completionTokens() == null);
            runs.updateById(run); return null;
        });
    }
    private interface FencedOperation<T> { T apply(AgentSession session, AgentRun run); }
    private <T> T fenced(Long id, String owner, int epoch, FencedOperation<T> operation) {
        AgentRun current = run(id);
        return transactions.execute(tx -> {
            AgentSession session = lockSession(current.getSessionId());
            AgentRun run = runs.lockById(id);
            if (!"RUNNING".equals(run.getStatus()) || Boolean.TRUE.equals(run.getCancelRequested())
                    || !Objects.equals(owner, run.getLeaseOwner()) || run.getLeaseEpoch() != epoch
                    || run.getLeaseUntil() == null || run.getLeaseUntil().isBefore(LocalDateTime.now())) throw new LeaseLost();
            return operation.apply(session, run);
        });
    }
    private AgentSession lockSession(Long id) {
        AgentSession session = sessions.lockById(id);
        if (session == null) throw new BizException(404, "Agent 会话不存在");
        return session;
    }
    private AgentStep newStep(AgentRun run, String type, String key) {
        AgentStep step = new AgentStep();
        step.setId(IdWorker.getId()); step.setRunId(run.getId()); step.setLogicalStepKey(key);
        step.setStepOrder(run.getNextStepOrder()); run.setNextStepOrder(run.getNextStepOrder() + 1);
        step.setStepType(type); step.setStatus("PENDING"); step.setAttempts(0);
        step.setIdempotencyKey(run.getId() + "/" + key); step.setConstraintVersion(run.getConstraintVersion());
        return step;
    }
    private AgentMessage append(AgentSession session, AgentRun run, String type, String role, Object body,
                                String toolCallId, String requestId, String eventType) {
        if (session.getNextSeq() == Integer.MAX_VALUE) throw new BizException("会话消息序号达到上限，请新建会话");
        AgentMessage message = new AgentMessage();
        message.setId(IdWorker.getId()); message.setSessionId(session.getId()); message.setRunId(run == null ? null : run.getId());
        message.setSeq(session.getNextSeq()); session.setNextSeq(session.getNextSeq() + 1);
        message.setMessageType(type); message.setModelRole(role); message.setContentJson(json.write(body));
        message.setToolCallId(toolCallId); message.setClientRequestId(requestId); message.setEventType(eventType);
        messages.insert(message); sessions.updateById(session);
        String eventName = "EVENT".equals(type) ? eventType : "MESSAGE_SAVED";
        events.publishAfterCommit(new Event(session.getId(), message.getRunId(), message.getSeq(), eventName, view(message)),
                run != null && "CONFIG_TEST".equals(run.getRunType()));
        return message;
    }
    private void event(AgentSession session, AgentRun run, String type, Object payload) {
        append(session, run, "EVENT", null, payload, null, null, type);
    }
    private void toolMessage(AgentSession session, AgentRun run, AgentStep step, ToolResult result) {
        append(session, run, "TOOL_RESULT", "TOOL", new MessageBody(null, List.of(), Map.of(), step.getToolName(), result, step.getParentStepId()),
                step.getToolCallId(), null, null);
    }
    public MessageView view(AgentMessage item) {
        Object body = "EVENT".equals(item.getMessageType()) ? json.read(item.getContentJson(), Map.class)
                : json.read(item.getContentJson(), MessageBody.class);
        if (body instanceof MessageBody modelBody) body = new MessageBody(modelBody.content(), modelBody.toolCalls(),
                Map.of(), modelBody.toolName(), modelBody.result(), modelBody.modelStepId());
        return new MessageView(item.getId(), item.getSessionId(), item.getRunId(), item.getSeq(), item.getMessageType(),
                item.getEventType(), item.getToolCallId(), body, item.getCreateTime());
    }
    private void finishLocked(AgentSession session, AgentRun run, String status, String content, String error) {
        // Complete every pending call so future model turns cannot contain orphaned tool messages.
        for (AgentStep step : steps(run.getId())) {
            if ("TOOL".equals(step.getStepType()) && !"SUCCEEDED".equals(step.getStatus()) && !"CANCELLED".equals(step.getStatus())) {
                ToolResult result = ToolResult.error("CANCELLED".equals(status) ? "CANCELLED" : "RUN_FAILED", "调用未完成");
                step.setStatus("CANCELLED"); step.setResultJson(json.write(result)); steps.updateById(step);
                toolMessage(session, run, step, result);
            }
        }
        run.setStatus(status); run.setErrorMessage(error); run.setFinishedAt(LocalDateTime.now()); releaseLease(run);
        run.setFinalResultJson(json.write(Map.of("summary", content == null ? Objects.toString(error, "任务结束") : content,
                "status", status, "modelTurns", run.getModelTurns(), "toolCalls", run.getToolCalls())));
        updateRun(run); setActive(session, null);
        String type = "COMPLETED".equals(status) ? "RUN_COMPLETED" : "CANCELLED".equals(status) ? "RUN_CANCELLED" : "RUN_FAILED";
        event(session, run, type, json.read(run.getFinalResultJson(), Map.class));
    }
    private void setActive(AgentSession session, Long runId) {
        session.setActiveRunId(runId);
        sessions.update(null, new LambdaUpdateWrapper<AgentSession>().eq(AgentSession::getId, session.getId())
                .set(AgentSession::getActiveRunId, runId));
    }
    private void releaseLease(AgentRun run) { run.setLeaseOwner(null); run.setLeaseUntil(null); }
    private void updateRun(AgentRun run) {
        runs.updateById(run);
        runs.update(null, new LambdaUpdateWrapper<AgentRun>().eq(AgentRun::getId, run.getId())
                .set(AgentRun::getLeaseOwner, run.getLeaseOwner()).set(AgentRun::getLeaseUntil, run.getLeaseUntil()));
    }
    private void validateRequest(SendMessage request) {
        if (request == null || request.getClientRequestId() == null || request.getClientRequestId().isBlank()
                || request.getClientRequestId().length() > 100 || request.getContent() == null || request.getContent().isBlank()
                || request.getContent().length() > 100000 || !Set.of("START_RUN", "STEER", "ANSWER").contains(request.getIntent()))
            throw new BizException(400, "请提供有效的请求 ID、输入类型和 1–100000 字的消息");
    }
}
