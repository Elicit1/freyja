package com.astra.freyja.mcp;

import java.util.function.BiFunction;
import java.util.Map;

public record McpTool<I>(String name, String description, Class<I> inputType, Effect effect, Map<String, Object> schema,
                         BiFunction<McpPrincipal, I, Object> handler, java.util.function.Consumer<Map<String, Object>> validator,
                         boolean writePermissionRequired) {
    public McpTool(String name, String description, Class<I> inputType, Effect effect, Map<String, Object> schema,
                   BiFunction<McpPrincipal, I, Object> handler) {
        this(name, description, inputType, effect, schema, handler, null, false);
    }
    public McpTool<I> validated(java.util.function.Consumer<Map<String, Object>> validator) {
        return new McpTool<>(name, description, inputType, effect, schema, handler, validator, writePermissionRequired);
    }
    public McpTool<I> requiringWritePermission() { return new McpTool<>(name, description, inputType, effect, schema, handler, validator, true); }
    public enum Effect { READ, WRITE }
    public static <I> McpTool<I> read(String name, String description, Class<I> type, BiFunction<McpPrincipal, I, Object> handler) {
        return new McpTool<>(name, description, type, Effect.READ, null, handler);
    }
}
