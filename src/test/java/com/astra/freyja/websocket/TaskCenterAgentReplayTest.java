package com.astra.freyja.websocket;

import com.astra.freyja.agent.event.AgentEventPublisher;
import com.astra.freyja.agent.model.AgentProtocol.Event;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.entity.AgentSession;
import com.astra.freyja.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskCenterAgentReplayTest {
    @Test void reconnectSendsCumulativePreviewAfterDurableReplayWithoutAllocatingMessageSequence() throws Exception {
        var store = mock(AgentStore.class);
        var session = new AgentSession(); session.setNextSeq(3); when(store.session(1L)).thenReturn(session);
        when(store.after(1L, 0)).thenReturn(List.of(message(1L, 1), message(1L, 2)));
        var runs = mock(com.astra.freyja.dao.AgentRunMapper.class);
        var run = new com.astra.freyja.entity.AgentRun(); run.setId(9L); run.setSessionId(1L); run.setStatus("RUNNING");
        run.setLeaseOwner("owner"); run.setLeaseEpoch(1); run.setConstraintVersion(0); run.setLeaseUntil(java.time.LocalDateTime.now().plusSeconds(30));
        when(runs.selectById(9L)).thenReturn(run);
        var events = new AgentEventPublisher(mock(ApplicationEventPublisher.class));
        var streams = new com.astra.freyja.agent.event.AgentStreamPublisher(runs, events);
        var handler = new TaskCenterWebSocketHandler(new ObjectMapper(), mock(TaskCenterService.class), mock(AiTaskService.class), mock(ShotPromptEventStore.class));
        handler.registerAgentEvents(store, events, streams);
        var step = new com.astra.freyja.entity.AgentStep(); step.setId(8L); step.setConstraintVersion(0);
        var socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        var sent = new java.util.ArrayList<String>();
        doAnswer(call -> { sent.add(((TextMessage) call.getArgument(0)).getPayload()); return null; }).when(socket).sendMessage(any(TextMessage.class));
        try (var channel = streams.open(9L, 1L, "owner", 1, step)) {
            channel.accept("灯塔");
            handler.handleTextMessage(socket, new TextMessage("{\"event\":\"AGENT_SUBSCRIBE\",\"sessionId\":\"1\",\"afterSeq\":0}"));
            var mapper = new ObjectMapper();
            assertEquals(List.of("AGENT_EVENT", "AGENT_EVENT", "AGENT_STREAM"), sent.stream().map(text -> mapper.readTree(text).path("event").asText()).toList());
            assertEquals("灯塔", mapper.readTree(sent.getLast()).path("data").path("text").asText());
            assertFalse(mapper.readTree(sent.getLast()).path("data").has("seq"));
            assertEquals(3, session.getNextSeq());
        }
    }
    @Test void replayUsesDurableHighWaterThenSendsLiveEventsAndUnsubscribeStopsThem() throws Exception {
        Long id = 2032095628944588801L;
        AgentStore store = mock(AgentStore.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        AgentSession session = new AgentSession(); session.setNextSeq(4);
        when(store.session(id)).thenReturn(session);
        List<String> sent = new CopyOnWriteArrayList<>();
        doAnswer(call -> { sent.add(((TextMessage) call.getArgument(0)).getPayload()); return null; }).when(socket).sendMessage(any(TextMessage.class));
        AgentEventPublisher events = new AgentEventPublisher(mock(ApplicationEventPublisher.class));
        TaskCenterWebSocketHandler handler = new TaskCenterWebSocketHandler(new ObjectMapper(), mock(TaskCenterService.class), mock(AiTaskService.class), mock(ShotPromptEventStore.class));
        handler.registerAgentEvents(store, events, new com.astra.freyja.agent.event.AgentStreamPublisher(
                mock(com.astra.freyja.dao.AgentRunMapper.class), events));
        CountDownLatch replayStarted = new CountDownLatch(1), liveAttempted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            when(store.after(id, 1)).thenAnswer(call -> {
                replayStarted.countDown(); assertTrue(liveAttempted.await(2, TimeUnit.SECONDS));
                return List.of(message(id, 2), message(id, 3), message(id, 4));
            });
            Future<?> live = executor.submit(() -> {
                try { assertTrue(replayStarted.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                liveAttempted.countDown();
                events.publishAfterCommit(new Event(id, 9L, 4, "RUN_COMPLETED", message(id, 4)), false);
            });
            handler.handleTextMessage(socket, new TextMessage("{\"event\":\"AGENT_SUBSCRIBE\",\"sessionId\":\"" + id + "\",\"afterSeq\":1}"));
            live.get(2, TimeUnit.SECONDS);
        }
        assertEquals(3, sent.size());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(List.of(2, 3, 4), sent.stream().map(text -> mapper.readTree(text).path("data").path("seq").asInt()).toList());
        handler.handleTextMessage(socket, new TextMessage("{\"event\":\"AGENT_UNSUBSCRIBE\",\"sessionId\":\"" + id + "\"}"));
        events.publishAfterCommit(new Event(id, 9L, 5, "MESSAGE_SAVED", message(id, 5)), false);
        assertEquals(3, sent.size());
    }
    private AgentStore.MessageView message(Long id, int seq) {
        return new AgentStore.MessageView(10L + seq, id, 9L, seq, "USER", null, null, "test", null);
    }
}
