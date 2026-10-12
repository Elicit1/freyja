package com.astra.freyja.mcp;

import java.util.function.BiFunction;
import java.util.Map;

public record McpTool<I>(String name, String description, Class<I> inputType, Effect effect, Map<String, Object> schema,
                         BiFunction<McpPrincipal, I, Object> handler) {
    public enum Effect { READ, WRITE }
    public static <I> McpTool<I> read(String name, String description, Class<I> type, BiFunction<McpPrincipal, I, Object> handler) {
        return new McpTool<>(name, description, type, Effect.READ, null, handler);
    }
}
