package com.astra.freyja.service;

import com.astra.freyja.dto.script.PlannerDecomposeResultVO;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.dto.script.ScriptDecomposeResultVO;
import com.astra.freyja.dto.script.SegmentShotResult;
import com.astra.freyja.entity.AiTask;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Redis source of truth for uncommitted chapter decomposition data. */
@Service
@RequiredArgsConstructor
public class ScriptDecomposeDraftStore {
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final String PREFIX = "script:decompose:draft:";
    private static final String INDEX = "script:decompose:task:index";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public void saveRequest(Long taskId, ScriptDecomposeRequestDTO request) {
        put(taskId, "request", request);
    }

    public ScriptDecomposeRequestDTO getRequest(Long taskId) {
        return get(taskId, "request", ScriptDecomposeRequestDTO.class);
    }

    public void savePlanner(Long taskId, PlannerDecomposeResultVO planner) {
        put(taskId, "planner", planner);
    }

    public void saveWorker(Long taskId, SegmentShotResult worker) {
        if (worker != null && worker.getSegmentId() != null) {
            put(taskId, "worker:" + worker.getSegmentId(), worker);
        }
    }

    public void saveResult(Long taskId, ScriptDecomposeResultVO result) {
        put(taskId, "result", result);
    }

    public ScriptDecomposeResultVO getResult(Long taskId) {
        return get(taskId, "result", ScriptDecomposeResultVO.class);
    }

    public void saveTask(AiTask task) {
        if (task == null || task.getId() == null) return;
        put(task.getId(), "task", task);
        redis.opsForZSet().add(INDEX, String.valueOf(task.getId()),
                task.getCreateTime() != null
                        ? task.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        : System.currentTimeMillis());
    }

    public AiTask getTask(Long taskId) {
        return get(taskId, "task", AiTask.class);
    }

    public List<AiTask> listTasks() {
        var ids = redis.opsForZSet().reverseRange(INDEX, 0, -1);
        if (ids == null || ids.isEmpty()) return List.of();
        List<AiTask> tasks = new ArrayList<>();
        for (String id : ids) {
            AiTask task = getTask(Long.valueOf(id));
            if (task == null) redis.opsForZSet().remove(INDEX, id);
            else tasks.add(task);
        }
        return tasks;
    }

    public void delete(Long taskId) {
        if (taskId != null) {
            for (AiTask child : listTasks()) {
                if (taskId.equals(child.getParentTaskId())) {
                    redis.delete(key(child.getId()));
                    redis.opsForZSet().remove(INDEX, String.valueOf(child.getId()));
                }
            }
            redis.delete(key(taskId));
            redis.opsForZSet().remove(INDEX, String.valueOf(taskId));
        }
    }

    private void put(Long taskId, String field, Object value) {
        if (taskId == null || value == null) return;
        String key = key(taskId);
        redis.opsForHash().put(key, field, objectMapper.writeValueAsString(value));
        redis.expire(key, RETENTION);
    }

    private <T> T get(Long taskId, String field, Class<T> type) {
        if (taskId == null) return null;
        Object value = redis.opsForHash().get(key(taskId), field);
        return value instanceof String json ? objectMapper.readValue(json, type) : null;
    }

    private String key(Long taskId) {
        return PREFIX + taskId;
    }
}
