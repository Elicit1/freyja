package com.astra.freyja.agent;

import com.astra.freyja.agent.model.ChatModelCapabilities;
import com.astra.freyja.common.BizException;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatModelCapabilitiesTest {
    private AiProvider mimo() {
        var provider = new AiProvider();
        provider.setChatCapabilitiesJson("""
            {"reasoningProtocol":"MIMO_CHAT","outputTokenParameter":"max_completion_tokens",
             "maxOutputTokens":131072,"recommendedOutputTokens":65536,"toolCalling":true}
            """);
        return provider;
    }
    @Test void providerDefaultsApplyWithoutMutatingTheRegisteredModel() {
        var model = new AiModel(); model.setParamsJson("{\"seed\":42,\"capabilities\":{\"contextWindow\":1048576}}");
        var resolved = ChatModelCapabilities.resolve(model, mimo());
        var caps = ChatModelCapabilities.from(resolved);
        assertEquals("MIMO_CHAT", caps.reasoningProtocol()); assertEquals("max_completion_tokens", caps.outputTokenParameter());
        assertEquals(131072, caps.outputLimit()); assertEquals(1048576, caps.contextLimit()); assertEquals(65536, caps.recommended(model));
        assertTrue(resolved.getParamsJson().contains("\"seed\":42")); assertTrue(resolved.getParamsJson().contains("\"toolCalling\":true"));
        assertFalse(model.getParamsJson().contains("MIMO_CHAT"));
    }
    @Test void explicitNoneAndFalseOverrideDefaultsWhileNullInherits() {
        var model = new AiModel(); model.setParamsJson("""
            {"capabilities":{"reasoningProtocol":"NONE","outputTokenParameter":"max_tokens","maxOutputTokens":null,"toolCalling":false}}
            """);
        var resolved = ChatModelCapabilities.resolve(model, mimo());
        var caps = ChatModelCapabilities.from(resolved);
        assertEquals("NONE", caps.reasoningProtocol()); assertEquals("max_tokens", caps.outputTokenParameter());
        assertEquals(131072, caps.outputLimit()); assertTrue(resolved.getParamsJson().contains("\"toolCalling\":false"));
    }
    @Test void resolvedCapabilitiesRemainStableAfterProviderChanges() {
        var provider = mimo(); var model = new AiModel();
        var pinned = ChatModelCapabilities.resolve(model, provider);
        provider.setChatCapabilitiesJson("{\"reasoningProtocol\":\"OPENAI\",\"maxOutputTokens\":1024}");
        assertEquals("MIMO_CHAT", ChatModelCapabilities.from(pinned).reasoningProtocol());
        assertEquals(131072, ChatModelCapabilities.from(pinned).outputLimit());
        var legacy = ChatModelCapabilities.resolve(model, null);
        assertEquals("NONE", ChatModelCapabilities.from(legacy).reasoningProtocol());
        assertEquals("max_tokens", ChatModelCapabilities.from(legacy).outputTokenParameter());
    }
    @Test void mimoRejectsUnsupportedEffortModesAndDefaultsRejectInvalidFields() {
        var caps = ChatModelCapabilities.from(new AiModel(), mimo());
        assertEquals(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")), caps.reasoningExtras("DISABLED"));
        assertEquals(java.util.Map.of("thinking", java.util.Map.of("type", "enabled")), caps.reasoningExtras("ENABLED"));
        assertTrue(caps.reasoningExtras("INHERIT").isEmpty());
        assertThrows(BizException.class, () -> caps.reasoningExtras("HIGH"));
        assertThrows(BizException.class, () -> ChatModelCapabilities.validateDefaults("{\"api_key\":\"never-allow\"}"));
        assertThrows(BizException.class, () -> ChatModelCapabilities.validateDefaults("[]"));
        assertThrows(BizException.class, () -> ChatModelCapabilities.validateDefaults("{\"toolCalling\":\"yes\"}"));
        assertThrows(BizException.class, () -> ChatModelCapabilities.validateDefaults("{\"reasoningProtocol\":\"MIMO_CHAT\",\"reasoningEfforts\":[\"high\"]}"));
    }
}
