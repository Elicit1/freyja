package com.astra.freyja.service;

import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.impl.AiModelFactoryImpl;
import com.astra.freyja.util.CryptoUtil;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiModelFactoryCapabilitiesTest {
    @Test void regularCallsInheritDefaultsAndPinnedCallsKeepTheirOwnProtocol() {
        var providers = mock(AiProviderMapper.class); var models = mock(AiModelMapper.class); var crypto = mock(CryptoUtil.class);
        var provider = new AiProvider(); provider.setId(1L); provider.setStatus(1); provider.setProviderType("OPENAI");
        provider.setBaseUrl("http://127.0.0.1:1/v1"); provider.setApiKey("encrypted-fixture");
        provider.setChatCapabilitiesJson("{\"reasoningProtocol\":\"MIMO_CHAT\",\"outputTokenParameter\":\"max_completion_tokens\",\"recommendedOutputTokens\":65536}");
        when(providers.selectById(1L)).thenReturn(provider); when(crypto.decrypt("encrypted-fixture")).thenReturn("fixture-key");
        var model = new AiModel(); model.setProviderId(1L); model.setStatus(1); model.setModelType("CHAT"); model.setModelCode("fixture");
        model.setParamsJson("{\"reasoning_effort\":\"high\"}"); when(models.selectOne(any())).thenReturn(model);
        var factory = new AiModelFactoryImpl(providers, models, crypto, new ObjectMapper());
        var regular = (OpenAiChatOptions) factory.getChatModel(1L, "fixture").getOptions();
        assertNull(regular.getMaxTokens()); assertEquals(65536, regular.getMaxCompletionTokens()); assertNull(regular.getReasoningEffort());
        var pinned = new AiModel(); pinned.setModelCode("fixture"); pinned.setMaxTokens(8192);
        pinned.setParamsJson("{\"capabilities\":{\"reasoningProtocol\":\"NONE\",\"outputTokenParameter\":\"max_tokens\"}}");
        var snapshot = (OpenAiChatOptions) factory.getChatModelForSnapshot(1L, pinned).getOptions();
        assertEquals(8192, snapshot.getMaxTokens()); assertNull(snapshot.getMaxCompletionTokens());
        assertFalse(model.getParamsJson().contains("MIMO_CHAT"));
    }
}
