package com.astra.freyja.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticatedWebSocketHandlerTest {
    @Test void logoutOrExpiryBlocksOutgoingEventsAndIncomingMessagesOnAnExistingSocket() throws Exception {
        var store = new AuthTestStore(); var auth = new AuthService(AuthTestStore.properties(), store.redis);
        var login = auth.login("admin", "test-admin-password-2026", "ip");
        var principal = auth.authenticate("Bearer " + login.token());
        var delegate = mock(WebSocketHandler.class);
        var handler = new AuthenticatedWebSocketHandler(delegate, auth);
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws"); when(session.getAttributes()).thenReturn(Map.of(LoginPrincipal.ATTRIBUTE, principal));
        handler.afterConnectionEstablished(session);
        var captured = ArgumentCaptor.forClass(WebSocketSession.class);
        verify(delegate).afterConnectionEstablished(captured.capture());
        var guarded = captured.getValue();
        guarded.sendMessage(new TextMessage("initial"));
        verify(session).sendMessage(new TextMessage("initial"));
        auth.logout(principal);
        assertThrows(IOException.class, () -> guarded.sendMessage(new TextMessage("secret")));
        verify(session, never()).sendMessage(new TextMessage("secret"));
        handler.handleMessage(session, new TextMessage("PING"));
        verify(delegate, never()).handleMessage(any(), any());
        verify(session, atLeastOnce()).close(new CloseStatus(4401, "登录已失效"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(delegate).afterConnectionClosed(guarded, CloseStatus.NORMAL);
    }

    @Test void anonymousSocketCannotReachTheDelegate() throws Exception {
        var auth = mock(AuthService.class); var delegate = mock(WebSocketHandler.class);
        var session = mock(WebSocketSession.class); when(session.getAttributes()).thenReturn(Map.of());
        new AuthenticatedWebSocketHandler(delegate, auth).afterConnectionEstablished(session);
        verifyNoInteractions(delegate);
        verify(session).close(new CloseStatus(4401, "登录已失效"));
    }
}
