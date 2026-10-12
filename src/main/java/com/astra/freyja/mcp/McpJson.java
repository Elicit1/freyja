package com.astra.freyja.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.stereotype.Component;

/** Isolated mapper: protocol request IDs keep their type, business Long values become strings. */
@Component
public class McpJson {
    private final ObjectMapper mapper;
    public McpJson() {
        SimpleModule ids = new SimpleModule("McpBusinessIds");
        ids.addSerializer(Long.class, ToStringSerializer.instance);
        ids.addSerializer(Long.TYPE, ToStringSerializer.instance);
        mapper = new ObjectMapper().findAndRegisterModules().registerModule(ids)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        mapper.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Boolean).setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
    }
    public ObjectMapper mapper() { return mapper; }
    public <T> T read(Object input, Class<T> type) { return mapper.convertValue(input, type); }
    public Object value(Object output) { return mapper.convertValue(output, Object.class); }
    public String write(Object output) {
        try { return mapper.writeValueAsString(output); }
        catch (Exception e) { throw new IllegalStateException("MCP 结果序列化失败", e); }
    }
}
