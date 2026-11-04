package com.astra.freyja.auth;

import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Recheck session revocation/expiry on both incoming messages and every outgoing event. */
public final class AuthenticatedWebSocketHandler extends WebSocketHandlerDecorator {
    private final AuthService auth;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public AuthenticatedWebSocketHandler(WebSocketHandler delegate, AuthService auth) { super(delegate); this.auth = auth; }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSession guarded = new WebSocketSessionDecorator(session) {
            @Override public void sendMessage(WebSocketMessage<?> message) throws IOException {
                if (!authorized(this)) throw new IOException("登录已失效");
                super.sendMessage(message);
            }
        };
        if (!authorized(guarded)) return;
        sessions.put(session.getId(), guarded);
        super.afterConnectionEstablished(guarded);
    }

    @Override public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        WebSocketSession guarded = sessions.get(session.getId());
        if (guarded != null && authorized(guarded)) super.handleMessage(guarded, message);
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        WebSocketSession guarded = sessions.remove(session.getId());
        if (guarded != null) super.afterConnectionClosed(guarded, status);
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        super.handleTransportError(sessions.getOrDefault(session.getId(), session), exception);
    }

    private boolean authorized(WebSocketSession session) throws IOException {
        Object value = session.getAttributes().get(LoginPrincipal.ATTRIBUTE);
        try {
            if (value instanceof LoginPrincipal principal && auth.isActive(principal.sessionKey())) return true;
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Fail closed when the session store is unavailable.
        }
        session.close(new CloseStatus(4401, "登录已失效"));
        return false;
    }
}
