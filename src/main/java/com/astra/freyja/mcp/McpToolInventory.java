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
    public List<McpTool<?>> all() {
        List<McpTool<?>> result = new ArrayList<>(reads.tools()); result.addAll(business.allTools());
        skills.ifAvailable(adapter -> result.addAll(adapter.tools())); return result;
    }
}
