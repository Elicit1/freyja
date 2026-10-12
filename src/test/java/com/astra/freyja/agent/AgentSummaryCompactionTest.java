package com.astra.freyja.agent;

import com.astra.freyja.agent.context.AgentContextBuilder;
import com.astra.freyja.agent.gateway.AgentModelGateway;
import com.astra.freyja.agent.memory.*;
import com.astra.freyja.agent.model.*;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.entity.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentSummaryCompactionTest {
    AgentJson json = new AgentJson();
    AgentStore store = mock(AgentStore.class);
    AgentModelGateway gateway = mock(AgentModelGateway.class);
    Snapshot snapshot;
    AgentRun run;
    AgentSession session;
    AgentContextBuilder builder;
    List<AgentMessage> history = new ArrayList<>();
    @BeforeEach void setup() {
        snapshot = AgentTestSupport.snapshot(List.of(), "NORMAL");
        snapshot.config().getModel().setMaxTokens(8192); snapshot.config().getModel().setReasoningMode("HIGH");
        snapshot.config().getMemory().setContextTokens(16384); snapshot.config().getMemory().setRecentMessages(4);
        snapshot.config().getMemory().getSummary().setReasoningMode("HIGH");
        snapshot.pinnedModel().setProviderId(snapshot.config().getModel().getProviderId());
        snapshot.pinnedModel().setParamsJson("{\"capabilities\":{\"contextWindow\":1048576,\"maxOutputTokens\":393216,\"reasoningProtocol\":\"DEEPSEEK_CHAT\",\"reasoningEfforts\":[\"low\",\"high\",\"max\"]}}");
        run = AgentTestSupport.run(snapshot, json); run.setModelTurns(38); run.setToolCalls(99); run.setExecutionMillis(780000L);
        session = new AgentSession(); session.setId(run.getSessionId()); session.setCoveredThroughSeq(399); session.setSummary("旧".repeat(4993));
        when(store.snapshot(run)).thenReturn(snapshot); when(store.run(run.getId())).thenReturn(run); when(store.session(session.getId())).thenReturn(session);
        for (int n = 0; n < 6; n++) {
            var row = new AgentMessage(); row.setId(400L + n); row.setSeq(400 + n); row.setMessageType("USER");
            row.setContentJson(json.write(MessageBody.text(n < 2 ? "新".repeat(809) : "继续"))); history.add(row);
        }
        when(store.modelHistory(session.getId(), 399)).thenReturn(history);
        doAnswer(call -> { charge(call.getArgument(5), call.getArgument(6)); return null; })
                .when(store).summaryAttemptFailed(anyLong(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyLong(), anyString(), anyInt());
        doAnswer(call -> { ModelReply reply = call.getArgument(5); charge(reply, call.getArgument(6)); session.setSummary(reply.content()); session.setCoveredThroughSeq(call.getArgument(4)); return null; })
                .when(store).saveSummary(anyLong(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyLong());
        builder = new AgentContextBuilder(store, json, mock(AgentMemoryService.class), new AgentMemorySummarizer(gateway, json), null, null, List.of());
    }
    void charge(ModelReply reply, long elapsed) { run.setExecutionMillis(run.getExecutionMillis() + elapsed); if (reply != null) { run.setPromptTokens(run.getPromptTokens() + reply.promptTokens()); run.setCompletionTokens(run.getCompletionTokens() + reply.completionTokens()); } }
    ModelReply truncated() { return new ModelReply(null, List.of(), Map.of("reasoningContent", "private"), 6600, 8191, "length-request", "length", 8191); }
    @Test void reasoningExhaustionChangesPolicyAndChargesFailureBeforeSavingCompleteSummary() {
        var originalRows = history.stream().map(AgentMessage::getContentJson).toList();
        when(gateway.call(any(), anyList(), anyList(), anyLong())).thenReturn(truncated(), new ModelReply("保留目标、分镜 ID 和待办。", List.of(), Map.of(), 6500, 30, "success", "stop", 0));
        var result = builder.build(run, "test-owner", 1);
        var requests = org.mockito.ArgumentCaptor.forClass(Snapshot.class);
        verify(gateway, times(2)).call(requests.capture(), anyList(), eq(List.of()), anyLong());
        assertEquals(16384, requests.getAllValues().get(0).config().getModel().getMaxTokens());
        assertEquals(32768, requests.getAllValues().get(1).config().getModel().getMaxTokens());
        assertEquals("DISABLED", requests.getAllValues().get(1).config().getModel().getReasoningMode());
        assertEquals(8192, snapshot.config().getModel().getMaxTokens()); assertEquals("HIGH", snapshot.config().getModel().getReasoningMode());
        assertEquals(401, session.getCoveredThroughSeq()); assertEquals(originalRows, history.stream().map(AgentMessage::getContentJson).toList());
        assertEquals(8221, run.getCompletionTokens()); assertEquals(38, run.getModelTurns()); assertEquals(99, run.getToolCalls());
        assertTrue(result.stream().anyMatch(m -> m.getText().contains("分镜 ID")));
    }
    @Test void exhaustedCompactionPreservesCursorAndEveryRawMessageAndReportsRecoverableBlock() {
        when(gateway.call(any(), anyList(), anyList(), anyLong())).thenReturn(truncated());
        var failure = assertThrows(SummaryBlockedException.class, () -> builder.build(run, "test-owner", 1));
        assertEquals("TRUNCATED", failure.code()); assertEquals(399, session.getCoveredThroughSeq()); assertEquals(4993, session.getSummary().length());
        assertEquals(6, history.size()); assertEquals(24573, run.getCompletionTokens());
        verify(store, never()).saveSummary(anyLong(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyLong());
        verify(gateway, times(3)).call(any(), anyList(), eq(List.of()), anyLong());
    }
    @Test void partialNonemptySummaryCannotAdvanceCursorAndOversizedBodyIsNotAccepted() {
        assertEquals("TRUNCATED", AgentContextBuilder.invalidSummary(new ModelReply("半截正文", List.of(), Map.of(), 1, 10, "partial", "length", 0), 4000));
        assertEquals("OVERSIZED", AgentContextBuilder.invalidSummary(new ModelReply("字".repeat(4001), List.of(), Map.of(), 1, 10, "large", "stop", 0), 4000));
        assertEquals("INCOMPLETE", AgentContextBuilder.invalidSummary(new ModelReply("正文", List.of(), Map.of(), 1, 10, "unknown", null, 0), 4000));
    }
}
