package com.astra.freyja.config;

import com.astra.freyja.websocket.TaskCenterWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置中心。
 * 开启原生 WebSocket 支持，统一通过任务中心端点推送任务和提示词事件。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TaskCenterWebSocketHandler taskCenterWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(taskCenterWebSocketHandler, "/ws/task-center", "/api/ws/task-center")
                .setAllowedOrigins("*");
    }
}
