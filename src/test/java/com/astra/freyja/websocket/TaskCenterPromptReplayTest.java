package com.astra.freyja.websocket;

import com.astra.freyja.dto.script.AiTaskDTO;
import com.astra.freyja.service.AiTaskService;
import com.astra.freyja.service.ShotPromptEventStore;
import com.astra.freyja.service.TaskCenterService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskCenterPromptReplayTest {
    @Test
    void genericAiSubscriptionReplaysWorkerEvents() throws Exception {
        TaskCenterService taskCenter = mock(TaskCenterService.class);
        AiTaskService tasks = mock(AiTaskService.class);
        ShotPromptEventStore journal = mock(ShotPromptEventStore.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(tasks.getTaskById(456L)).thenReturn(AiTaskDTO.builder().id(456L).taskType("CHAPTER_DECOMPOSE").build());
        when(journal.after("456", 0)).thenReturn(List.of(
                new ShotPromptEventStore.Event("456", 1, "channel_chunk", "{\"channel\":\"PLANNER\",\"chunk\":\"分段完成\"}"),
                new ShotPromptEventStore.Event("456", 2, "worker_status", "{\"channel\":\"SEG001\",\"status\":\"RUNNING\"}")));
        List<String> sent = new ArrayList<>();
        doAnswer(call -> { sent.add(((TextMessage) call.getArgument(0)).getPayload()); return null; })
                .when(session).sendMessage(any(TextMessage.class));
        TaskCenterWebSocketHandler handler = new TaskCenterWebSocketHandler(new ObjectMapper(), taskCenter, tasks, journal);
        handler.handleTextMessage(session, new TextMessage("{\"event\":\"AI_SUBSCRIBE\",\"taskId\":\"456\",\"afterSeq\":0}"));
        assertEquals(2, sent.size());
        assertEquals("AI_EVENT", new ObjectMapper().readTree(sent.get(0)).path("event").asText());
        assertEquals("worker_status", new ObjectMapper().readTree(sent.get(1)).path("data").path("type").asText());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void subscribeReplaysMissingEventsAndContinuesLiveOnSameSession() throws Exception {
        TaskCenterService taskCenter = mock(TaskCenterService.class);
        AiTaskService tasks = mock(AiTaskService.class);
        ShotPromptEventStore journal = mock(ShotPromptEventStore.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(tasks.getTaskById(123L)).thenReturn(AiTaskDTO.builder().id(123L).taskType("SHOT_PROMPT_DERIVE").build());
        when(journal.after("123", 1)).thenReturn(List.of(new ShotPromptEventStore.Event("123", 2, "chunk", "恢复的内容")));
        List<String> sent = new ArrayList<>();
        doAnswer(call -> { sent.add(((TextMessage) call.getArgument(0)).getPayload()); return null; })
                .when(session).sendMessage(any(TextMessage.class));
        Consumer[] listener = new Consumer[1];
        doAnswer(call -> { listener[0] = call.getArgument(0); return null; })
                .when(journal).addListener(any());

        TaskCenterWebSocketHandler handler = new TaskCenterWebSocketHandler(new ObjectMapper(), taskCenter, tasks, journal);
        handler.registerPromptEvents();
        handler.handleTextMessage(session, new TextMessage("{\"event\":\"PROMPT_SUBSCRIBE\",\"taskId\":\"123\",\"afterSeq\":1}"));
        listener[0].accept(new ShotPromptEventStore.Event("123", 3, "done", "[DONE]"));

        assertEquals(2, sent.size());
        assertEquals(2, new ObjectMapper().readTree(sent.get(0)).path("data").path("seq").asInt());
        assertEquals(3, new ObjectMapper().readTree(sent.get(1)).path("data").path("seq").asInt());
    }
}
