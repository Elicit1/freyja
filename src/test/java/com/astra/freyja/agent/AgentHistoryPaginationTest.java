package com.astra.freyja.agent;

import com.astra.freyja.agent.context.AgentContextBuilder;
import com.astra.freyja.agent.memory.AgentMemoryService;
import com.astra.freyja.agent.memory.AgentMemorySummarizer;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.model.SummaryBlockedException;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentHistoryPaginationTest {
    AgentJson json = new AgentJson();
    AgentStore store = mock(AgentStore.class);
    AgentMemorySummarizer summarizer = mock(AgentMemorySummarizer.class);
    Snapshot snapshot;
    AgentRun run;
    AgentSession session;
    AgentContextBuilder builder;
    List<AgentMessage> history = new ArrayList<>();
    List<String> summaryInputs = new ArrayList<>();
    List<Integer> savedCursors = new ArrayList<>();

    @BeforeEach void setup() {
        snapshot = AgentTestSupport.snapshot(List.of(), "NORMAL");
        snapshot.config().getMemory().setContextTokens(8000);
        snapshot.config().getModel().setMaxTokens(1000);
        snapshot.config().getMemory().getSummary().setMaxTokens(1000);
        snapshot.config().getMemory().getSummary().setRetryMaxTokens(1000);
        snapshot.config().getMemory().getSummary().setMaxRetries(0);
        snapshot.pinnedModel().setParamsJson("{\"capabilities\":{\"contextWindow\":32768,\"maxOutputTokens\":4096}}");
        run = AgentTestSupport.run(snapshot, json);
        session = new AgentSession(); session.setId(run.getSessionId()); session.setCoveredThroughSeq(0);
        when(store.snapshot(run)).thenReturn(snapshot);
        when(store.session(session.getId())).thenReturn(session);
        when(store.run(run.getId())).thenReturn(run);
        when(store.modelHistory(eq(session.getId()), anyInt())).thenAnswer(call -> {
            int afterSeq = call.getArgument(1);
            return history.stream().filter(row -> row.getSeq() > afterSeq)
                    .limit(AgentStore.MODEL_HISTORY_PAGE_SIZE).toList();
        });
        when(summarizer.summarize(eq(snapshot), any(), anyString(), anyLong())).thenAnswer(call -> {
            summaryInputs.add(call.getArgument(2));
            return summary("已保留历史目标和执行结果。", "stop");
        });
        doAnswer(call -> {
            assertEquals(session.getCoveredThroughSeq(), (int) call.getArgument(3));
            int through = call.getArgument(4);
            assertTrue(through > session.getCoveredThroughSeq());
            session.setSummary(((ModelReply) call.getArgument(5)).content());
            session.setCoveredThroughSeq(through); savedCursors.add(through);
            return null;
        }).when(store).saveSummary(anyLong(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyLong());
        builder = new AgentContextBuilder(store, json, mock(AgentMemoryService.class), summarizer, null, null, List.of());
    }

    ModelReply summary(String content, String finishReason) {
        return new ModelReply(content, List.of(), Map.of(), 1, 1, null, finishReason, null);
    }
    void add(String type, MessageBody body, String callId) {
        int seq = history.size() + 1;
        var row = new AgentMessage(); row.setId((long) seq); row.setSeq(seq); row.setMessageType(type);
        row.setContentJson(json.write(body)); row.setToolCallId(callId); history.add(row);
    }
    void users(int count) {
        for (int n = 0; n < count; n++) add("USER", MessageBody.text("message[" + (history.size() + 1) + "]"), null);
    }
    String text(List<Message> messages) {
        return messages.stream().map(Message::getText).collect(Collectors.joining("\n"));
    }

    @ParameterizedTest
    @ValueSource(ints = {5000, 10003})
    void fullPagesAreCompactedWithoutLosingOrDuplicatingAnyHistory(int count) {
        users(count);
        var original = history.stream().map(AgentMessage::getContentJson).toList();
        var context = builder.build(run, "test-owner", 1);
        assertTrue(summaryInputs.size() > 1);
        assertTrue(session.getCoveredThroughSeq() > 0 && session.getCoveredThroughSeq() < count);
        assertTrue(context.getLast() instanceof UserMessage);
        assertTrue(context.getLast().getText().endsWith("message[" + count + "]"));
        String consumed = String.join("\n", summaryInputs) + "\n" + text(context);
        var markers = Pattern.compile("message\\[(\\d+)\\]").matcher(consumed).results()
                .map(match -> Integer.parseInt(match.group(1))).toList();
        assertEquals(java.util.stream.IntStream.rangeClosed(1, count).boxed().toList(), markers);
        assertEquals(original, history.stream().map(AgentMessage::getContentJson).toList());
        verify(store).modelHistory(session.getId(), 5000);
        if (count > 10000) verify(store).modelHistory(session.getId(), 10000);
    }

    @Test void fullPageFitsWithoutSummaryWhenThereIsEnoughContext() {
        users(5000);
        snapshot.config().getMemory().setContextTokens(1000000);
        snapshot.config().getMemory().setSummaryEnabled(false);
        var context = builder.build(run, "test-owner", 1);
        assertEquals(5000, context.stream().filter(UserMessage.class::isInstance).count());
        verify(store).modelHistory(session.getId(), 5000);
        verifyNoInteractions(summarizer);
        assertEquals(0, session.getCoveredThroughSeq());
    }

    @Test void cursorUsesActualSequencesAfterExistingSummaryAndAcrossEventGaps() {
        users(5001);
        history.forEach(row -> row.setSeq(100 + row.getSeq() * 3));
        session.setCoveredThroughSeq(100); session.setSummary("原有摘要");
        snapshot.config().getMemory().setContextTokens(1000000);
        snapshot.config().getMemory().setSummaryEnabled(false);
        var context = builder.build(run, "test-owner", 1);
        assertEquals(5001, context.stream().filter(UserMessage.class::isInstance).count());
        assertTrue(text(context).contains("原有摘要"));
        verify(store).modelHistory(session.getId(), 100);
        verify(store).modelHistory(session.getId(), 15100);
        verifyNoInteractions(summarizer);
    }

    @Test void pageBoundaryKeepsAllCallsLatestResultsAndInterleavedUserInputTogether() {
        users(4998);
        add("ASSISTANT", new MessageBody("", List.of(new ToolCall("a", "read", "{}"), new ToolCall("b", "read", "{}")), Map.of(), null, null, 10L), null);
        add("TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "read", ToolResult.error("CANCELLED_RESULT", "中断"), 10L), "a");
        add("USER", MessageBody.text("INTERLEAVED_USER"), null);
        add("TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "read", ToolResult.success("RECOVERED_RESULT" + "x".repeat(12000)), 10L), "a");
        add("TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "read", ToolResult.success("SECOND_RESULT"), 10L), "b");
        add("ASSISTANT", new MessageBody("继续", List.of(), Map.of(), null, null, 11L), null);
        add("USER", MessageBody.text("LATEST_GOAL"), null);
        var context = builder.build(run, "test-owner", 1);
        assertTrue(summaryInputs.stream().anyMatch(input -> input.contains("RECOVERED_RESULT")
                && input.contains("SECOND_RESULT") && input.contains("TOOL_CALLS") && input.contains("INTERLEAVED_USER")));
        assertFalse(String.join("\n", summaryInputs).contains("CANCELLED_RESULT"));
        assertTrue(savedCursors.stream().noneMatch(seq -> seq >= 4999 && seq < 5003));
        assertEquals(5003, session.getCoveredThroughSeq());
        assertTrue(context.getLast().getText().endsWith("LATEST_GOAL"));
    }

    @Test void aToolTurnSpanningAnEntirePageWaitsForLaterResultsBeforeGrouping() {
        add("ASSISTANT", new MessageBody("", List.of(new ToolCall("a", "read", "{}")), Map.of(), null, null, 10L), null);
        for (int i = 0; i < 4999; i++)
            add("TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "read", ToolResult.success("obsolete"), 10L), "a");
        add("TOOL_RESULT", new MessageBody(null, List.of(), Map.of(), "read", ToolResult.success("LATEST_RESULT"), 10L), "a");
        add("ASSISTANT", new MessageBody("继续", List.of(), Map.of(), null, null, 11L), null);
        var context = builder.build(run, "test-owner", 1);
        var results = context.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast).toList();
        assertEquals(1, results.size());
        assertTrue(results.getFirst().getResponses().getFirst().responseData().contains("LATEST_RESULT"));
        verifyNoInteractions(summarizer);
        verify(store).modelHistory(session.getId(), 5000);
    }

    @Test void laterPageSummaryFailureKeepsCompletedSummariesAndAllRawHistory() {
        users(5000);
        add("USER", MessageBody.text("FAIL_BATCH" + "x".repeat(12000)), null);
        add("USER", MessageBody.text("LATEST_GOAL"), null);
        var original = history.stream().map(AgentMessage::getContentJson).toList();
        when(summarizer.summarize(eq(snapshot), any(), anyString(), anyLong())).thenAnswer(call -> {
            String input = call.getArgument(2);
            return input.contains("FAIL_BATCH") ? summary("半截", "length") : summary("已保留历史目标和执行结果。", "stop");
        });
        var failure = assertThrows(SummaryBlockedException.class, () -> builder.build(run, "test-owner", 1));
        assertEquals("TRUNCATED", failure.code());
        assertFalse(savedCursors.isEmpty());
        assertEquals(savedCursors.getLast(), session.getCoveredThroughSeq());
        assertTrue(session.getCoveredThroughSeq() < 5001);
        assertEquals("已保留历史目标和执行结果。", session.getSummary());
        assertEquals(original, history.stream().map(AgentMessage::getContentJson).toList());
        int resumeFrom = session.getCoveredThroughSeq();
        when(summarizer.summarize(eq(snapshot), any(), anyString(), anyLong()))
                .thenReturn(summary("已保留历史目标和执行结果。", "stop"));
        var resumed = builder.build(run, "test-owner", 1);
        verify(store).modelHistory(session.getId(), resumeFrom);
        assertEquals(5001, session.getCoveredThroughSeq());
        assertTrue(resumed.getLast().getText().endsWith("LATEST_GOAL"));
        assertEquals(original, history.stream().map(AgentMessage::getContentJson).toList());
    }
}
