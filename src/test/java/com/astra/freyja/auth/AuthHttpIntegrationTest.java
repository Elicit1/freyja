package com.astra.freyja.auth;

import com.astra.freyja.config.WebSocketConfig;
import com.astra.freyja.controller.AuthController;
import com.astra.freyja.websocket.TaskCenterWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = AuthHttpIntegrationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.enabled=false", "freyja.auth.password=test-admin-password-2026", "freyja.auth.token-ttl-seconds=60",
                "logging.file.name=target/auth-http-test.log", "spring.datasource.url=jdbc:h2:mem:auth-http;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
                "spring.sql.init.mode=never"})
class AuthHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean TaskCenterWebSocketHandler taskHandler;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = com.astra.freyja.dao.AdminCredentialMapper.class)
    @Import({AuthConfiguration.class, AuthController.class, WebSocketConfig.class, BusinessEndpoint.class})
    static class App {
        @Bean StringRedisTemplate stringRedisTemplate() { return new AuthTestStore().redis; }
    }

    @BeforeEach void websocketReplies() throws Exception {
        jdbc.update("DELETE FROM sys_admin_credential");
        doAnswer(call -> {
            org.springframework.web.socket.WebSocketSession session = call.getArgument(0);
            session.sendMessage(new org.springframework.web.socket.TextMessage("ready")); return null;
        }).when(taskHandler).afterConnectionEstablished(any());
    }

    @RestController static class BusinessEndpoint {
        @GetMapping("/business") Map<String, String> business() { return Map.of("value", "protected"); }
    }

    HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void passwordChangeRevokesRealHttpAndWebSocketConnections() throws Exception {
        assertEquals(401, request("GET", "/business", null, null).statusCode());
        var response = request("POST", "/auth/login", null, "{\"username\":\"admin\",\"password\":\"test-admin-password-2026\"}");
        assertEquals(200, response.statusCode(), response.body());
        String token = json.readTree(response.body()).path("data").path("token").asText();
        assertEquals(200, request("GET", "/business", token, null).statusCode());
        String ticket = json.readTree(request("POST", "/auth/ws-ticket", token, null).body()).path("data").path("ticket").asText();
        var ready = new CompletableFuture<String>();
        var closed = new CompletableFuture<Integer>();
        var listener = new WebSocket.Listener() {
            @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
            @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                ready.complete(data.toString()); webSocket.request(1); return null;
            }
            @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
                closed.complete(code); return null;
            }
        };
        var socket = http.newWebSocketBuilder().header("Origin", "http://localhost:" + port)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/task-center?ticket=" + ticket), listener).get(10, TimeUnit.SECONDS);
        try {
            assertEquals("ready", ready.get(10, TimeUnit.SECONDS));
            assertEquals(200, request("POST", "/auth/password", token,
                    "{\"oldPassword\":\"test-admin-password-2026\",\"newPassword\":\"my-permanent-password\"}").statusCode());
            assertEquals(401, request("GET", "/business", token, null).statusCode());
            assertEquals(200, request("POST", "/auth/login", null,
                    "{\"username\":\"admin\",\"password\":\"my-permanent-password\"}").statusCode());
            socket.sendText("PING", true).get(10, TimeUnit.SECONDS);
            assertEquals(4401, closed.get(10, TimeUnit.SECONDS));
        } finally { socket.abort(); }
    }

    @Test void crossOriginWebSocketIsRejectedEvenWithAValidTicket() throws Exception {
        var session = auth.login("admin", "test-admin-password-2026", "test-origin");
        String ticket = auth.createWebSocketTicket(auth.authenticate("Bearer " + session.token()));
        var future = http.newWebSocketBuilder().header("Origin", "https://untrusted.example")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/task-center?ticket=" + ticket), new WebSocket.Listener() {});
        var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        var handshake = assertInstanceOf(WebSocketHandshakeException.class, error.getCause());
        assertEquals(403, handshake.getResponse().statusCode());
    }
}
