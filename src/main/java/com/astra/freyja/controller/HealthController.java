package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final RedisTemplate<String, Object> redisTemplate;

    public HealthController(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping
    public R<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            result.put("redis", pong);
        } catch (Exception e) {
            result.put("redis", "DOWN: " + e.getMessage());
        }
        return R.ok(result);
    }
}