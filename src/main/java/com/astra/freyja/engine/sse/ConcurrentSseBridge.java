package com.astra.freyja.engine.sse;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 线程安全的 SSE 调度网关 (ConcurrentSseBridge)。
 * 专门用于在 Planner AI 与多个 Worker 虚拟线程并发执行大模型流式输出时，
 * 进行微秒级原子锁保护的 Token 片元实时推送，避免长连接 HTTP Chunk 损坏与 IllegalStateException。
 */
@Slf4j
public class ConcurrentSseBridge {

    private final SseEmitter emitter;
    private final BiConsumer<String, String> eventSink;
    private final Object lock = new Object();
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public ConcurrentSseBridge(SseEmitter emitter) {
        this.emitter = emitter;
        this.eventSink = null;
        // 客户端关闭页面、切换路由或网络断开时，SseEmitter 会通过异步生命周期回调通知服务端。
        // 这属于后台任务的正常情况：任务继续执行，但不再尝试向已失效的响应写数据。
        emitter.onError(ex -> markClientDisconnected());
        emitter.onTimeout(this::markClientDisconnected);
        emitter.onCompletion(this::markClientDisconnected);
    }

    /** A task event journal can use the same pipeline without an HTTP response. */
    public ConcurrentSseBridge(BiConsumer<String, String> eventSink) {
        this.emitter = null;
        this.eventSink = eventSink;
    }

