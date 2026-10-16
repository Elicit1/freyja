package com.astra.freyja.service;

import com.astra.freyja.event.RenderDispatchReleasedEvent;
import com.astra.freyja.event.RenderQueueChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Redis notifications wake other instances; MySQL remains the durable source of truth. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RenderQueueSignalBridge implements MessageListener {
    public static final String CHANNEL = "freyja:render:queue:signals";
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final String origin = UUID.randomUUID().toString();

    @TransactionalEventListener(fallbackExecution = true)
    public void changed(RenderQueueChangedEvent event) { if (!event.remote()) publish("CHANGED"); }

    @TransactionalEventListener(fallbackExecution = true)
    public void released(RenderDispatchReleasedEvent event) { if (!event.remote()) publish("RELEASED"); }

    private void publish(String signal) {
        try { redis.convertAndSend(CHANNEL, origin + ":" + signal); }
        catch (Exception e) { log.warn("跨实例渲染队列通知失败: {}", e.getMessage()); }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        int separator = payload.indexOf(':');
        if (separator < 0 || origin.equals(payload.substring(0, separator))) return;
        switch (payload.substring(separator + 1)) {
            case "CHANGED" -> events.publishEvent(new RenderQueueChangedEvent(true));
            case "RELEASED" -> events.publishEvent(new RenderDispatchReleasedEvent(true));
            default -> { }
        }
    }
}
