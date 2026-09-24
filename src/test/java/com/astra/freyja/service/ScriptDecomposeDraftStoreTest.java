package com.astra.freyja.service;

import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.entity.AiTask;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScriptDecomposeDraftStoreTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void requestAndTaskRoundTripWithSevenDayExpiry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations hashes = mock(HashOperations.class);
        ZSetOperations zset = mock(ZSetOperations.class);
        Map<String, Object> fields = new HashMap<>();
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForZSet()).thenReturn(zset);
        doAnswer(call -> {
            fields.put(call.getArgument(1), call.getArgument(2));
            return null;
        }).when(hashes).put(any(), any(), any());
        when(hashes.get(any(), any())).thenAnswer(call -> fields.get(call.getArgument(1)));

        ScriptDecomposeDraftStore store = new ScriptDecomposeDraftStore(redis, new ObjectMapper());
        ScriptDecomposeRequestDTO request = new ScriptDecomposeRequestDTO();
        request.setRawText("关闭窗口后仍需恢复的剧本原文");
        store.saveRequest(42L, request);

        AiTask task = new AiTask();
        task.setId(42L);
        task.setStatus("RUNNING");
        task.setCreateTime(LocalDateTime.now());
        store.saveTask(task);

        assertEquals(request.getRawText(), store.getRequest(42L).getRawText());
        assertEquals("RUNNING", store.getTask(42L).getStatus());
        verify(redis, atLeast(2)).expire("script:decompose:draft:42", Duration.ofDays(7));
    }
}
