package com.astra.freyja.websocket;

import com.astra.freyja.dto.task.TaskCenterItemVO;
import com.astra.freyja.dto.task.TaskCenterWsMessage;
import com.astra.freyja.dto.render.RenderTaskWsMessage;
import com.astra.freyja.event.TaskStatusChangedEvent;
import com.astra.freyja.service.TaskCenterService;
import com.astra.freyja.service.AiTaskService;
import com.astra.freyja.service.ShotPromptEventStore;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * 全局任务中心 WebSocket 长连接处理器。
 * 负责任务中心首次握手全量活跃快照单播、心跳响应及跨模块任务状态毫秒级实时广播。
 */
@Slf4j
@Component
public class TaskCenterWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final TaskCenterService taskCenterService;
    private final AiTaskService aiTaskService;
    private final ShotPromptEventStore promptEvents;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, Set<String>> promptSubscriptions = new ConcurrentHashMap<>();

    public TaskCenterWebSocketHandler(ObjectMapper objectMapper, @Lazy TaskCenterService taskCenterService,
                                      AiTaskService aiTaskService, ShotPromptEventStore promptEvents) {
        this.objectMapper = objectMapper;
        this.taskCenterService = taskCenterService;
        this.aiTaskService = aiTaskService;
        this.promptEvents = promptEvents;
    }

    @jakarta.annotation.PostConstruct
    public void registerPromptEvents() { promptEvents.addListener(this::broadcastPromptEvent); }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("[TaskCenterWS] 客户端长连接建立成功: sessionId={}, 当前在线数={}", session.getId(), sessions.size());

        // 首次建立连接，立即单播一次全量活跃任务快照
        try {
            List<TaskCenterItemVO> activeTasks = taskCenterService.getActiveTasks();
            TaskCenterWsMessage initMsg = TaskCenterWsMessage.builder()
                    .event("INITIAL_STATE")
                    .timestamp(System.currentTimeMillis())
                    .activeCount(activeTasks.size())
                    .data(activeTasks)
                    .build();
            sendToSession(session, initMsg);
        } catch (Exception e) {
            log.warn("[TaskCenterWS] 单播初始快照失败: sessionId={}, err={}", session.getId(), e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if (payload != null && payload.startsWith("{")) {
            try {
                var request = objectMapper.readTree(payload);
                String event = request.path("event").asText();
                if ("PROMPT_SUBSCRIBE".equals(event) || "AI_SUBSCRIBE".equals(event)) {
                    String taskId = request.path("taskId").asText();
                    long afterSeq = Math.max(0, request.path("afterSeq").asLong());
                    if (!taskId.matches("[0-9]{1,19}")) return;
                    var task = aiTaskService.getTaskById(Long.valueOf(taskId));
                    if (task == null) return;
                    synchronized (session) {
                        promptSubscriptions.computeIfAbsent(session, ignored -> ConcurrentHashMap.newKeySet()).add(taskId);
                        for (var item : promptEvents.after(taskId, afterSeq)) sendPromptEvent(session, item);
                    }
                    return;
                }
                if ("PROMPT_UNSUBSCRIBE".equals(event) || "AI_UNSUBSCRIBE".equals(event)) {
                    Set<String> subscribed = promptSubscriptions.get(session);
                    if (subscribed != null) subscribed.remove(request.path("taskId").asText());
                    return;
                }
            } catch (Exception e) {
                log.warn("[TaskCenterWS] 提示词事件订阅失败: {}", e.getMessage());
                return;
            }
        }
        if ("PING".equalsIgnoreCase(payload) || (payload != null && payload.contains("\"PING\""))) {
            TaskCenterWsMessage pong = TaskCenterWsMessage.builder()
                    .event("PONG")
                    .timestamp(System.currentTimeMillis())
                    .activeCount(sessions.size())
                    .build();
            sendToSession(session, pong);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        promptSubscriptions.remove(session);
        log.debug("[TaskCenterWS] 客户端长连接断开: sessionId={}, code={}, 当前在线数={}",
                session.getId(), status.getCode(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session.getId());
        promptSubscriptions.remove(session);
        log.warn("[TaskCenterWS] 客户端连接异常: sessionId={}, err={}", session.getId(), exception.getMessage());
    }

    /**
     * 监听全局任务状态变更事件并广播到所有在线会话
     */
    @EventListener
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        if (sessions.isEmpty() || event == null) {
            return;
        }
        try {
            TaskCenterItemVO item = taskCenterService.getTask(event.getSourceType(), event.getTaskId());
            if (item != null) {
                TaskCenterWsMessage updateMsg = TaskCenterWsMessage.builder()
                        .event("TASK_UPDATED")
                        .timestamp(System.currentTimeMillis())
                        .data(item)
                        .build();
                broadcast(updateMsg);
            }
        } catch (Exception e) {
            log.warn("[TaskCenterWS] 处理任务状态变更广播失败: taskId={}, err={}", event.getTaskId(), e.getMessage());
        }
    }

    /**
     * 向所有在线客户端广播消息
     */
    public void broadcast(TaskCenterWsMessage wsMessage) {
        if (sessions.isEmpty()) {
            return;
        }
        String text;
        try {
            text = objectMapper.writeValueAsString(wsMessage);
        } catch (Exception e) {
            log.error("[TaskCenterWS] 序列化广播消息失败: {}", e.getMessage());
            return;
        }

        TextMessage textMessage = new TextMessage(text);
        sessions.forEach((id, session) -> {
            if (session.isOpen()) {
                synchronized (session) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        log.warn("[TaskCenterWS] 发送消息给会话异常: sessionId={}, err={}", id, e.getMessage());
                    }
                }
            }
        });
    }

    /** Reuse the task-center connection for detailed render updates. */
    public void broadcastRenderEvent(RenderTaskWsMessage renderMessage) {
        if (sessions.isEmpty()) return;
        String json = objectMapper.writeValueAsString(Map.of("event", "RENDER_EVENT", "data", renderMessage));
        sessions.forEach((id, session) -> {
            synchronized (session) {
                if (!session.isOpen()) return;
                try { session.sendMessage(new TextMessage(json)); }
                catch (IOException e) { log.warn("[TaskCenterWS] 渲染消息发送失败: sessionId={}", id); }
            }
        });
    }

    private void broadcastPromptEvent(ShotPromptEventStore.Event event) {
        promptSubscriptions.forEach((session, taskIds) -> {
            if (!taskIds.contains(event.taskId())) return;
            synchronized (session) {
                try { sendPromptEvent(session, event); }
                catch (IOException e) { promptSubscriptions.remove(session); }
            }
        });
    }

    private void sendPromptEvent(WebSocketSession session, ShotPromptEventStore.Event event) throws IOException {
        if (!session.isOpen()) return;
        String json = objectMapper.writeValueAsString(Map.of("event", "AI_EVENT", "data", event));
        session.sendMessage(new TextMessage(json));
    }

    private void sendToSession(WebSocketSession session, TaskCenterWsMessage wsMessage) {
        if (session != null && session.isOpen()) {
            synchronized (session) {
                try {
                    String json = objectMapper.writeValueAsString(wsMessage);
                    session.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    log.warn("[TaskCenterWS] 单播异常: sessionId={}, err={}", session.getId(), e.getMessage());
                }
            }
        }
    }
}
