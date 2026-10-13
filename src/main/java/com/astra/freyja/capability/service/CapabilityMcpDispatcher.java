package com.astra.freyja.capability.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.mcp.*;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

/** Request-local immutable catalog: SDK tool specifications handle calls, without incremental directory mutation. */
@Component
@Slf4j
@RequiredArgsConstructor
public class CapabilityMcpDispatcher {
    private final CapabilityCatalogService capabilities;
    private final McpToolCatalog catalog;
    private final McpRuntimeControl control;
    private final com.fasterxml.jackson.databind.ObjectMapper input = new com.fasterxml.jackson.databind.ObjectMapper();
    private final tools.jackson.databind.json.JsonMapper protocol = tools.jackson.databind.json.JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    public boolean handle(HttpServletRequest request, HttpServletResponse response, byte[] body, McpPrincipal caller) throws IOException {
        if (!"POST".equals(request.getMethod())) return false;
        Map<String, Object> rpc;
        try { rpc = input.readValue(body, Map.class); } catch (Exception invalid) { return false; }
        String method = rpc.get("method") instanceof String s ? s : "";
        if (!Set.of("initialize", "tools/list", "tools/call", "resources/list", "resources/read").contains(method)) return false;
        Object id = rpc.get("id");
        Map<String, Object> envelope = new LinkedHashMap<>(); envelope.put("jsonrpc", "2.0"); envelope.put("id", id);
        try {
            if (!"2.0".equals(rpc.get("jsonrpc")) || !rpc.containsKey("id") || id instanceof Boolean || id instanceof Map || id instanceof List)
                throw new RpcError(-32600, "JSON-RPC请求格式无效");
            if (rpc.get("params") != null && !(rpc.get("params") instanceof Map)) throw new RpcError(-32602, "参数必须为对象");
            Map<String, Object> params = rpc.get("params") instanceof Map<?, ?> values ? (Map<String, Object>) values : Map.of();
            var release = capabilities.current();
            var settings = control.current();
            var definitions = capabilities.mcpTools(release).stream().filter(t -> control.toolEnabled(settings, t)).map(catalog::specification).toList();
            Object result = switch (method) {
                case "initialize" -> Map.of("protocolVersion", protocolVersion(params),
                        "serverInfo", Map.of("name", "freyja-digital-studio", "version", "1.0.0"),
                        "capabilities", Map.of("tools", Map.of("listChanged", false), "resources", Map.of("subscribe", false, "listChanged", false)),
                        "instructions", release.instructions() + "\n公共应用规则（优先于示例和默认创作建议）：\n" + release.rules());
                case "tools/list" -> Map.of("tools", definitions.stream().map(d -> d.tool()).toList());
                case "tools/call" -> {
                    if (!(params.get("name") instanceof String name) || (params.get("arguments") != null && !(params.get("arguments") instanceof Map)))
                        throw new RpcError(-32602, "工具名及参数格式无效");
                    var definition = definitions.stream().filter(d -> d.tool().name().equals(name)).findFirst()
                            .orElseThrow(() -> new RpcError(-32602, "工具不存在或未启用"));
                    yield definition.callHandler().apply(McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, caller)),
                            new McpSchema.CallToolRequest(name, (Map<String, Object>) params.getOrDefault("arguments", Map.of())));
                }
                case "resources/list" -> Map.of("resources", List.of(Map.of("uri", "freyja://guide", "name", "freyja-creation-guide", "mimeType", "text/plain")));
                case "resources/read" -> {
                    if (!"freyja://guide".equals(params.get("uri"))) throw new RpcError(-32602, "资源不存在");
                    yield Map.of("contents", List.of(Map.of("uri", "freyja://guide", "mimeType", "text/plain", "text",
                            release.instructions() + "\n公共应用规则（优先于示例和默认创作建议）：\n" + release.rules())));
                }
                default -> throw new IllegalStateException();
            };
            envelope.put("result", result);
        } catch (RpcError error) { envelope.put("error", Map.of("code", error.code, "message", error.getMessage())); }
        catch (BizException error) { envelope.put("error", Map.of("code", -32603, "message", error.getMessage())); }
        catch (RuntimeException error) {
            log.error("MCP协议请求执行失败: method={}", method, error);
            envelope.put("error", Map.of("code", -32603, "message", "服务内部错误，请稍后重试"));
        }
        response.setStatus(200); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write(protocol.writeValueAsString(envelope));
        return true;
    }
    private String protocolVersion(Map<String, Object> params) {
        if (!(params.get("protocolVersion") instanceof String version) || version.isBlank())
            throw new RpcError(-32602, "initialize必须提供protocolVersion");
        return Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25").contains(version) ? version : "2025-11-25";
    }
    private static final class RpcError extends RuntimeException {
        final int code;
        RpcError(int code, String message) { super(message); this.code = code; }
    }
}
