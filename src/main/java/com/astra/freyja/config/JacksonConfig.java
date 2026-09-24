package com.astra.freyja.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 全局 Jackson 配置：Long 序列化为字符串（避免前端 JS 对 19 位雪花 ID 精度丢失），
 * 反序列化同时兼容数字与字符串两种形式。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.findAndRegisterModules();
        return mapper;
    }

    @Bean
    public JsonMapperBuilderCustomizer longToStringCustomizer() {
        SimpleModule module = new SimpleModule("FreyjaLongToString");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addDeserializer(Long.class, new LongDeserializer());
        return builder -> {
            builder.addModule(module);
            builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        };
    }

    private static class LongDeserializer extends StdDeserializer<Long> {

        LongDeserializer() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NUMBER_INT) {
                return p.getLongValue();
            }
            if (token == JsonToken.VALUE_STRING) {
                String value = p.getText();
                if (value == null || value.isBlank()) {
                    return null;
                }
                try {
                    return Long.valueOf(value.trim());
                } catch (NumberFormatException e) {
                    return (Long) ctxt.handleWeirdStringValue(Long.class, value, "不是合法的 Long");
                }
            }
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            return (Long) ctxt.handleUnexpectedToken(Long.class, p);
        }
    }
}