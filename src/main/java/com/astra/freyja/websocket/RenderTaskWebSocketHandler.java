package com.astra.freyja.websocket;

import com.astra.freyja.dto.render.RenderTaskWsMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Compatibility adapter for render services; the task center owns the only WebSocket endpoint. */
@Component
public class RenderTaskWebSocketHandler {
    @Autowired
    @Lazy
    private TaskCenterWebSocketHandler taskCenterHandler;

    public void broadcast(RenderTaskWsMessage message) {
        taskCenterHandler.broadcastRenderEvent(message);
    }
}
