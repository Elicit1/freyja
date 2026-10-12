package com.astra.freyja.agent;

import com.astra.freyja.agent.context.AgentContextBuilder;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.entity.AgentMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentContextBuilderTest {
    AgentJson json = new AgentJson();
    AgentContextBuilder builder = new AgentContextBuilder(null, json, null, null, null, null, List.of());
    AgentMessage row(int seq, String type, MessageBody body, String callId) {
        AgentMessage row = new AgentMessage(); row.setId((long) seq); row.setSeq(seq); row.setMessageType(type);
        row.setToolCallId(callId); row.setContentJson(json.write(body)); return row;
    }
    @Test void replacementKeepsHistoryButMarksNewGoalAsReplacingStoppedWork() {
        var old = row(1, "USER", new MessageBody("旧目标", List.of(), null, null, null, null), null);
        var latest = row(2, "USER", new MessageBody("新目标", List.of(), Map.of("intent", "REPLACE_RUN"), null, null, null), null);
        var groups = builder.groups(List.of(old, latest));
        assertEquals(2, groups.size()); assertTrue(groups.getFirst().messages().getFirst().getText().endsWith("旧目标"));
        assertTrue(groups.getLast().messages().getFirst().getText().contains("已停止此前的任务"));
        assertTrue(groups.getLast().messages().getFirst().getText().endsWith("新目标"));
        assertEquals("新目标", json.read(latest.getContentJson(), MessageBody.class).content());
    }
    @Test void summaryBoundaryNeverSplitsCallAndResult() {
        var groups = List.of(new AgentContextBuilder.Group(1, 1, List.of(new UserMessage("目标"))),
                new AgentContextBuilder.Group(2, 4, List.of(new AssistantMessage("调用"))),
                new AgentContextBuilder.Group(3, 3, List.of(new UserMessage("中途补充"))));
        assertEquals(1, AgentContextBuilder.safeSummaryCut(groups, 2));
    }
    @Test void orphanedCallIsRejectedInsteadOfSentToProvider() {
        var assistant = row(2, "ASSISTANT", new MessageBody("", List.of(new ToolCall("a", "get_shot_detail", "{}")), Map.of(), null, null, 10L), null);
        assertThrows(BizException.class, () -> builder.groups(List.of(assistant)));
    }
    @Test void retriesUseLatestResultForSameDurableCallWithoutDuplicateToolMessages() {
        var assistant = row(2, "ASSISTANT", new MessageBody("", List.of(new ToolCall("a", "get_shot_detail", "{}")), Map.of(), null, null, 10L), null);
        var cancelled = row(3, "TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "get_shot_detail", ToolResult.error("RUN_FAILED", "中断"), 10L), "a");
        var recovered = row(4, "TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "get_shot_detail", ToolResult.success(Map.of("shotId", "2032095628944588801")), 10L), "a");
        var groups = builder.groups(List.of(assistant, cancelled, recovered));
        assertEquals(1, groups.size()); assertEquals(2, groups.getFirst().messages().size());
        var result = (ToolResponseMessage) groups.getFirst().messages().getLast();
        assertEquals(1, result.getResponses().size());
        assertTrue(result.getResponses().getFirst().responseData().contains("2032095628944588801"));
        assertFalse(result.getResponses().getFirst().responseData().contains("RUN_FAILED"));
    }
    @Test void repeatedProviderCallIdsAcrossTurnsStaySeparate() {
        var a = row(1, "ASSISTANT", new MessageBody("", List.of(new ToolCall("same", "read", "{}")), Map.of(), null, null, 10L), null);
        var ar = row(2, "TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "read", ToolResult.success("first"), 10L), "same");
        var b = row(3, "ASSISTANT", new MessageBody("", List.of(new ToolCall("same", "read", "{}")), Map.of(), null, null, 11L), null);
        var br = row(4, "TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "read", ToolResult.success("second"), 11L), "same");
        var groups = builder.groups(List.of(a, ar, b, br));
        assertTrue(((ToolResponseMessage) groups.get(0).messages().getLast()).getResponses().getFirst().responseData().contains("first"));
        assertTrue(((ToolResponseMessage) groups.get(1).messages().getLast()).getResponses().getFirst().responseData().contains("second"));
    }
    @Test void eventRowsNeverEnterModelHistory() {
        AgentMessage event = new AgentMessage(); event.setMessageType("EVENT"); event.setContentJson("{\"status\":\"RUNNING\"}");
        assertTrue(builder.groups(List.of(event)).isEmpty());
    }
    @Test void oversizedRecentToolResultsAreCompactedWithCompletePairsAndReasoning() {
        var snapshot = AgentTestSupport.snapshot(List.of(), "NORMAL");
        snapshot.config().getMemory().setContextTokens(8000); snapshot.config().getMemory().setRecentMessages(10);
        snapshot.config().getModel().setMaxTokens(1000);
        var run = AgentTestSupport.run(snapshot, json);
        var session = new com.astra.freyja.entity.AgentSession(); session.setId(run.getSessionId()); session.setCoveredThroughSeq(0);
        var store = mock(com.astra.freyja.agent.runtime.AgentStore.class);
        var memory = mock(com.astra.freyja.agent.memory.AgentMemoryService.class);
        var summarizer = mock(com.astra.freyja.agent.memory.AgentMemorySummarizer.class);
        when(store.snapshot(run)).thenReturn(snapshot); when(store.session(session.getId())).thenReturn(session);
        var plan = new com.astra.freyja.agent.model.AgentTaskPlan("仍需保存分镜", List.of(
                new com.astra.freyja.agent.model.AgentTaskPlan.Item("save", "保存分镜", com.astra.freyja.agent.model.AgentTaskPlan.Status.IN_PROGRESS)));
        when(store.latestTaskPlan(run.getId())).thenReturn(plan);
        var assistant = row(2, "ASSISTANT", new MessageBody("", List.of(new ToolCall("a", "read", "{}")), Map.of("reasoningContent", "r".repeat(3600)), null, null, 10L), null);
        var result = row(3, "TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "read", ToolResult.success("x".repeat(3600)), 10L), "a");
        var latest = row(4, "ASSISTANT", new MessageBody("继续", List.of(), Map.of("reasoningContent", "latest reasoning"), null, null, 11L), null);
        when(store.modelHistory(session.getId(), 0)).thenReturn(List.of(row(1, "USER", MessageBody.text("目标"), null), assistant, result, latest));
        var summary = new ModelReply("已完成查询，继续按目标处理。", List.of(), Map.of(), 1, 1, null);
        when(summarizer.summarize(eq(snapshot), any(), anyString(), anyLong())).thenReturn(summary);
        doAnswer(i -> { session.setSummary(summary.content()); session.setCoveredThroughSeq(i.getArgument(4)); return null; })
                .when(store).saveSummary(anyLong(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyLong());
        var compacting = new AgentContextBuilder(store, json, memory, summarizer, null, null, List.of());
        var messages = compacting.build(run, "test-owner", 1);
        assertEquals(3, session.getCoveredThroughSeq(), "assistant and result must be summarized together");
        assertTrue(messages.stream().anyMatch(m -> m.getText() != null && m.getText().contains(summary.content())));
        assertTrue(messages.stream().anyMatch(m -> m instanceof SystemMessage && m.getText().contains("仍需保存分镜")));
        assertEquals("latest reasoning", messages.getLast().getMetadata().get("reasoningContent"));
        verify(summarizer).summarize(eq(snapshot), any(), contains("TOOL_RESULTS"), anyLong());
    }
}