    private void send(String name, String data) throws java.io.IOException {
        if (eventSink != null) eventSink.accept(name, data);
        else emitter.send(SseEmitter.event().name(name).data(data));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 发送单条文本片元 (Token 级别或日志级别)
     */
    public void sendChunk(String message) {
        if (completed.get() || StringUtils.isEmpty(message)) {
            return;
        }
        synchronized (lock) {
            if (completed.get()) return;
            try {
                send("chunk", message);
            } catch (Exception e) {
                log.debug("[ConcurrentSseBridge] 客户端断开连接或发送失败: {}", e.getMessage());
                completed.set(true);
            }
        }
    }

    /**
     * 通知客户端后台任务已持久化创建。客户端断开后任务仍由服务端继续执行。
     */
    public void sendTaskCreated(Long taskId, Long shotId) {
        if (completed.get() || taskId == null) {
            return;
        }
        synchronized (lock) {
            if (completed.get()) return;
            try {
                java.util.Map<String, String> payload = new java.util.LinkedHashMap<>();
                payload.put("taskId", String.valueOf(taskId));
                if (shotId != null) payload.put("shotId", String.valueOf(shotId));
                send("task_created", OBJECT_MAPPER.writeValueAsString(payload));
            } catch (Exception e) {
                log.debug("[ConcurrentSseBridge] 发送任务创建事件失败: {}", e.getMessage());
                completed.set(true);
            }
        }
    }

    /**
     * 发送分段列表初始化事件 (event: segments_init)
     */
    public void sendSegmentsInit(Object segments) {
        if (completed.get() || segments == null) {
            return;
        }
        synchronized (lock) {
            if (completed.get()) return;
            try {
                String json = OBJECT_MAPPER.writeValueAsString(segments);
                send("segments_init", json);
            } catch (Exception e) {
                log.debug("[ConcurrentSseBridge] 发送 segments_init 失败: {}", e.getMessage());
                completed.set(true);
            }
        }
    }

    /**
     * 发送已发现并对齐的资产列表事件 (event: assets_discovered)
     */
    public void sendAssetsDiscovered(Object assets) {
        if (completed.get() || assets == null) {
            return;
        }
        synchronized (lock) {
            if (completed.get()) return;
            try {
                String json = OBJECT_MAPPER.writeValueAsString(assets);
                send("assets_discovered", json);
            } catch (Exception e) {
                log.debug("[ConcurrentSseBridge] 发送 assets_discovered 失败: {}", e.getMessage());
                completed.set(true);
            }
        }
    }

    /**
     * 发送指定通道的流式片元事件 (event: channel_chunk)
     */
    public void sendChannelChunk(String channel, String chunk) {
        if (completed.get() || StringUtils.isEmpty(chunk)) {
            return;
        }
        synchronized (lock) {
            if (completed.get()) return;
            try {
                java.util.Map<String, String> payload = java.util.Map.of(
                        "channel", StringUtils.defaultIfBlank(channel, "ALL"),
                        "chunk", chunk
                );
                String json = OBJECT_MAPPER.writeValueAsString(payload);
                send("channel_chunk", json);
            } catch (Exception e) {
                log.debug("[ConcurrentSseBridge] 发送 channel_chunk 失败: {}", e.getMessage());
                completed.set(true);
            }
        }
    }

    /**
     * 发送 Worker 状态变更事件 (event: worker_status)
     */
    public void sendWorkerStatus(String channel, String status, Integer shotsCount, Double duration) {
        if (completed.get() || StringUtils.isEmpty(channel)) {
            return;
        }
        synchronized (lock) {
            if (completed.get()) return;
            try {
                java.util.Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("channel", channel);
                payload.put("status", status);
                if (shotsCount != null) payload.put("shotsCount", shotsCount);
                if (duration != null) payload.put("duration", duration);

                String json = OBJECT_MAPPER.writeValueAsString(payload);
                send("worker_status", json);
            } catch (Exception e) {
                log.debug("[ConcurrentSseBridge] 发送 worker_status 失败: {}", e.getMessage());
                completed.set(true);
            }
        }
    }

    /**
     * 发送结构化 JSON 结果并标明完成
     */
    public void sendResult(String jsonResult) {
        if (completed.get() || StringUtils.isEmpty(jsonResult)) {
            return;
        }
        synchronized (lock) {
            if (completed.get()) return;
            try {
                send("result", jsonResult);
            } catch (Exception e) {
                log.debug("[ConcurrentSseBridge] 发送 Result 失败: {}", e.getMessage());
                completed.set(true);
            }
        }
    }

    /**
     * 发送异常错误事件
     */
    public void sendError(String errorMessage) {
        if (completed.get()) {
            return;
        }
        synchronized (lock) {
            if (completed.get()) return;
            try {
                send("error", StringUtils.defaultIfBlank(errorMessage, "流水线执行异常"));
            } catch (Exception ignored) {
            } finally {
                complete();
            }
        }
    }

    /**
     * 正常结束长连接
     */
    public void complete() {
        if (completed.compareAndSet(false, true)) {
            synchronized (lock) {
                try {
                    send("done", "[DONE]");
                } catch (Exception ignored) {
                } finally {
                    try {
                        if (emitter != null) emitter.complete();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public boolean isCompleted() {
        return completed.get();
    }

    /** Send server-observed Skill use without exposing SKILL.md content. */
    public void sendSkillEvent(String jsonEvent) {
        if (completed.get() || StringUtils.isEmpty(jsonEvent)) return;
        synchronized (lock) {
            if (completed.get()) return;
            try {
                send("skill_event", jsonEvent);
            } catch (Exception e) {
                log.debug("[ConcurrentSseBridge] 发送 skill_event 失败: {}", e.getMessage());
                completed.set(true);
            }
        }
    }

    /** A failed Worker attempt must not leave partial JSON in its live channel. */
    public void sendChannelReset(String channel) {
        if (completed.get() || StringUtils.isBlank(channel)) return;
        synchronized (lock) {
            if (completed.get()) return;
            try {
                send("channel_reset", OBJECT_MAPPER.writeValueAsString(java.util.Map.of("channel", channel)));
            } catch (Exception e) {
                log.debug("[ConcurrentSseBridge] 发送 channel_reset 失败: {}", e.getMessage());
                completed.set(true);
            }
        }
    }

    /**
     * 标记 SSE 客户端连接已经不可用。
     * 不取消后台任务，也不向上抛出异常；最终结果由持久化任务状态负责恢复。
     */
    public void markClientDisconnected() {
        completed.set(true);
    }
}
