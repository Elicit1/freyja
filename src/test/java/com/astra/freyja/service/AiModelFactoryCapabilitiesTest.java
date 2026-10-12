package com.astra.freyja.service;

import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.impl.AiModelFactoryImpl;
import com.astra.freyja.util.CryptoUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiModelFactoryCapabilitiesTest {
    @ParameterizedTest @CsvSource({"DEEPSEEK_CHAT,true", "DEEPSEEK_CHAT,false", "MIMO_CHAT,true", "MIMO_CHAT,false"})
    void actualRequestsRespectThinkingAfterSdkMergesDefaults(String protocol, boolean defaultThinking) throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = """
                {"id":"fixture","object":"chat.completion","created":1730000000,"model":"fixture",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            int limit = "MIMO_CHAT".equals(protocol) ? 131072 : 393216;
            String tokenField = "MIMO_CHAT".equals(protocol) ? "max_completion_tokens" : "max_tokens";
            var providers = mock(AiProviderMapper.class); var models = mock(AiModelMapper.class); var crypto = mock(CryptoUtil.class);
            var provider = new AiProvider(); provider.setId(1L); provider.setStatus(1); provider.setProviderType("OPENAI");
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); provider.setMaxRetries(0);
            provider.setApiKey("encrypted-fixture"); provider.setChatCapabilitiesJson("{\"reasoningProtocol\":\"" + protocol
                    + "\",\"outputTokenParameter\":\"" + tokenField + "\",\"maxOutputTokens\":" + limit + "}");
            when(providers.selectById(1L)).thenReturn(provider); when(crypto.decrypt("encrypted-fixture")).thenReturn("fixture-key");
            var entry = new AiModel(); entry.setProviderId(1L); entry.setStatus(1); entry.setModelType("CHAT"); entry.setModelCode("fixture");
            entry.setMaxTokens(limit * 2); entry.setTemperature(0.4); entry.setTopP(0.8);
            entry.setParamsJson("{\"thinking\":{\"type\":\"" + (defaultThinking ? "enabled" : "disabled")
                    + "\"},\"reasoning_effort\":\"high\",\"frequency_penalty\":1.0,\"presence_penalty\":1.0}");
            when(models.selectOne(any())).thenReturn(entry);
            var factory = new AiModelFactoryImpl(providers, models, crypto, new ObjectMapper());
            var model = factory.getChatModel(1L, "fixture");
            model.call(new org.springframework.ai.chat.prompt.Prompt(new org.springframework.ai.chat.messages.UserMessage("fixture")));
            model.call(new org.springframework.ai.chat.prompt.Prompt("fixture", ((OpenAiChatOptions) model.getOptions()).mutate().temperature(0.25).topP(0.6)
                    .extraBody(java.util.Map.of("thinking", java.util.Map.of("type", defaultThinking ? "disabled" : "enabled"))).build()));
            model.call(new org.springframework.ai.chat.prompt.Prompt(new org.springframework.ai.chat.messages.UserMessage("fixture")));
            var json = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            for (int i = 0; i < requests.size(); i++) {
                boolean thinking = i == 1 ? !defaultThinking : defaultThinking;
                var body = json.readTree(requests.get(i));
                assertEquals(thinking ? "enabled" : "disabled", body.path("thinking").path("type").asText());
                assertEquals(limit, body.path(tokenField).asInt(), "request " + i + ": " + body);
                assertFalse(body.has("max_tokens".equals(tokenField) ? "max_completion_tokens" : "max_tokens"));
                if ("MIMO_CHAT".equals(protocol)) {
                    assertFalse(body.has("reasoning_effort"));
                    if (thinking) { assertFalse(body.has("temperature")); assertFalse(body.has("top_p")); }
                    else { assertEquals(i == 1 ? 0.25 : 0.4, body.path("temperature").asDouble()); assertEquals(i == 1 ? 0.6 : 0.8, body.path("top_p").asDouble()); }
                } else if (thinking) {
                    assertEquals("high", body.path("reasoning_effort").asText()); assertEquals(0.95, body.path("top_p").asDouble());
                    assertFalse(body.has("temperature")); assertFalse(body.has("frequency_penalty")); assertFalse(body.has("presence_penalty"));
                } else {
                    assertFalse(body.has("reasoning_effort")); assertFalse(body.has("top_p"));
                    assertEquals(i == 1 ? 0.25 : 0.4, body.path("temperature").asDouble());
                }
            }
            assertEquals(3, requests.size()); assertEquals(limit * 2, entry.getMaxTokens(), "saved settings must not be mutated");
        } finally { server.stop(0); }
    }
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
