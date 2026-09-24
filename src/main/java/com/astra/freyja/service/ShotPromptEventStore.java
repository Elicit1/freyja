package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Shared AI task event journal. The legacy class/key names preserve existing prompt task replay. */
@Service
@RequiredArgsConstructor
public class ShotPromptEventStore {
    private static final Duration RETENTION = Duration.ofHours(24);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final Object[] taskLocks = createLocks();

    private static Object[] createLocks() {
        Object[] locks = new Object[256];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    public record Event(String taskId, long seq, String type, String data) {}
    public record Payload(String type, String data) {}

    public Event append(String taskId, String type, String data) {
        return append(taskId, type, data, RETENTION);
    }

    public Event appendScript(String taskId, String type, String data) {
        return append(taskId, type, data, Duration.ofDays(7));
    }

    public void delete(String taskId) {
        redis.delete(key(taskId));
    }

    private Event append(String taskId, String type, String data, Duration retention) {
        synchronized (taskLocks[Math.floorMod(taskId.hashCode(), taskLocks.length)]) {
            String key = key(taskId);
            String payload = objectMapper.writeValueAsString(new Payload(type, data));
            Long seq = redis.opsForList().rightPush(key, payload);
            if (seq == null) throw new BizException("AI 事件写入失败");
            redis.expire(key, retention);
            Event event = new Event(taskId, seq, type, data);
            for (Consumer<Event> listener : listeners) {
                try { listener.accept(event); } catch (Exception ignored) { /* journal remains authoritative */ }
            }
            return event;
        }
    }

    public List<Event> after(String taskId, long seq) {
        List<String> values = redis.opsForList().range(key(taskId), Math.max(0, seq), -1);
        if (values == null || values.isEmpty()) return List.of();
        List<Event> events = new ArrayList<>(values.size());
        long next = Math.max(0, seq);
        for (String value : values) {
            Payload payload = objectMapper.readValue(value, Payload.class);
            events.add(new Event(taskId, ++next, payload.type(), payload.data()));
        }
        return events;
    }

    public void addListener(Consumer<Event> listener) { listeners.add(listener); }

    private String key(String taskId) { return "shot:prompt:events:" + taskId; }
}
