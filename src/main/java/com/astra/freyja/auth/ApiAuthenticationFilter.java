package com.astra.freyja.auth;

import com.astra.freyja.common.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Default-deny boundary for every non-MCP servlet endpoint, including future controllers. */
public final class ApiAuthenticationFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper json;

    public ApiAuthenticationFilter(AuthService auth, ObjectMapper json) { this.auth = auth; this.json = json; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/mcp") || path.startsWith("/mcp/")
                || path.equals("/auth/login") && "POST".equals(request.getMethod());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        LoginPrincipal principal;
        try {
            String path = request.getServletPath();
            boolean websocket = (path.equals("/ws/task-center") || path.equals("/api/ws/task-center"))
                    && "GET".equals(request.getMethod()) && "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
            principal = websocket ? auth.consumeWebSocketTicket(request.getParameter("ticket"))
                    : auth.authenticate(request.getHeader("Authorization"));
        } catch (DataAccessException e) {
            reject(response, 503, "登录验证服务暂不可用"); return;
        }
        if (principal == null) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"freyja-api\"");
            reject(response, 401, "请先登录或重新登录"); return;
        }
        request.setAttribute(LoginPrincipal.ATTRIBUTE, principal);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(json.writeValueAsString(R.fail(status, message)));
    }
}
