package com.astra.freyja.agent.tool;

import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.capability.model.CapabilityModels.*;
import com.astra.freyja.capability.service.CapabilityCatalogService;
import com.astra.freyja.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import java.util.List;

/** Agent adapter for published platform capabilities. There is no separate native-tool registry. */
@Component
@RequiredArgsConstructor
public class AgentToolRegistry {
    private final CapabilityCatalogService capabilities;
    public AgentTool<?> require(String name, Snapshot snapshot) {
        if (snapshot.capabilities() == null) throw new BizException(409, "能力平台已升级，请新建Agent会话");
        capabilities.release(snapshot.capabilities().releaseId());
        var binding = snapshot.capabilities().bindings().stream().filter(b -> b.name().equals(name)).findFirst()
                .orElseThrow(() -> new BizException(403, "工具不在本次任务的能力快照中"));
        capabilities.requireCurrent(binding.id());
        return capabilities.agentTool(binding);
    }
    public AgentSnapshot available(String code) { return capabilities.agentSnapshot(code); }
    public AgentSnapshot snapshot(String code, List<String> names) {
        return capabilities.agentSnapshot(code, names);
    }
    public String sharedRules(AgentSnapshot snapshot) {
        return capabilities.release(snapshot.releaseId()).rules();
    }
    public int releaseVersion(AgentSnapshot snapshot) {
        return Math.toIntExact(capabilities.release(snapshot.releaseId()).revision());
    }
    public List<ToolDescriptor> catalog(AgentSnapshot snapshot) {
        return snapshot.bindings().stream().map(capabilities::agentTool).map(AgentTool::descriptor).toList();
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
