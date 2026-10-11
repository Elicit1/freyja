package com.astra.freyja.agent;

import com.astra.freyja.agent.context.AgentContextBuilder;
import com.astra.freyja.agent.gateway.AgentModelGateway;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.profile.*;
import com.astra.freyja.agent.runtime.*;
import com.astra.freyja.agent.tool.*;
import com.astra.freyja.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRuntimeTest {
    AgentStore store = mock(AgentStore.class);
    AgentModelGateway gateway = mock(AgentModelGateway.class);
    AgentContextBuilder context = mock(AgentContextBuilder.class);
    AgentToolExecutor tools = mock(AgentToolExecutor.class);
    AgentToolRegistry registry = mock(AgentToolRegistry.class);
    AgentJson json = new AgentJson();
    List<AgentStep> journal = new ArrayList<>();
    AgentRun run;
    AgentRuntime runtime;
    AtomicLong stepIds = new AtomicLong(1);
    @BeforeEach void setup() {
        var snapshot = AgentTestSupport.snapshot(List.of(), "NORMAL");
        run = AgentTestSupport.run(snapshot, json);
        when(store.run(run.getId())).thenReturn(run); when(store.snapshot(run)).thenReturn(snapshot);
        AgentSession session = new AgentSession(); session.setId(run.getSessionId());
        when(store.session(run.getSessionId())).thenReturn(session);
        when(store.steps(run.getId())).thenAnswer(call -> new ArrayList<>(journal));
        when(context.build(any(), anyString(), anyInt())).thenReturn(List.of(new org.springframework.ai.chat.messages.UserMessage("目标")));
        when(store.prepareModel(anyLong(), anyString(), anyInt())).thenAnswer(call -> {
            AgentStep step = new AgentStep(); step.setId(stepIds.getAndIncrement()); step.setStepType("MODEL"); step.setStatus("PENDING");
            step.setConstraintVersion(0); step.setAttempts(0); journal.add(step); return step;
        });
        when(store.saveModel(anyLong(), anyString(), anyInt(), any(), any(), anyLong())).thenAnswer(call -> {
            AgentStep step = call.getArgument(3); ModelReply reply = call.getArgument(4);
            step.setStatus("SUCCEEDED"); step.setResultJson(json.write(reply)); run.setModelTurns(run.getModelTurns() + 1);
            for (ToolCall tool : reply.toolCalls()) {
                AgentStep pending = new AgentStep(); pending.setId(stepIds.getAndIncrement()); pending.setStepType("TOOL");
                pending.setStatus("PENDING"); pending.setToolName(tool.name()); journal.add(pending);
            }
            return true;
        });
        doAnswer(call -> { run.setStatus("COMPLETED"); return null; }).when(store).complete(anyLong(), anyString(), anyInt(), anyString(), anyString());
        runtime = new AgentRuntime(store, gateway, context, tools, registry, new AgentProfileRegistry(List.of(new CoreAssistantProfile())), json);
    }
    @Test void toolCallsAreDurableBeforeExecutionAndObservedByNextModelTurn() {
        when(gateway.call(any(), anyList(), anyList(), anyLong())).thenReturn(
                new ModelReply("", List.of(new ToolCall("c1", "read", "{}")), Map.of(), 10, 5, "first"),
                new ModelReply("已读取项目", List.of(), Map.of(), 20, 6, "second"));
        when(tools.execute(any(), any(), anyString(), anyInt())).thenAnswer(call -> {
            assertTrue(journal.stream().anyMatch(s -> "MODEL".equals(s.getStepType()) && "SUCCEEDED".equals(s.getStatus())));
            ((AgentStep) call.getArgument(1)).setStatus("SUCCEEDED"); return ToolResult.success("事实");
        });
        runtime.execute(run.getId(), "test-owner", 1);
        var order = inOrder(store, gateway, tools);
        order.verify(gateway).call(any(), anyList(), anyList(), anyLong());
        order.verify(store).saveModel(anyLong(), anyString(), anyInt(), any(), any(), anyLong());
        order.verify(tools).execute(any(), any(), anyString(), anyInt());
        order.verify(gateway).call(any(), anyList(), anyList(), anyLong());
        assertEquals("COMPLETED", run.getStatus());
    }
    @Test void restartWithSavedFinalReplyFinishesWithoutAnotherModelCall() {
        AgentStep step = new AgentStep(); step.setStepType("MODEL"); step.setStatus("SUCCEEDED"); step.setConstraintVersion(0);
        step.setResultJson(json.write(new ModelReply("已完成", List.of(), Map.of(), 1, 1, "saved"))); journal.add(step);
        runtime.execute(run.getId(), "test-owner", 1);
        verifyNoInteractions(gateway); verify(store).complete(run.getId(), "test-owner", 1, "COMPLETED", "已完成");
    }
    @Test void asyncWaitReleasesRunAndDoesNotSpinOrCallModel() {
        AgentStep step = new AgentStep(); step.setStepType("TOOL"); step.setStatus("WAITING_EXTERNAL"); journal.add(step);
        when(tools.execute(any(), any(), anyString(), anyInt())).thenReturn(ToolResult.deferred(new WaitHandle("fake", "job", System.currentTimeMillis() + 10000)));
        runtime.execute(run.getId(), "test-owner", 1);
        verify(tools, times(1)).execute(any(), any(), anyString(), anyInt()); verifyNoInteractions(gateway);
    }
    @Test void newWorkerOrCancelledRunDoesNotContinueOldExecution() {
        run.setLeaseEpoch(2);
        runtime.execute(run.getId(), "test-owner", 1);
        verifyNoInteractions(gateway, tools);
        run.setLeaseEpoch(1); run.setStatus("CANCELLED");
        runtime.execute(run.getId(), "test-owner", 1);
        verifyNoInteractions(gateway, tools);
    }
    @Test void emptyAnswerCannotPassCompletionPolicy() {
        run.setModelTurns(20);
        AgentStep step = new AgentStep(); step.setStepType("MODEL"); step.setStatus("SUCCEEDED"); step.setConstraintVersion(0);
        step.setResultJson(json.write(new ModelReply("", List.of(), Map.of(), 1, 1, "empty"))); journal.add(step);
        runtime.execute(run.getId(), "test-owner", 1);
        verify(store, never()).complete(anyLong(), anyString(), anyInt(), anyString(), anyString());
        verify(store).fail(eq(run.getId()), eq("test-owner"), eq(1), contains("预算"));
    }
    @Test void exhaustedExecutionBudgetPreventsAnotherRetry() {
        store.snapshot(run).config().getRuntime().setMaxRetries(3);
        when(gateway.call(any(), anyList(), anyList(), anyLong())).thenThrow(new IllegalStateException("timeout"));
        doAnswer(call -> {
            run.setExecutionMillis(900000L); return null;
        }).when(store).modelAttemptFailed(anyLong(), anyString(), anyInt(), anyLong(), anyString(), anyLong());
        runtime.execute(run.getId(), "test-owner", 1);
        verify(gateway, times(1)).call(any(), anyList(), anyList(), anyLong());
        verify(store).fail(eq(run.getId()), eq("test-owner"), eq(1), contains("时限"));
    }
    @Test void summaryTimeIsChargedOnceAndLimitsRemainingModelTime() {
        when(context.build(any(), anyString(), anyInt())).thenAnswer(call -> {
            run.setExecutionMillis(899950L);
            return List.of(new org.springframework.ai.chat.messages.UserMessage("摘要后的目标"));
        });
        when(gateway.call(any(), anyList(), anyList(), anyLong())).thenAnswer(call -> {
            long remaining = call.getArgument(3);
            assertTrue(remaining > 0 && remaining <= 50);
            return new ModelReply("完成", List.of(), Map.of(), 1, 1, "summary");
        });
        runtime.execute(run.getId(), "test-owner", 1);
        verify(store).saveModel(anyLong(), anyString(), anyInt(), any(), any(), longThat(elapsed -> elapsed < 50));
        assertEquals("COMPLETED", run.getStatus());
    }
}
