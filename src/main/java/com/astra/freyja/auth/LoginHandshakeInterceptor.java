package com.astra.freyja.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** Only carries the principal already authenticated by the servlet filter into the socket. */
public final class LoginHandshakeInterceptor implements HandshakeInterceptor {
    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servlet) {
            Object principal = servlet.getServletRequest().getAttribute(LoginPrincipal.ATTRIBUTE);
            if (principal instanceof LoginPrincipal) {
                attributes.put(LoginPrincipal.ATTRIBUTE, principal);
                return true;
            }
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {}
}
