package com.astra.freyja.capability.service;

import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.tool.AgentTool;
import com.astra.freyja.agent.profile.AgentProfileRegistry;
import com.astra.freyja.capability.model.CapabilityModels.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.mcp.*;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import java.util.*;

/** The Java inventory describes executable capabilities, never the current public tool names. */
@Component
@RequiredArgsConstructor
public class CapabilityHandlers {
    public record Handler(String key, McpTool<?> business, AgentTool<?> nativeTool, Map<String, Object> schema,
                          Effect effect, RecoveryMode recoveryMode, boolean projectRequired) {}
    private final ObjectProvider<McpToolInventory> business;
    private final ObjectProvider<List<AgentTool<?>>> nativeTools;
    private final ObjectProvider<AgentProfileRegistry> profiles;
    private final McpJson json;
    private final com.astra.freyja.agent.model.AgentJson agentJson;
    private volatile Map<String, Handler> handlers;

    public Map<String, Handler> all() {
        var result = handlers;
        if (result != null) return result;
        synchronized (this) {
            if (handlers != null) return handlers;
            Map<String, Handler> entries = new LinkedHashMap<>();
            for (McpTool<?> tool : business.getObject().registeredHandlers()) {
                String key = Consumer.MCP.name() + ":" + tool.name();
                var schema = schema(tool);
                boolean project = ((List<?>) schema.getOrDefault("required", List.of())).contains("dramaId");
                entries.put(key, new Handler(key, tool, null, schema, Effect.valueOf(tool.effect().name()),
                        tool.effect() == McpTool.Effect.WRITE ? RecoveryMode.TRANSACTIONAL : RecoveryMode.REPLAY_SAFE, project));
            }
            for (AgentTool<?> tool : nativeTools.getObject()) {
                String key = Consumer.INTERNAL_AGENT.name() + ":" + tool.name();
                var schema = parse(tool.descriptor().inputSchema());
                stringIds(schema);
                entries.put(key, new Handler(key, null, tool, schema,
                        tool.effect(), tool.recoveryMode(), tool.projectRequired()));
            }
            handlers = Collections.unmodifiableMap(entries);
            return handlers;
        }
    }
    @SuppressWarnings("unchecked") private void stringIds(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("integer".equals(map.get("type")) && "int64".equals(map.get("format"))) {
                Map<String, Object> schema = (Map<String, Object>) map;
                schema.put("type", "string"); schema.remove("format"); schema.put("pattern", "^[1-9][0-9]{0,18}$");
            }
            new ArrayList<>(map.values()).forEach(this::stringIds);
        } else if (value instanceof List<?> list) list.forEach(this::stringIds);
    }
    public Handler require(String key) {
        Handler value = all().get(key);
        if (value == null) throw new BizException(409, "CAPABILITY_HANDLER_UNAVAILABLE: 未注册的业务处理器");
        return value;
    }
    public List<Binding> initialBindings() {
        List<Binding> result = new ArrayList<>();
        var profile = profiles.getObject().require(AgentProfileRegistry.ASSISTANT_CODE);
        Set<String> nativeNames = new HashSet<>();
        all().values().stream().filter(h -> h.nativeTool() != null).forEach(h -> nativeNames.add(h.nativeTool().name()));
        for (Handler handler : all().values()) {
            String capabilityId = Long.toString(IdWorker.getId()), versionId = Long.toString(IdWorker.getId());
            if (handler.business() != null) {
                var tool = handler.business();
                result.add(binding(capabilityId, versionId, handler, Consumer.MCP, "freyja", tool.name(), tool.description(), handler.schema(), ContextMode.EXPLICIT, false));
                if (!nativeNames.contains(tool.name())) {
                    Map<String, Object> adapted = internalSchema(handler.schema());
                    result.add(binding(capabilityId, versionId, handler, Consumer.INTERNAL_AGENT, profile.code(), tool.name(),
                            tool.description() + " 当前项目与请求幂等标识由服务端注入，无需重复提交。", adapted,
                            tool.name().equals("create_project") ? ContextMode.UNBOUND_PROJECT : ContextMode.SESSION_PROJECT, false));
                }
            } else {
                var tool = handler.nativeTool();
                result.add(binding(capabilityId, versionId, handler, Consumer.INTERNAL_AGENT, profile.code(), tool.name(), tool.description(),
                        handler.schema(), ContextMode.AGENT_RUNTIME, profile.requiredTools().contains(tool.name())));
            }
        }
        return result;
    }
    private Binding binding(String cap, String version, Handler h, Consumer consumer, String code, String name,
                            String description, Map<String, Object> schema, ContextMode mode, boolean required) {
        return new Binding(Long.toString(IdWorker.getId()), cap, version, h.key(), consumer, code, name, description,
                schema, true, required, mode, h.projectRequired(), h.effect(), h.recoveryMode());
    }
    @SuppressWarnings("unchecked") public Map<String, Object> internalSchema(Map<String, Object> source) {
        Map<String, Object> result = parse(json.write(source));
        Map<String, Object> properties = (Map<String, Object>) result.get("properties");
        properties.remove("dramaId"); properties.remove("requestId");
        result.put("required", ((List<String>) result.getOrDefault("required", List.of())).stream()
                .filter(n -> !Set.of("dramaId", "requestId").contains(n)).toList());
        return result;
    }
    public Map<String, Object> schema(McpTool<?> tool) {
        Map<String, Object> schema = tool.schema() == null ? parse(JsonSchemaGenerator.generateForType(tool.inputType())) : parse(json.write(tool.schema()));
        schema.put("additionalProperties", false);
        return schema;
    }
    @SuppressWarnings("unchecked") public Map<String, Object> parse(String text) {
        try { return json.mapper().readValue(text, Map.class); }
        catch (Exception e) { throw new BizException(400, "能力参数 Schema 格式无效"); }
    }
    @SuppressWarnings("unchecked") public Object invokeBusiness(Handler h, McpPrincipal caller, Map<String, Object> args) {
        McpTool<Object> tool = (McpTool<Object>) h.business();
        if (tool.validator() != null) tool.validator().accept(args);
        return tool.handler().apply(caller, json.read(args, tool.inputType()));
    }
    @SuppressWarnings("unchecked") public ToolResult invokeNative(Handler h, Map<String, Object> args, ToolContext context, boolean recover, WaitHandle wait) {
        AgentTool<Object> tool = (AgentTool<Object>) h.nativeTool();
        Object input = agentJson.read(json.write(args), tool.inputType());
        return recover ? tool.reconcile(input, context, wait) : tool.execute(input, context);
    }
}
