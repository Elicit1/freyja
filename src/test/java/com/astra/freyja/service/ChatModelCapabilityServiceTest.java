package com.astra.freyja.service;

import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatModelCapabilityServiceTest {
    @Test void discoveryReadsOnlyExplicitCapabilitiesAndUsesV1FallbackWithoutInventingProtocol() throws Exception {
        var fallback = new AtomicInteger(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", e -> { e.sendResponseHeaders(404, -1); e.close(); });
        server.createContext("/v1/models", e -> {
            fallback.incrementAndGet(); assertEquals("Bearer fixture-key", e.getRequestHeaders().getFirst("Authorization"));
            byte[] data = "{\"data\":[{\"id\":\"deepseek-flash\",\"context_window\":1048576,\"max_output_tokens\":393216,\"effort\":{\"supported_levels\":[\"low\",\"high\",\"max\"]}},{\"id\":\"unknown\"}]}".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(200, data.length); e.getResponseBody().write(data); e.close();
        }); server.start();
        try {
            var providers = mock(AiProviderMapper.class); var crypto = mock(CryptoUtil.class);
            var provider = new AiProvider(); provider.setStatus(1); provider.setProviderType("OPENAI"); provider.setApiKey("encrypted");
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); when(providers.selectById(1L)).thenReturn(provider);
            when(crypto.decrypt("encrypted")).thenReturn("fixture-key");
            var service = new ChatModelCapabilityService(providers, crypto, new ObjectMapper()); var result = service.discover(1L);
            assertEquals(1048576, result.getFirst().contextWindow()); assertEquals(393216, result.getFirst().maxOutputTokens());
            assertEquals(java.util.List.of("low", "high", "max"), result.getFirst().reasoningEfforts());
            assertNull(result.getLast().contextWindow()); assertNull(result.getLast().maxOutputTokens()); assertEquals(1, fallback.get());
        } finally { server.stop(0); }
    }
}
