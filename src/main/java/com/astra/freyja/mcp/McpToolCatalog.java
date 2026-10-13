package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolCatalog {
    private final McpJson json;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private McpRuntimeControl control;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private McpBusinessSchemas businessSchemas;
    private final DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();

    @SuppressWarnings("unchecked")
    public <I> McpStatelessServerFeatures.SyncToolSpecification specification(McpTool<I> entry) {
        Map<String, Object> generated;
        try { generated = entry.schema() == null ? json.mapper().readValue(JsonSchemaGenerator.generateForType(entry.inputType()), Map.class) : new LinkedHashMap<>(entry.schema()); }
        catch (Exception e) { throw new IllegalStateException("MCP Schema 生成失败: " + entry.name(), e); }
        generated.put("additionalProperties", false);
        Map<String, Object> properties = (Map<String, Object>) generated.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) generated.getOrDefault("required", List.of());
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", properties, required, false,
                (Map<String, Object>) generated.get("$defs"), (Map<String, Object>) generated.get("definitions"));
        boolean read = entry.effect() == McpTool.Effect.READ;
        var tool = McpSchema.Tool.builder().name(entry.name()).description(entry.description()).inputSchema(schema)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(read).destructiveHint(!read)
                        .idempotentHint(true).openWorldHint(false).build()).build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((context, request) -> {
            long start = System.nanoTime();
            try {
                if (control != null) control.requireTool(entry.name(), entry.effect());
                McpPrincipal caller = principal(context);
                if (entry.effect() == McpTool.Effect.WRITE && !caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED");
                Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                if (!properties.keySet().containsAll(arguments.keySet())) throw new BizException(400, "工具参数包含未知字段");
                for (String name : required) if (arguments.get(name) == null) throw new BizException(400, "缺少必填参数: " + name);
                validateBusiness(entry.name(), arguments);
                if (!validator.validate(generated, arguments).valid()) throw new BizException(400, "参数类型不符合 Schema；业务 ID 必须为字符串");
                I input;
                try { input = json.read(arguments, entry.inputType()); }
                catch (IllegalArgumentException e) { throw new BizException(400, "参数类型不符合 Schema；业务 ID 必须为字符串"); }
                Object data = entry.handler().apply(caller, input);
                log.info("[MCP] tool={} effect={} elapsedMs={}", entry.name(), entry.effect(), (System.nanoTime() - start) / 1000000);
                return result(Map.of("ok", true, "data", data == null ? Map.of() : json.value(data)), false);
            } catch (BizException e) {
                log.warn("[MCP] tool={} rejected code={} reason={}", entry.name(), e.getCode(), e.getMessage());
                return result(Map.of("ok", false, "error", Map.of("code", e.getCode(), "message", e.getMessage())), true);
            } catch (RuntimeException e) {
                log.error("[MCP] tool={} failed", entry.name(), e);
                return result(Map.of("ok", false, "error", Map.of("code", 500, "message", "MCP 工具执行失败，请查看服务端日志")), true);
            }
        }).build();
    }

    @SuppressWarnings("unchecked")
    private void validateBusiness(String tool, Map<String, Object> arguments) {
        if (businessSchemas == null) return;
        if (Set.of("batch_create_records", "append_storyboard").contains(tool) && arguments.get("items") instanceof List<?> items) {
            for (int index = 0; index < items.size(); index++) {
                if (items.get(index) instanceof Map<?, ?> item && item.get("type") instanceof String type && item.get("data") instanceof Map<?, ?> data) {
                    McpRecordType recordType;
                    try { recordType = McpRecordType.valueOf(type); }
                    catch (IllegalArgumentException e) { throw new BizException(400, "items[" + index + "].type 无效"); }
                    try { businessSchemas.validate(recordType, (Map<String, Object>) data, true, true); }
                    catch (BizException e) { throw new BizException(e.getCode(), "items[" + index + "]: " + e.getMessage()); }
                }
            }
        }
        for (McpRecordType type : McpRecordType.values()) {
            String name = type.name().toLowerCase(Locale.ROOT);
            if ((tool.equals("create_" + name) || tool.equals("update_" + name)) && arguments.get("data") instanceof Map<?, ?> data)
                businessSchemas.validate(type, (Map<String, Object>) data, false, tool.startsWith("create_"));
            if (tool.equals("batch_update_" + name + "s") && arguments.get("items") instanceof List<?> items) {
                for (int index = 0; index < items.size(); index++) {
                    if (items.get(index) instanceof Map<?, ?> item && item.get("data") instanceof Map<?, ?> data) {
                        try { businessSchemas.validate(type, (Map<String, Object>) data, false, false); }
                        catch (BizException e) { throw new BizException(e.getCode(), "items[" + index + "]: " + e.getMessage()); }
                    }
                }
            }
        }
    }

    public McpSchema.CallToolResult result(Object data, boolean error) {
        return McpSchema.CallToolResult.builder().structuredContent(data).addTextContent(json.write(data)).isError(error).build();
    }
    public static McpPrincipal principal(McpTransportContext context) {
        if (context == null || !(context.get(McpPrincipal.CONTEXT_KEY) instanceof McpPrincipal caller))
            throw new BizException(401, "MCP_UNAUTHORIZED");
        return caller;
    }
}
