package com.astra.freyja.agent;

import com.astra.freyja.agent.event.AgentEventPublisher;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.profile.AgentProfileRegistry;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.agent.settings.AgentConfigService;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.agent.AgentRequests.SendMessage;
import com.astra.freyja.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentStoreTest {
    AgentSessionMapper sessions = mock(AgentSessionMapper.class);
    AgentMessageMapper messages = mock(AgentMessageMapper.class);
    AgentRunMapper runs = mock(AgentRunMapper.class);
    AgentStepMapper steps = mock(AgentStepMapper.class);
    AgentConfigService configs = mock(AgentConfigService.class);
    AgentJson json = new AgentJson();
    AgentStore store;
    AgentSession session;
    AgentRun run;
    AgentStep step;
    @BeforeEach void setup() {
        AgentTestSupport.metadata();
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        store = new AgentStore(sessions, messages, runs, steps, mock(DramaMapper.class), configs,
                mock(AgentProfileRegistry.class), json, mock(AgentEventPublisher.class), new TransactionTemplate(manager));
        session = new AgentSession(); session.setId(AgentTestSupport.SESSION); session.setAgentCode("core-assistant");
        session.setActiveRunId(AgentTestSupport.RUN); session.setNextSeq(1);
        run = AgentTestSupport.run(AgentTestSupport.snapshot(List.of("save_session_note"), "NORMAL"), json);
        step = new AgentStep(); step.setId(AgentTestSupport.STEP); step.setRunId(run.getId()); step.setStepType("TOOL");
        step.setParentStepId(300L); step.setToolName("save_session_note"); step.setToolCallId("call-1"); step.setStatus("RUNNING"); step.setConstraintVersion(0);
        when(runs.selectById(run.getId())).thenReturn(run); when(runs.lockById(run.getId())).thenReturn(run);
        when(sessions.lockById(session.getId())).thenReturn(session); when(sessions.selectById(session.getId())).thenReturn(session);
        when(steps.lockById(step.getId())).thenReturn(step); when(steps.selectList(any())).thenReturn(List.of(step));
    }
    @Test void repeatCheckpointReturnsStoredResultWithoutRepeatingWrite() {
        AtomicInteger writes = new AtomicInteger();
        store.commitTool(run.getId(), "test-owner", 1, step.getId(), () -> { writes.incrementAndGet(); return ToolResult.success(Map.of("savedId", "2032095628944588805")); }, 0);
        ToolResult replay = store.commitTool(run.getId(), "test-owner", 1, step.getId(), () -> { fail("must not repeat write"); return null; }, 0);
        assertEquals(1, writes.get()); assertEquals("SUCCEEDED", step.getStatus());
        assertTrue(json.write(replay).contains("2032095628944588805"));
        verify(messages, times(2)).insert(any(AgentMessage.class)); // one result plus its progress event
    }
    @Test void cancellationFencesEveryLateWrite() {
        run.setStatus("CANCELLED"); run.setCancelRequested(true);
        assertThrows(AgentStore.LeaseLost.class, () -> store.commitTool(run.getId(), "test-owner", 1, step.getId(), () -> {
            fail("cancelled run must not execute"); return null;
        }, 0));
        verify(messages, never()).insert(any(AgentMessage.class));
    }
    @Test void staleLeaseCannotCommitEvenWhileRunStillRunning() {
        run.setLeaseEpoch(2);
        assertThrows(AgentStore.LeaseLost.class, () -> store.commitTool(run.getId(), "test-owner", 1, step.getId(), () -> {
            fail("old worker must not execute"); return null;
        }, 0));
    }
    @Test void changedGoalRejectsOldTransactionalOperation() {
        run.setConstraintVersion(1);
        ToolResult result = store.commitTool(run.getId(), "test-owner", 1, step.getId(), () -> {
            fail("old plan must not write"); return null;
        }, 0);
        assertEquals("CONSTRAINT_CHANGED", result.errorCode());
        assertEquals("SUCCEEDED", step.getStatus());
    }
    @Test void duplicateSubmissionUsesOriginalRunWithoutReadingNewConfig() {
        AgentMessage saved = new AgentMessage(); saved.setId(123L); saved.setRunId(run.getId());
        when(messages.selectOne(any())).thenReturn(saved);
        SendMessage request = new SendMessage(); request.setClientRequestId("same-request"); request.setContent("查看资产");
        var submission = store.submit(session.getId(), request, null);
        assertEquals(run.getId(), submission.runId()); assertEquals(123L, submission.messageId());
        verifyNoInteractions(configs); verify(runs, never()).insert(any(AgentRun.class));
    }
    @Test void deferredReconciliationPreservesOriginalDeadline() {
        WaitHandle original = new WaitHandle("fake", "job-1", System.currentTimeMillis() + 10000);
        step.setWaitHandleJson(json.write(original));
        store.commitTool(run.getId(), "test-owner", 1, step.getId(),
                () -> ToolResult.deferred(new WaitHandle("fake", "job-1", System.currentTimeMillis() + 999999)), 0);
        assertEquals(original, json.read(step.getWaitHandleJson(), WaitHandle.class));
        assertEquals("WAITING_TASK", run.getStatus()); assertNull(run.getLeaseOwner());
    }
    @Test void fullModelReplyAndAllCallsAreSavedBeforeAnyBusinessOperation() {
        AgentStep model = new AgentStep(); model.setId(77L); model.setStepType("MODEL"); model.setStatus("PENDING"); model.setConstraintVersion(0);
        when(steps.lockById(77L)).thenReturn(model);
        ModelReply reply = new ModelReply("", List.of(new ToolCall("a", "save_session_note", "{}"), new ToolCall("b", "save_session_note", "{}")),
                Map.of(), 50, 10, "response-1");
        assertTrue(store.saveModel(run.getId(), "test-owner", 1, model, reply, 10));
        assertEquals(reply, json.read(model.getResultJson(), ModelReply.class));
        verify(steps, times(2)).insert(argThat((AgentStep s) -> "TOOL".equals(s.getStepType()) && s.getParentStepId().equals(77L)));
        assertEquals(1, run.getModelTurns()); assertEquals(50, run.getPromptTokens());
    }
    @Test void internalModelPropertiesAreNotExposedToChatOrReplay() {
        AgentMessage row = new AgentMessage(); row.setId(1L); row.setSessionId(session.getId()); row.setSeq(1); row.setMessageType("ASSISTANT");
        row.setContentJson(json.write(new MessageBody("结果", List.of(), Map.of("reasoningContent", "private"), null, null, 3L)));
        MessageBody view = (MessageBody) store.view(row).body();
        assertTrue(view.properties().isEmpty()); assertEquals("结果", view.content());
    }
    @Test void supersededModelReplyConsumesBudgetWithoutExecutingItsPlan() {
        AgentStep model = new AgentStep(); model.setId(77L); model.setStatus("PENDING"); model.setConstraintVersion(0);
        when(steps.lockById(77L)).thenReturn(model); run.setConstraintVersion(1);
        ModelReply reply = new ModelReply("过时结果", List.of(new ToolCall("old", "save_session_note", "{}")), Map.of(), 50, 10, "superseded");
        assertFalse(store.saveModel(run.getId(), "test-owner", 1, model, reply, 25));
        assertEquals(1, run.getModelTurns()); assertEquals(50, run.getPromptTokens()); assertEquals(10, run.getCompletionTokens());
        assertEquals(25L, run.getExecutionMillis()); assertFalse(run.getUsageUnknown());
        verify(steps, never()).insert(any(AgentStep.class)); verify(messages, never()).insert(any(AgentMessage.class));
    }
    SendMessage replacement() {
        SendMessage request = new SendMessage(); request.setClientRequestId("replace-1");
        request.setContent("改为查询道具"); request.setIntent("REPLACE_RUN"); request.setExpectedRunId(run.getId());
        return request;
    }
    void replacementConfig() {
        session.setSessionType("NORMAL");
        var snapshot = AgentTestSupport.snapshot(List.of("save_session_note"), "NORMAL");
        when(configs.get("core-assistant")).thenReturn(snapshot.config());
        when(configs.snapshot(eq("core-assistant"), any(), eq("NORMAL"))).thenReturn(snapshot);
    }
    @Test void replacementCancelsOldRunAndPairsCallsBeforeQueuingNewGoal() {
        replacementConfig();
        var submitted = store.submit(session.getId(), replacement(), null);
        assertEquals("CANCELLED", run.getStatus()); assertTrue(run.getCancelRequested()); assertNull(run.getLeaseOwner());
        assertEquals("CANCELLED", step.getStatus()); assertNotNull(run.getFinishedAt());
        assertNotEquals(run.getId(), submitted.runId()); assertEquals(submitted.runId(), session.getActiveRunId());
        verify(runs).insert(argThat((AgentRun next) -> "QUEUED".equals(next.getStatus()) && "改为查询道具".equals(next.getObjective())
                && next.getSessionId().equals(session.getId()) && !next.getCancelRequested()));
        verify(messages).insert(argThat((AgentMessage message) -> "TOOL_RESULT".equals(message.getMessageType()) && run.getId().equals(message.getRunId())));
        verify(messages).insert(argThat((AgentMessage message) -> "USER".equals(message.getMessageType())
                && "REPLACE_RUN".equals(json.read(message.getContentJson(), MessageBody.class).properties().get("intent"))));
        assertThrows(AgentStore.LeaseLost.class, () -> store.commitTool(run.getId(), "test-owner", 1, step.getId(), () -> {
            fail("old task must not commit after replacement"); return null;
        }, 0));
    }
    @Test void replacementRejectsChangedActiveRunWithoutStoppingIt() {
        session.setActiveRunId(999L);
        assertThrows(com.astra.freyja.common.BizException.class, () -> store.submit(session.getId(), replacement(), null));
        assertEquals("RUNNING", run.getStatus()); assertEquals(999L, session.getActiveRunId());
        verifyNoInteractions(configs); verify(runs, never()).insert(any(AgentRun.class));
    }
    @Test void invalidReplacementConfigLeavesOldTaskActive() {
        replacementConfig();
        when(configs.snapshot(eq("core-assistant"), any(), eq("NORMAL"))).thenThrow(new com.astra.freyja.common.BizException("模型不可用"));
        assertThrows(com.astra.freyja.common.BizException.class, () -> store.submit(session.getId(), replacement(), null));
        assertEquals("RUNNING", run.getStatus()); assertFalse(run.getCancelRequested()); assertEquals(run.getId(), session.getActiveRunId());
        assertEquals("RUNNING", step.getStatus()); verify(messages, never()).insert(any(AgentMessage.class));
    }
    @Test void retriedReplacementReturnsExistingSubmissionWithoutCancellingAnotherRun() {
        session.setActiveRunId(999L);
        AgentMessage saved = new AgentMessage(); saved.setId(123L); saved.setRunId(456L);
        when(messages.selectOne(any())).thenReturn(saved);
        var submitted = store.submit(session.getId(), replacement(), null);
        assertEquals(456L, submitted.runId()); assertEquals(999L, session.getActiveRunId());
        assertEquals("RUNNING", run.getStatus()); verifyNoInteractions(configs);
        verify(runs, never()).insert(any(AgentRun.class)); verify(messages, never()).insert(any(AgentMessage.class));
    }
    @Test void ordinarySendCannotReplaceActiveRun() {
        SendMessage request = replacement(); request.setIntent("START_RUN");
        assertThrows(com.astra.freyja.common.BizException.class, () -> store.submit(session.getId(), request, null));
        assertEquals("RUNNING", run.getStatus()); verifyNoInteractions(configs);
    }
}
