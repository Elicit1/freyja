package com.astra.freyja.agent.model;

import com.astra.freyja.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.stereotype.Component;

@Component
public class AgentJson {
    private final ObjectMapper mapper;
    public AgentJson() {
        SimpleModule ids = new SimpleModule();
        ids.addSerializer(Long.class, ToStringSerializer.instance);
        mapper = new ObjectMapper().findAndRegisterModules().registerModule(ids)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new BizException("Agent 数据序列化失败"); }
    }
    public <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception e) { throw new BizException("Agent 数据格式无效: " + type.getSimpleName()); }
    }
    public <T> T copy(T value, Class<T> type) { return read(write(value), type); }
}
