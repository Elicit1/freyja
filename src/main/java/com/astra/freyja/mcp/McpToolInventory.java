package com.astra.freyja.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class McpToolInventory {
    private final McpReadTools reads;
    private final McpBusinessTools business;
    public List<McpTool<?>> all() { List<McpTool<?>> result = new ArrayList<>(reads.tools()); result.addAll(business.allTools()); return result; }
}
