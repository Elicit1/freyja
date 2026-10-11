package com.astra.freyja.agent;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.agent.tool.*;
import com.astra.freyja.entity.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentToolExecutorTest {
    public record Input(String value) {}
    @Test void failedExternalSubmissionIsReconciledInsteadOfSubmittedAgain() {
        var store = mock(AgentStore.class); var registry = mock(AgentToolRegistry.class); var json = new AgentJson();
        var snapshot = AgentTestSupport.snapshot(List.of("external"), "NORMAL");
        var run = AgentTestSupport.run(snapshot, json);
        AgentStep step = new AgentStep(); step.setId(3L); step.setToolName("external"); step.setStatus("PENDING");
        step.setAttempts(1); step.setInputJson(json.write(new ToolCall("c", "external", "{\"value\":\"x\"}"))); step.setIdempotencyKey("durable-key");
        AgentSession session = new AgentSession(); session.setId(run.getSessionId());
        @SuppressWarnings("unchecked") AgentTool<Input> tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("external"); when(tool.version()).thenReturn(1);
        when(tool.effect()).thenReturn(Effect.WRITE); when(tool.recoveryMode()).thenReturn(RecoveryMode.RECONCILE); when(tool.inputType()).thenReturn(Input.class);
        doReturn(tool).when(registry).require("external"); when(store.snapshot(run)).thenReturn(snapshot); when(store.session(run.getSessionId())).thenReturn(session);
        when(store.beginTool(anyLong(), anyString(), anyInt(), anyLong())).thenReturn(step);
        when(tool.reconcile(any(), any(), isNull())).thenReturn(ToolResult.success("existing-job"));
        when(store.commitTool(anyLong(), anyString(), anyInt(), anyLong(), any(), anyLong())).thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
        ToolResult result = new AgentToolExecutor(registry, store, json).execute(run, step, "test-owner", 1);
        assertEquals("existing-job", result.data()); verify(tool, never()).execute(any(), any());
        verify(tool).reconcile(eq(new Input("x")), argThat(c -> "durable-key".equals(c.idempotencyKey())), isNull());
    }
    @Test void testRunCannotInvokeWriteToolEvenWithMaliciousCall() {
        var store = mock(AgentStore.class); var registry = mock(AgentToolRegistry.class); var json = new AgentJson();
        var snapshot = AgentTestSupport.snapshot(List.of("write"), "CONFIG_TEST");
        var run = AgentTestSupport.run(snapshot, json);
        AgentStep step = new AgentStep(); step.setId(3L); step.setToolName("write");
        var tool = mock(AgentTool.class); when(tool.name()).thenReturn("write"); when(tool.version()).thenReturn(1); when(tool.effect()).thenReturn(Effect.WRITE);
        when(registry.require("write")).thenReturn(tool); when(store.snapshot(run)).thenReturn(snapshot); when(store.session(run.getSessionId())).thenReturn(new AgentSession());
        when(store.commitTool(anyLong(), anyString(), anyInt(), anyLong(), any(), anyLong())).thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
        var result = new AgentToolExecutor(registry, store, json).execute(run, step, "test-owner", 1);
        assertEquals("READ_ONLY_TEST", result.errorCode()); verify(tool, never()).execute(any(), any());
    }
}
