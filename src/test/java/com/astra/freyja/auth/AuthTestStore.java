package com.astra.freyja.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic expiring store behind the real session service; no external Redis required. */
class AuthTestStore {
    record Entry(String value, long expiresAt) {}
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final Map<String, Entry> entries = new ConcurrentHashMap<>();
    final AtomicLong time = new AtomicLong();

    @SuppressWarnings("unchecked")
    AuthTestStore() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(call -> {
            entries.put(call.getArgument(0), new Entry(call.getArgument(1), time.get() + ((Duration) call.getArgument(2)).toMillis()));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(call -> read(call.getArgument(0)));
        when(values.getAndDelete(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0); String value = read(key); entries.remove(key); return value;
        });
        when(redis.delete(anyString())).thenAnswer(call -> entries.remove((String) call.getArgument(0)) != null);
        when(redis.execute(any(RedisScript.class), anyList())).thenAnswer(call -> {
            String key = ((List<String>) call.getArgument(1)).getFirst();
            String old = read(key);
            long next = old == null ? 1 : Long.parseLong(old) + 1;
            long expiry = old == null ? time.get() + 60000 : entries.get(key).expiresAt();
            entries.put(key, new Entry(Long.toString(next), expiry)); return next;
        });
    }

    private String read(String key) {
        Entry entry = entries.get(key);
        return entry != null && entry.expiresAt() > time.get() ? entry.value() : null;
    }

    static AuthProperties properties() {
        var properties = new AuthProperties();
        properties.setPassword("test-admin-password-2026");
        properties.setTokenTtlSeconds(60);
        return properties;
    }
}
