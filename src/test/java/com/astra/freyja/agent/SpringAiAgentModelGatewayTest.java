package com.astra.freyja.agent;

import com.astra.freyja.agent.gateway.SpringAiAgentModelGateway;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.tool.*;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.AiModelFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpringAiAgentModelGatewayTest {
    @ParameterizedTest @ValueSource(strings = {"DEEPSEEK_CHAT", "MIMO_CHAT"})
    void summaryOverridesActualWireThinkingAndRetainsLengthAndReasoningUsage(String protocol) throws Exception {
        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"id":"summary-exhausted","object":"chat.completion","created":1730000000,"model":"test-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":"private reasoning"},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":6600,"completion_tokens":8191,"total_tokens":14791,"completion_tokens_details":{"reasoning_tokens":8191}}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var providers = mock(AiProviderMapper.class); var models = mock(AiModelMapper.class);
            var provider = new AiProvider(); provider.setStatus(1); when(providers.selectById(anyLong())).thenReturn(provider);
            var entry = new AiModel(); entry.setStatus(1); entry.setModelType("CHAT"); when(models.selectOne(any())).thenReturn(entry);
            var source = AgentTestSupport.snapshot(List.of(), "NORMAL"); source.config().getModel().setMaxTokens(8192);
            source.pinnedModel().setParamsJson("{\"capabilities\":{\"contextWindow\":1048576,\"maxOutputTokens\":131072,\"reasoningProtocol\":\"" + protocol
                    + "\",\"outputTokenParameter\":\"" + ("MIMO_CHAT".equals(protocol) ? "max_completion_tokens" : "max_tokens") + "\"}}");
            var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().apiKey("fixture")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").model("test-model").maxRetries(0)
                    .extraBody(java.util.Map.of("thinking", java.util.Map.of("type", "enabled"), "reasoning_effort", "high")).build()).build();
            var factory = mock(AiModelFactory.class); when(factory.getChatModelForSnapshot(anyLong(), any())).thenReturn(model);
            var gateway = new SpringAiAgentModelGateway(factory, models, providers, new AgentToolRegistry(List.of()), executor);
            var summarizer = new com.astra.freyja.agent.memory.AgentMemorySummarizer(gateway, new com.astra.freyja.agent.model.AgentJson());
            var reply = summarizer.summarize(source, "旧摘要", "新历史", 30000);
            var wireJson = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var request = wireJson.readTree(requestBody.get());
            assertEquals("disabled", request.path("thinking").path("type").asText());
            if ("MIMO_CHAT".equals(protocol)) {
                assertFalse(request.has("reasoning_effort")); assertFalse(request.has("max_tokens"));
                assertEquals(16384, request.path("max_completion_tokens").asInt());
            } else { assertEquals("none", request.path("reasoning_effort").asText()); assertEquals(16384, request.path("max_tokens").asInt()); }
            assertEquals("length", reply.finishReason());
            assertEquals(8191, reply.reasoningTokens()); assertEquals(8191, reply.completionTokens()); assertTrue(reply.properties().isEmpty());
            assertEquals(8192, source.config().getModel().getMaxTokens());
        } finally { server.stop(0); }
    }
    public record ShotInput(String shotId) {}
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void actualSpringAiHttpAdapterReturnsToolCallsWithoutExecutingThem(boolean includeUsage) throws Exception {
        AgentTestSupport.metadata();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String responseJson = """
                {"id":"chatcmpl-test","object":"chat.completion","created":1730000000,"model":"test-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,
                    "tool_calls":[{"id":"call-1","type":"function","function":{"name":"get_shot_detail",
                       "arguments":"{\\\"shotId\\\":\\\"2032095628944588801\\\"}"}}]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                """;
            if (!includeUsage) responseJson = responseJson.replace("{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}", "null");
            byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var providerMapper = mock(AiProviderMapper.class); var modelMapper = mock(AiModelMapper.class);
            AiProvider provider = new AiProvider(); provider.setStatus(1); when(providerMapper.selectById(anyLong())).thenReturn(provider);
            AiModel configured = new AiModel(); configured.setStatus(1); configured.setModelType("CHAT"); when(modelMapper.selectOne(any())).thenReturn(configured);
            var snapshot = AgentTestSupport.snapshot(List.of("get_shot_detail"), "NORMAL");
            var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().apiKey("test-key")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").model("test-model").maxRetries(0).build())
                    .httpClientBuilderCustomizer(com.astra.freyja.agent.gateway.ReasoningReplay.customizer()).build();
            var factory = mock(AiModelFactory.class); when(factory.getChatModelForSnapshot(anyLong(), any())).thenReturn(model);
            AgentTool<ShotInput> tool = new AgentTool<>() {
                public String name() { return "get_shot_detail"; }
                public String description() { return "读取分镜"; }
                public Class<ShotInput> inputType() { return ShotInput.class; }
                public ToolResult execute(ShotInput input, ToolContext context) { fail("model must never execute a tool"); return null; }
            };
            var registry = new AgentToolRegistry(List.of(tool));
            var gateway = new SpringAiAgentModelGateway(factory, modelMapper, providerMapper, registry, executor);
            var reply = gateway.call(snapshot, List.of(new UserMessage("读取分镜")), List.of("get_shot_detail"));
            assertEquals(1, reply.toolCalls().size()); assertEquals("get_shot_detail", reply.toolCalls().getFirst().name());
            assertTrue(reply.toolCalls().getFirst().arguments().contains("2032095628944588801"));
            if (includeUsage) { assertEquals(10, reply.promptTokens()); assertEquals(5, reply.completionTokens()); }
            else { assertNull(reply.promptTokens()); assertNull(reply.completionTokens()); }
            assertTrue(requestBody.get().contains("\"tools\"")); assertTrue(requestBody.get().contains("get_shot_detail"));
            // Exercise the actual SDK serializer, including a prior empty reasoning field.
            var empty = org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                    .properties(java.util.Map.of("reasoningContent", "")).toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("old", "function", "get_shot_detail", "{}"))).build();
            var reasoned = org.springframework.ai.chat.messages.AssistantMessage.builder().content("继续")
                    .properties(java.util.Map.of("reasoningContent", "protocol fixture")).build();
            gateway.call(snapshot, List.of(new UserMessage("读取分镜"), empty,
                    org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("old", "get_shot_detail", "{}"))).build(), reasoned), List.of("get_shot_detail"));
            var strictJson = new com.fasterxml.jackson.databind.ObjectMapper();
            strictJson.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var body = strictJson.readTree(requestBody.get());
            assertEquals("", body.path("messages").get(1).path("reasoning_content").asText("absent"));
            assertEquals("protocol fixture", body.path("messages").get(3).path("reasoning_content").asText());
            assertEquals("old", body.path("messages").get(2).path("tool_call_id").asText());
            gateway.call(snapshot, List.of(new UserMessage("普通查询")), List.of("get_shot_detail"));
            assertEquals(1, strictJson.readTree(requestBody.get()).path("messages").size(), "replay scope must not leak into the next request");
        } finally { server.stop(0); }
    }
    @Test void actualOllamaHttpAdapterReturnsToolCallsWithoutExecutingThem() throws Exception {
        AgentTestSupport.metadata();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"model":"test-model","created_at":"2026-10-11T01:00:00Z","done":true,
                 "message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"get_shot_detail",
                    "arguments":{"shotId":"2032095628944588801"}}}]},"prompt_eval_count":10,"eval_count":5}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var providerMapper = mock(AiProviderMapper.class); var modelMapper = mock(AiModelMapper.class);
            AiProvider provider = new AiProvider(); provider.setStatus(1); when(providerMapper.selectById(anyLong())).thenReturn(provider);
            AiModel configured = new AiModel(); configured.setStatus(1); configured.setModelType("CHAT"); when(modelMapper.selectOne(any())).thenReturn(configured);
            var model = OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                    .options(OllamaChatOptions.builder().model("test-model").build()).build();
            var factory = mock(AiModelFactory.class); when(factory.getChatModelForSnapshot(anyLong(), any())).thenReturn(model);
            AgentTool<ShotInput> tool = new AgentTool<>() {
                public String name() { return "get_shot_detail"; }
                public String description() { return "读取分镜"; }
                public Class<ShotInput> inputType() { return ShotInput.class; }
                public ToolResult execute(ShotInput input, ToolContext context) { fail("model must never execute a tool"); return null; }
            };
            var gateway = new SpringAiAgentModelGateway(factory, modelMapper, providerMapper, new AgentToolRegistry(List.of(tool)), executor);
            var reply = gateway.call(AgentTestSupport.snapshot(List.of("get_shot_detail"), "NORMAL"), List.of(new UserMessage("读取分镜")), List.of("get_shot_detail"));
            assertEquals(1, reply.toolCalls().size()); assertFalse(reply.toolCalls().getFirst().id().isBlank());
            assertEquals("get_shot_detail", reply.toolCalls().getFirst().name());
            assertTrue(reply.toolCalls().getFirst().arguments().contains("2032095628944588801"));
            assertEquals(10, reply.promptTokens()); assertEquals(5, reply.completionTokens());
            assertTrue(requestBody.get().contains("get_shot_detail"));
        } finally { server.stop(0); }
    }
}
