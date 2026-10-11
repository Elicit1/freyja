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

class AgentContextBuilderTest {
    AgentJson json = new AgentJson();
    AgentContextBuilder builder = new AgentContextBuilder(null, json, null, null, null, null, List.of());
    AgentMessage row(int seq, String type, MessageBody body, String callId) {
        AgentMessage row = new AgentMessage(); row.setId((long) seq); row.setSeq(seq); row.setMessageType(type);
        row.setToolCallId(callId); row.setContentJson(json.write(body)); return row;
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
}
