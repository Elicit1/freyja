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
        handler.registerAgentEvents(store, events);
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
