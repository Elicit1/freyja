package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.Callable;

/** Runs an AI request independently of the HTTP connection and journals its bus events. */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiEventTaskService {
    private final AiTaskService taskService;
    private final ShotPromptEventStore events;
    private final ObjectMapper objectMapper;

    public String start(AiTaskType type, Long dramaId, String targetId, String modelCode,
                        Object input, Callable<?> work) {
        return start(type, dramaId, targetId, modelCode, input, work, null, false);
    }

    public String startWorkerRetry(Long parentTaskId, String modelCode, Object input, Callable<?> work) {
        var parent = taskService.getTaskById(parentTaskId);
        if (parent == null || !AiTaskType.CHAPTER_DECOMPOSE.name().equals(parent.getTaskType())) {
            throw new BizException(404, "拆解任务不存在或 Redis 草稿已过期");
        }
        return start(AiTaskType.WORKER_RETRY, null, String.valueOf(parentTaskId), modelCode,
                input, work, parentTaskId, true);
    }

    private String start(AiTaskType type, Long dramaId, String targetId, String modelCode,
                         Object input, Callable<?> work, Long parentTaskId, boolean scriptDraft) {
        AiTask task = taskService.createTask(dramaId, null, type, targetId, parentTaskId,
                objectMapper.writeValueAsString(input), modelCode, null);
        if (task == null || task.getId() == null) throw new BizException("AI 任务创建失败");
        if (taskService.getTaskById(task.getId()) == null) throw new BizException("AI 任务持久化失败");
        String id = String.valueOf(task.getId());
        try {
            append(id, "task_created", id, scriptDraft);
        } catch (Exception e) {
            taskService.markFailed(task.getId(), "事件缓存写入失败");
            throw new BizException("AI 事件缓存写入失败");
        }
        Thread.ofVirtual().start(() -> {
            try {
                taskService.markRunning(task.getId());
                append(id, "stage", "⚡ 正在执行 AI 推理...\n", scriptDraft);
                Object result = work.call();
                String json = objectMapper.writeValueAsString(result);
                if (type == AiTaskType.CHARACTER_PROMPT_DERIVE || type == AiTaskType.LOOK_PROMPT_DERIVE
                        || type == AiTaskType.SCENE_PROMPT_DERIVE || type == AiTaskType.PROP_PROMPT_DERIVE) {
                    append(id, "chunk", json, scriptDraft);
                }
                append(id, "result", json, scriptDraft);
                taskService.markSuccess(task.getId(), json, 0);
            } catch (Exception e) {
                log.error("[AiEventTask] 执行失败: taskId={}", id, e);
                String message = e.getMessage() == null ? "AI 任务执行失败" : e.getMessage();
                try { append(id, "error", message, scriptDraft); } catch (Exception ignored) { }
                taskService.markFailed(task.getId(), message);
            } finally {
                try { append(id, "done", "[DONE]", scriptDraft); } catch (Exception ignored) { }
            }
        });
        return id;
    }

    private void append(String taskId, String type, String data, boolean scriptDraft) {
        if (scriptDraft) events.appendScript(taskId, type, data);
        else events.append(taskId, type, data);
    }
}
