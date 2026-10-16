package com.astra.freyja.service;

import com.astra.freyja.event.RenderDispatchReleasedEvent;
import com.astra.freyja.event.RenderQueueChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenderQueueSignalBridgeTest {
    @Test void remoteSignalsWakeTheCorrectListenersWithoutRepublishing() {
        var redis = mock(StringRedisTemplate.class);
        var events = mock(ApplicationEventPublisher.class);
        var bridge = new RenderQueueSignalBridge(redis, events);
        bridge.onMessage(message("other-instance:CHANGED"), null);
        bridge.onMessage(message("other-instance:RELEASED"), null);
        verify(events).publishEvent(new RenderQueueChangedEvent(true));
        verify(events).publishEvent(new RenderDispatchReleasedEvent(true));
        bridge.changed(new RenderQueueChangedEvent(true));
        bridge.released(new RenderDispatchReleasedEvent(true));
        verifyNoInteractions(redis);
    }

    @Test void ownSignalIsNotEchoedBackIntoTheWorker() {
        var redis = mock(StringRedisTemplate.class);
        var events = mock(ApplicationEventPublisher.class);
        var bridge = new RenderQueueSignalBridge(redis, events);
        doAnswer(call -> { bridge.onMessage(message(call.getArgument(1)), null); return 1L; })
                .when(redis).convertAndSend(eq(RenderQueueSignalBridge.CHANNEL), anyString());
        bridge.changed(new RenderQueueChangedEvent());
        verify(redis).convertAndSend(eq(RenderQueueSignalBridge.CHANNEL), anyString());
        verifyNoInteractions(events);
    }

    private static Message message(String body) {
        var message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
