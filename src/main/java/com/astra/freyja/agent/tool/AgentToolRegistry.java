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
    public AgentToolRegistry(List<AgentTool<?>> entries) {
        for (AgentTool<?> tool : entries) {
            if (tools.putIfAbsent(tool.name(), tool) != null)
                throw new IllegalStateException("重复 Agent 工具: " + tool.name());
            if (tool.effect() == Effect.WRITE && tool.recoveryMode() == RecoveryMode.REPLAY_SAFE)
                throw new IllegalStateException("写工具必须声明事务或查询恢复: " + tool.name());
        }
    }
    public AgentTool<?> require(String name) {
        AgentTool<?> tool = tools.get(name);
        if (tool == null) throw new BizException("TOOL_NOT_FOUND: " + name);
        return tool;
    }
    public List<ToolDescriptor> catalog(List<String> allowed) {
        return allowed.stream().map(this::require).map(AgentTool::descriptor).toList();
    }
    public List<ToolCallback> definitions(List<String> names) {
        return names.stream().map(this::require).map(tool -> (ToolCallback) new ToolCallback() {
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
