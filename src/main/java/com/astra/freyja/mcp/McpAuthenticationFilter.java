package com.astra.freyja.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.stream.Collectors;

public final class McpAuthenticationFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    static final int MAX_IMAGE_REQUEST_BYTES = 16 * 1024 * 1024;
    private static final com.fasterxml.jackson.databind.ObjectMapper REQUEST_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final McpProperties properties;
    private final McpRuntimeControl control;

    public McpAuthenticationFilter(McpProperties properties) {
        this(properties, null);
    }
    public McpAuthenticationFilter(McpProperties properties, McpRuntimeControl control) {
        this.properties = properties; this.control = control;
        if (control == null && properties.isEnabled() && !McpRuntimeControl.validToken(properties.getToken()))
            throw new IllegalStateException("开启 MCP 必须配置至少 24 字符的 FREYJA_MCP_TOKEN");
        properties.getAllowedProjectIds().stream().filter(s -> !s.isBlank()).forEach(McpScope::id);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.equals("/mcp") && !path.startsWith("/mcp/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        McpRuntimeSettings settings = control == null ? null : control.current();
        boolean enabled = settings == null ? properties.isEnabled() : settings.isEnabled();
        if (!enabled) { reject(response, 404, "MCP_DISABLED"); return; }
        String token = settings == null ? properties.getToken() : control.token(settings);
        if (!McpRuntimeControl.validToken(token)) { reject(response, 503, "MCP_TOKEN_NOT_CONFIGURED"); return; }
        byte[] credential = token.getBytes(StandardCharsets.UTF_8);
        Set<String> origins = settings == null ? properties.getAllowedOrigins() : settings.getAllowedOrigins();
        Set<Long> projects = (settings == null ? properties.getAllowedProjectIds() : settings.getAllowedProjectIds()).stream().filter(s -> !s.isBlank())
                .map(McpScope::id).collect(Collectors.toUnmodifiableSet());
        McpPrincipal principal = settings == null ? new McpPrincipal(properties.isWriteEnabled(), properties.isAllowAllProjects(), properties.isAllowCreateProjects(), projects)
                : new McpPrincipal(settings.isWriteEnabled(), settings.isAllowAllProjects(), settings.isAllowCreateProjects(), projects);
        String origin = request.getHeader("Origin");
        if (origin != null && !origins.contains(origin)) { reject(response, 403, "MCP_ORIGIN_DENIED"); return; }
        if (origin != null) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.addHeader("Vary", "Origin");
            response.setHeader("Access-Control-Expose-Headers", "MCP-Protocol-Version");
        }
        if ("OPTIONS".equals(request.getMethod()) && request.getHeader("Access-Control-Request-Method") != null) {
            if (origin == null || !"POST".equals(request.getHeader("Access-Control-Request-Method"))) {
                reject(response, 403, "MCP_PREFLIGHT_DENIED"); return;
            }
            response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept, MCP-Protocol-Version, Mcp-Method, Mcp-Name");
            response.setStatus(204); return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || !MessageDigest.isEqual(credential, authorization.substring(7).getBytes(StandardCharsets.UTF_8))) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"freyja-mcp\"");
            reject(response, 401, "MCP_UNAUTHORIZED"); return;
        }
        if (request.getContentLengthLong() > MAX_IMAGE_REQUEST_BYTES) { reject(response, 413, "MCP_REQUEST_TOO_LARGE"); return; }
        byte[] body = request.getInputStream().readNBytes(MAX_IMAGE_REQUEST_BYTES + 1);
        if (body.length > MAX_IMAGE_REQUEST_BYTES || (body.length > MAX_BODY_BYTES && !imageUpload(body))) {
            reject(response, 413, "MCP_REQUEST_TOO_LARGE"); return;
        }
        request.setAttribute(McpPrincipal.CONTEXT_KEY, principal);
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private static boolean imageUpload(byte[] body) {
        try {
            var root = REQUEST_JSON.readTree(body);
            return root != null && "tools/call".equals(root.path("method").asText())
                    && McpImageUploads.TOOL_NAME.equals(root.path("params").path("name").asText());
        } catch (IOException e) { return false; }
    }

    /** Bounded buffering also covers chunked requests; the MCP transport is synchronous. */
    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final ServletInputStream stream;
        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request); this.body = body;
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            stream = new ServletInputStream() {
                @Override public int read() { return source.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return source.read(bytes, offset, length); }
                @Override public boolean isFinished() { return source.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("MCP uses synchronous request bodies"); }
            };
        }
        @Override public ServletInputStream getInputStream() { return stream; }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)); }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
