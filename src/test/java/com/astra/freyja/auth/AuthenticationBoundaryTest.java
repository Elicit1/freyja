package com.astra.freyja.auth;

import com.astra.freyja.controller.AuthController;
import com.astra.freyja.mcp.*;
import com.astra.freyja.capability.service.CapabilityMcpDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthenticationBoundaryTest {
    static final String MCP_TOKEN = "separate-mcp-access-token-2026";
    final JsonMapper json = JsonMapper.builder().build();
    AuthTestStore store;
    AuthService auth;
    MockMvc mvc;
    McpRuntimeSettings settings;

    @BeforeEach void setup() {
        store = new AuthTestStore(); auth = new AuthService(AuthTestStore.properties(), store.redis, store.credentials);
        var control = mock(McpRuntimeControl.class);
        settings = new McpRuntimeSettings(); settings.setEnabled(true);
        when(control.current()).thenReturn(settings); when(control.token(settings)).thenReturn(MCP_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth), new Endpoints())
                .addFilters(new ApiAuthenticationFilter(auth, json), new McpAuthenticationFilter(control, mock(CapabilityMcpDispatcher.class))).build();
    }

    String login() throws Exception {
        var response = mvc.perform(post("/auth/login").servletPath("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"test-admin-password-2026\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("token").asText();
    }

    MockHttpServletRequestBuilder endpoint(String path, String token) {
        var request = get(path).servletPath(path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    @Test void onlyLoginIsPublicAndMcpTokensCannotAuthorizeBusinessOrSettings() throws Exception {
        for (String path : new String[]{"/business", "/system/mcp", "/health", "/mcp-settings", "/mcpish", "/auth/me", "/future-api"}) {
            mvc.perform(endpoint(path, null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
            mvc.perform(endpoint(path, MCP_TOKEN)).andExpect(status().isUnauthorized());
        }
        mvc.perform(endpoint("/auth/login", null)).andExpect(status().isUnauthorized());
        mvc.perform(endpoint("/business", null).param("token", login())).andExpect(status().isUnauthorized());
    }

    @Test void loginAllowsBusinessAndMcpSettingsButCannotReplaceMcpToken() throws Exception {
        String token = login();
        mvc.perform(endpoint("/business", token)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));
        mvc.perform(endpoint("/system/mcp", token)).andExpect(status().isOk());
        mvc.perform(endpoint("/auth/me", token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("admin"));
        mvc.perform(post("/mcp").servletPath("/mcp").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").servletPath("/mcp").header("Authorization", "Bearer " + MCP_TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mcp").value(true));
        mvc.perform(post("/mcp").servletPath("/mcp")).andExpect(status().isUnauthorized());
        settings.setEnabled(false);
        mvc.perform(post("/mcp").servletPath("/mcp").header("Authorization", "Bearer " + MCP_TOKEN)).andExpect(status().isNotFound());
    }

    @Test void badCredentialsReturnRealHttp401AndLogoutRevokesTheToken() throws Exception {
        mvc.perform(post("/auth/login").servletPath("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.msg").value("用户名或密码错误"));
        String token = login();
        mvc.perform(post("/auth/logout").servletPath("/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(endpoint("/business", token)).andExpect(status().isUnauthorized());
    }

    @Test void redisFailureDoesNotPermitAccess() throws Exception {
        String token = login();
        when(store.redis.opsForValue().get(org.mockito.ArgumentMatchers.anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        mvc.perform(endpoint("/business", token)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
    }

    @Test void websocketHandshakeRequiresAOneUseTicketInsteadOfALoginTokenInTheUrl() throws Exception {
        String token = login();
        var ticketResponse = mvc.perform(post("/auth/ws-ticket").servletPath("/auth/ws-ticket").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse();
        String ticket = json.readTree(ticketResponse.getContentAsString()).path("data").path("ticket").asText();
        mvc.perform(endpoint("/ws/task-center", null).header("Upgrade", "websocket").param("token", token)).andExpect(status().isUnauthorized());
        mvc.perform(endpoint("/ws/task-center", null).header("Upgrade", "websocket").param("ticket", ticket)).andExpect(status().isOk());
        mvc.perform(endpoint("/ws/task-center", null).header("Upgrade", "websocket").param("ticket", ticket)).andExpect(status().isUnauthorized());
        String aliasTicket = auth.createWebSocketTicket(auth.authenticate("Bearer " + token));
        mvc.perform(endpoint("/api/ws/task-center", null).header("Upgrade", "websocket").param("ticket", aliasTicket)).andExpect(status().isOk());
    }

    @Test void passwordChangeRequiresLoginVerifiesOldPasswordAndRevokesAllTokensWithoutChangingMcpAccess() throws Exception {
        String input = "{\"oldPassword\":\"test-admin-password-2026\",\"newPassword\":\"my-permanent-password\"}";
        mvc.perform(post("/auth/password").servletPath("/auth/password").contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/password").servletPath("/auth/password").header("Authorization", "Bearer " + MCP_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isUnauthorized());
        String token = login(); String second = login();
        mvc.perform(post("/auth/password").servletPath("/auth/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"oldPassword\":\"wrong\",\"newPassword\":\"my-permanent-password\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value("原密码不正确"));
        mvc.perform(endpoint("/business", token)).andExpect(status().isOk());
        mvc.perform(post("/auth/password").servletPath("/auth/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isOk());
        mvc.perform(endpoint("/business", token)).andExpect(status().isUnauthorized());
        mvc.perform(endpoint("/business", second)).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").servletPath("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"my-permanent-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty());
        mvc.perform(post("/mcp").servletPath("/mcp").header("Authorization", "Bearer " + MCP_TOKEN)).andExpect(status().isOk());
    }

    @RestController static class Endpoints {
        @GetMapping({"/business", "/system/mcp", "/ws/task-center", "/api/ws/task-center"})
        Map<String, String> business(@RequestAttribute(LoginPrincipal.ATTRIBUTE) LoginPrincipal principal) { return Map.of("username", principal.username()); }
        @PostMapping("/mcp") Map<String, Boolean> mcp(HttpServletRequest request) { return Map.of("mcp", request.getAttribute(McpPrincipal.CONTEXT_KEY) instanceof McpPrincipal); }
    }
}
