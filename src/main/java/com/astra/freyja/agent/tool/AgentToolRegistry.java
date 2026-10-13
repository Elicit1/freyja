package com.astra.freyja.agent.tool;

import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.common.BizException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool<?>> tools = new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.astra.freyja.capability.service.CapabilityCatalogService capabilities;
    public AgentToolRegistry(List<AgentTool<?>> entries) {
        for (AgentTool<?> tool : entries) {
            if (tools.putIfAbsent(tool.name(), tool) != null)
                throw new IllegalStateException("重复 Agent 工具: " + tool.name());
            if (tool.effect() == Effect.WRITE && tool.recoveryMode() == RecoveryMode.REPLAY_SAFE)
                throw new IllegalStateException("写工具必须声明事务或查询恢复: " + tool.name());
        }
    }
    public AgentTool<?> require(String name) {
        if (capabilities != null) {
            var binding = capabilities.bindings(capabilities.current(), com.astra.freyja.capability.model.CapabilityModels.Consumer.INTERNAL_AGENT,
                    com.astra.freyja.agent.profile.AgentProfileRegistry.ASSISTANT_CODE).stream().filter(b -> b.name().equals(name)).findFirst()
                    .orElseThrow(() -> new BizException(404, "能力不存在或未授权: " + name));
            return capabilities.agentTool(binding);
        }
        AgentTool<?> tool = tools.get(name);
        if (tool == null) throw new BizException("TOOL_NOT_FOUND: " + name);
        return tool;
    }
    public AgentTool<?> require(String name, Snapshot snapshot) {
        if (capabilities == null) return require(name);
        if (snapshot.capabilities() == null) throw new BizException(409, "能力平台已升级，请新建Agent会话");
        capabilities.release(snapshot.capabilities().releaseId());
        var binding = snapshot.capabilities().bindings().stream().filter(b -> b.name().equals(name)).findFirst()
                .orElseThrow(() -> new BizException(403, "工具不在本次任务的能力快照中"));
        capabilities.requireCurrent(binding.id());
        return capabilities.agentTool(binding);
    }
    public List<String> allowedNames(String code, List<String> bootstrap) { return capabilities == null ? bootstrap : capabilities.allowedNames(code); }
    public List<String> requiredNames(String code, List<String> bootstrap) { return capabilities == null ? bootstrap : capabilities.requiredNames(code); }
    public com.astra.freyja.capability.model.CapabilityModels.AgentSnapshot snapshot(String code, List<String> names) {
        return capabilities == null ? null : capabilities.agentSnapshot(code, names);
    }
    public String sharedRules() { return capabilities == null ? "" : capabilities.rules(); }
    public String sharedRules(com.astra.freyja.capability.model.CapabilityModels.AgentSnapshot snapshot) {
        return capabilities == null ? "" : capabilities.release(snapshot.releaseId()).rules();
    }
    public int releaseVersion(com.astra.freyja.capability.model.CapabilityModels.AgentSnapshot snapshot) {
        return Math.toIntExact(capabilities.release(snapshot.releaseId()).revision());
    }
    public List<ToolDescriptor> catalog(List<String> allowed) {
        return allowed.stream().map(this::require).map(AgentTool::descriptor).toList();
    }
    public List<ToolCallback> definitions(List<String> names) {
        return callbacks(names.stream().<AgentTool<?>>map(this::require).toList());
    }
    public List<ToolCallback> definitions(List<String> names, Snapshot snapshot) {
        return callbacks(names.stream().<AgentTool<?>>map(name -> require(name, snapshot)).toList());
    }
    private List<ToolCallback> callbacks(List<AgentTool<?>> entries) {
        return entries.stream().map(tool -> (ToolCallback) new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                ToolDescriptor item = tool.descriptor();
                return ToolDefinition.builder().name(item.name()).description(item.description())
                        .inputSchema(item.inputSchema()).build();
            }
            public String call(String input) {
                throw new IllegalStateException("Agent 工具必须由持久化执行器调用");
            }
        }).toList();
    }
}
