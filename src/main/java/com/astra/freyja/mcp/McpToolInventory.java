package com.astra.freyja.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class McpToolInventory {
    private final McpReadTools reads;
    private final McpBusinessTools business;
    private final org.springframework.beans.factory.ObjectProvider<McpSkillTools> skills;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private McpVoiceTools voices;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.astra.freyja.capability.service.CapabilityCatalogService capabilities;
    public List<McpTool<?>> all() {
        return capabilities == null ? registeredHandlers() : capabilities.mcpTools();
    }
    public List<McpTool<?>> registeredHandlers() {
        List<McpTool<?>> result = new ArrayList<>(reads.tools()); result.addAll(business.allTools());
        skills.ifAvailable(adapter -> result.addAll(adapter.tools()));
        if (voices != null) result.addAll(voices.tools());
        return result;
    }
}
