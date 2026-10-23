package com.astra.freyja.service;

import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShotVideoCapabilityServiceTest {
    @Test void requiresBothContractsAndIntersectsMeasuredLimits() throws Exception {
        var json = new ObjectMapper();
        Map<String,Object> local = new LinkedHashMap<>(Map.of("schemaVersion", 1, "status", "VERIFIED", "adapter", "MINIMAX_H3_REF_VIDEO_V1", "workflowRevision", "a".repeat(64),
                "maxVideos", 2, "minClipSeconds", 1, "maxClipSeconds", 15, "maxTotalClipSeconds", 15, "decodeFps", 24));
        local.putAll(Map.of("maxDecodedFrames", 360, "maxDecodeWidth", 768, "maxDecodeHeight", 768, "maxMixedAssets", 12, "supportsVideoAudio", true));
        Map<String,Object> remote = new LinkedHashMap<>(local);
        remote.put("maxVideos", 1); remote.put("maxClipSeconds", 5); remote.put("maxDecodedFrames", 120); remote.put("supportsVideoAudio", false);
        AtomicReference<String> responseBody = new AtomicReference<>();
        responseBody.set(json.writeValueAsString(Map.of("nodeId", "node1", "workflowRevision", "a".repeat(64), "healthy", true, "referenceVideo", remote)));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/minimax-h3-ref-turbo8/capabilities", exchange -> {
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var provider = new AiProvider(); provider.setId(1L); provider.setStatus(1); provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var model = new AiModel(); model.setId(2L); model.setProviderId(1L); model.setMaxVideos(2); model.setModelCode("minimax-h3-ref-turbo8");
            model.setParamsJson(json.writeValueAsString(Map.of("videoGeneration", Map.of("referenceVideo", local))));
            var service = new ShotVideoCapabilityService(mock(AiModelMapper.class), mock(AiProviderMapper.class), mock(CryptoUtil.class), json);
            var option = service.describe(model, provider);
            assertTrue(option.getRenderable(), option.getReason());
            assertEquals(1, option.getReferenceVideo().getMaxVideos());
            assertEquals(120, option.getReferenceVideo().getMaxDecodedFrames());
            assertEquals(0, option.getReferenceVideo().getMaxClipSeconds().compareTo(new java.math.BigDecimal("5")));
            assertFalse(option.getReferenceVideo().getSupportsVideoAudio());
            remote.put("maxVideos", 2);
            responseBody.set(json.writeValueAsString(Map.of("nodeId", "node1", "workflowRevision", "a".repeat(64), "healthy", true, "referenceVideo", remote)));
            assertEquals(2, service.describe(model, provider).getReferenceVideo().getMaxVideos());
            model.setMaxVideos(1);
            assertEquals(1, service.describe(model, provider).getReferenceVideo().getMaxVideos());
            model.setMaxVideos(2);
            model.setMaxVideos(3); remote.put("maxVideos", 3);
            responseBody.set(json.writeValueAsString(Map.of("nodeId", "node1", "workflowRevision", "a".repeat(64), "healthy", true, "referenceVideo", remote)));
            assertEquals(3, service.describe(model, provider).getReferenceVideo().getMaxVideos(), "model-center count overrides the legacy duplicated JSON count");
            model.setMaxVideos(2);
            remote.put("status", "UNVERIFIED");
            responseBody.set(json.writeValueAsString(Map.of("nodeId", "node1", "workflowRevision", "a".repeat(64), "healthy", true, "referenceVideo", remote)));
            assertFalse(service.describe(model, provider).getRenderable());
            remote.put("status", "VERIFIED");
            responseBody.set(json.writeValueAsString(Map.of("nodeId", "node1", "workflowRevision", "b".repeat(64), "healthy", true, "referenceVideo", remote)));
            assertEquals("REFERENCE_VIDEO_WORKFLOW_MISMATCH", service.describe(model, provider).getErrorCode());
        } finally { server.stop(0); }
    }
}
