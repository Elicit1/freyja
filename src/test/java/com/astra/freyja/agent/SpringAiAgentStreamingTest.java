package com.astra.freyja.agent;

import com.astra.freyja.agent.gateway.*;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.tool.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.AiModelFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.ollama.*;
import org.springframework.ai.ollama.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpringAiAgentStreamingTest {
    private SpringAiAgentModelGateway gateway(ChatModel model, ExecutorService executor) {
        AgentTestSupport.metadata();
        var providers = mock(AiProviderMapper.class); var models = mock(AiModelMapper.class);
        var provider = new AiProvider(); provider.setStatus(1); when(providers.selectById(anyLong())).thenReturn(provider);
        var entry = new AiModel(); entry.setStatus(1); entry.setModelType("CHAT"); when(models.selectOne(any())).thenReturn(entry);
        var factory = mock(AiModelFactory.class); when(factory.getChatModelForSnapshot(anyLong(), any())).thenReturn(model);
        AgentTool<SpringAiAgentModelGatewayTest.ShotInput> tool = new AgentTool<>() {
            public String name() { return "get_shot_detail"; }
            public String description() { return "读取分镜"; }
            public Class<SpringAiAgentModelGatewayTest.ShotInput> inputType() { return SpringAiAgentModelGatewayTest.ShotInput.class; }
            public ToolResult execute(SpringAiAgentModelGatewayTest.ShotInput input, ToolContext context) { fail("Streaming must not execute tools"); return null; }
        };
        return new SpringAiAgentModelGateway(factory, models, providers, new AgentToolRegistry(List.of(tool)), executor);
    }
    private static String chunk(String delta, String finish) {
        return "data: {\"id\":\"stream-test\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":"
                + delta + ",\"finish_reason\":" + finish + "}]}\n\n";
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void actualOpenAiStreamsPublicTextBeforeCompletionAndAssemblesToolsAndReplay(boolean includeUsage) throws Exception {
        var observed = new CountDownLatch(1); var request = new AtomicReference<String>();
        var fixtureError = new AtomicReference<Throwable>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(chunk("{\"role\":\"assistant\",\"content\":\"灯\",\"reasoning_content\":\"internal-secret\"}", "null").getBytes(StandardCharsets.UTF_8)); out.flush();
                if (!observed.await(3, TimeUnit.SECONDS)) throw new AssertionError("Preview did not arrive before completion");
                out.write(chunk("{\"content\":\"塔\"}", "null").getBytes(StandardCharsets.UTF_8));
                out.write(chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"get_shot_detail\",\"arguments\":\"{\\\"shotId\\\":\"}}]}", "null").getBytes(StandardCharsets.UTF_8));
                out.write(chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"2032095628944588801\\\"}\"}}]}", "null").getBytes(StandardCharsets.UTF_8));
                out.write(chunk("{}", "\"tool_calls\"").getBytes(StandardCharsets.UTF_8));
                if (includeUsage) out.write("data: {\"id\":\"stream-test\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":\"test-model\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n".getBytes(StandardCharsets.UTF_8));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (Throwable e) { fixtureError.set(e); } finally { exchange.close(); }
        }); server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().apiKey("fixture-key").maxRetries(0)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").model("test-model").build())
                    .httpClientBuilderCustomizer(ReasoningReplay.customizer()).build();
            var gateway = gateway(model, executor);
            var empty = AssistantMessage.builder().content("").properties(Map.of("reasoningContent", ""))
                    .toolCalls(List.of(new AssistantMessage.ToolCall("old", "function", "get_shot_detail", "{}"))).build();
            var history = List.<Message>of(new UserMessage("读取分镜"), empty,
                    ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("old", "get_shot_detail", "{}"))).build(),
                    AssistantMessage.builder().content("继续").properties(Map.of("reasoningContent", "old-private-reasoning")).build());
            var text = new CopyOnWriteArrayList<String>();
            var reply = gateway.stream(AgentTestSupport.snapshot(List.of("get_shot_detail"), "NORMAL"), history,
                    List.of("get_shot_detail"), 5000, delta -> { text.add(delta); observed.countDown(); });
            assertNull(fixtureError.get()); assertEquals("灯塔", String.join("", text)); assertEquals("灯塔", reply.content());
            assertEquals("internal-secret", reply.properties().get("reasoningContent"));
            assertEquals(1, reply.toolCalls().size()); assertEquals("call-1", reply.toolCalls().getFirst().id());
            assertEquals("{\"shotId\":\"2032095628944588801\"}", reply.toolCalls().getFirst().arguments());
            assertEquals("get_shot_detail", reply.toolCalls().getFirst().name());
            if (includeUsage) { assertEquals(10, reply.promptTokens()); assertEquals(5, reply.completionTokens()); }
            else { assertNull(reply.promptTokens()); assertNull(reply.completionTokens()); }
            var json = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var body = json.readTree(request.get()); assertTrue(body.path("stream").asBoolean());
            assertTrue(body.path("stream_options").path("include_usage").asBoolean());
            assertFalse(body.has(ReasoningReplay.STREAM_REPLAY_FIELD));
            assertEquals("", body.path("messages").get(1).path("reasoning_content").asText("absent"));
            assertEquals("old-private-reasoning", body.path("messages").get(3).path("reasoning_content").asText());
            gateway.stream(AgentTestSupport.snapshot(List.of(), "NORMAL"), List.of(new UserMessage("独立查询")), List.of(), 5000, ignored -> {});
            assertEquals(1, json.readTree(request.get()).path("messages").size(), "Request-local replay must not leak");
        } finally { server.stop(0); }
    }
    @ParameterizedTest @ValueSource(strings = {"MIMO_CHAT", "DEEPSEEK_CHAT"})
    void thinkingFragmentsAndToolHistorySurviveTheNextUserTurn(String protocol) throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (requests.size() == 1) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    for (String item : List.of(chunk("{\"role\":\"assistant\",\"reasoning_content\":\"fixture part one; \"}", "null"),
                            chunk("{\"reasoning_content\":\"fixture part two\"}", "null"),
                            chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"mimo-tool\",\"type\":\"function\",\"function\":{\"name\":\"get_shot_detail\",\"arguments\":\"{}\"}}]}", "null"),
                            chunk("{}", "\"tool_calls\""), "data: [DONE]\n\n")) out.write(item.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                byte[] response = """
                    {"id":"mimo-final","object":"chat.completion","created":1730000000,"model":"test-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"完成"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        }); server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var snapshot = AgentTestSupport.snapshot(List.of("get_shot_detail"), "NORMAL");
            snapshot.config().getModel().setReasoningMode("ENABLED");
            snapshot.config().getModel().setTemperature(0.4); snapshot.config().getModel().setTopP(0.8);
            snapshot.pinnedModel().setParamsJson("{\"capabilities\":{\"reasoningProtocol\":\"" + protocol
                    + "\",\"outputTokenParameter\":\"" + ("MIMO_CHAT".equals(protocol) ? "max_completion_tokens" : "max_tokens") + "\"}}");
            var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().apiKey("fixture").maxRetries(0)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").model("test-model").build())
                    .httpClientBuilderCustomizer(ReasoningReplay.customizer(com.astra.freyja.agent.model.ChatModelCapabilities.from(snapshot.pinnedModel()))).build();
            var gateway = gateway(model, executor);
            var text = new CopyOnWriteArrayList<String>();
            var reply = gateway.stream(snapshot, List.of(new UserMessage("查询镜头")), List.of("get_shot_detail"), 5000, text::add);
            assertEquals("fixture part one; fixture part two", reply.properties().get("reasoningContent"));
            assertEquals("", String.join("", text), "thinking is never emitted as public answer text");
            var assistant = AssistantMessage.builder().content(reply.content()).properties(reply.properties())
                    .toolCalls(reply.toolCalls().stream().map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.arguments())).toList()).build();
            gateway.call(snapshot, List.of(new UserMessage("查询镜头"), assistant,
                    ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("mimo-tool", "get_shot_detail", "{}"))).build(),
                    new UserMessage("继续检查镜头")), List.of("get_shot_detail"));
            var json = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            for (var encoded : requests) {
                var request = json.readTree(encoded);
                assertEquals("enabled", request.path("thinking").path("type").asText());
                assertFalse(request.has("temperature"));
                if ("MIMO_CHAT".equals(protocol)) {
                    assertTrue(request.has("max_completion_tokens")); assertFalse(request.has("max_tokens"));
                    assertFalse(request.has("reasoning_effort")); assertFalse(request.has("top_p"));
                } else {
                    assertTrue(request.has("max_tokens")); assertFalse(request.has("max_completion_tokens"));
                    assertEquals(0.95, request.path("top_p").asDouble());
                }
            }
            var history = json.readTree(requests.getLast()).path("messages");
            assertEquals("fixture part one; fixture part two", history.get(1).path("reasoning_content").asText());
            assertEquals("mimo-tool", history.get(1).path("tool_calls").get(0).path("id").asText());
            assertEquals("mimo-tool", history.get(2).path("tool_call_id").asText());
            assertEquals("继续检查镜头", history.get(3).path("content").asText());
        } finally { server.stop(0); }
    }
    @Test void actualOllamaStreamAggregatesTextToolAndUsage() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                {"model":"test-model","created_at":"2026-10-11T01:00:00Z","done":false,"message":{"role":"assistant","content":"灯"}}
                {"model":"test-model","created_at":"2026-10-11T01:00:00Z","done":false,"message":{"role":"assistant","content":"塔","tool_calls":[{"function":{"name":"get_shot_detail","arguments":{"shotId":"2032095628944588801"}}}]}}
                {"model":"test-model","created_at":"2026-10-11T01:00:00Z","done":true,"done_reason":"stop","message":{"role":"assistant","content":""},"prompt_eval_count":10,"eval_count":5}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var model = OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                    .options(OllamaChatOptions.builder().model("test-model").build()).build();
            var text = new CopyOnWriteArrayList<String>();
            var reply = gateway(model, executor).stream(AgentTestSupport.snapshot(List.of("get_shot_detail"), "NORMAL"), List.of(new UserMessage("读取分镜")), List.of("get_shot_detail"), 5000, text::add);
            assertEquals("灯塔", reply.content()); assertEquals("灯塔", String.join("", text));
            assertEquals(1, reply.toolCalls().size()); assertFalse(reply.toolCalls().getFirst().id().isBlank());
            assertEquals(10, reply.promptTokens()); assertEquals(5, reply.completionTokens());
        } finally { server.stop(0); }
    }
    @Test void timeoutAndInterruptionDisposeSubscriptionInsteadOfReturningPartialReply() throws Exception {
        var model = mock(ChatModel.class); when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test-model").build());
        var cancelled = new CountDownLatch(2); var emitted = new CountDownLatch(1);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> Flux.concat(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("partial"))))), Flux.<ChatResponse>never()).doOnCancel(cancelled::countDown));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gateway = gateway(model, executor); var snapshot = AgentTestSupport.snapshot(List.of(), "NORMAL");
            assertThrows(BizException.class, () -> gateway.stream(snapshot, List.of(new UserMessage("请求")), List.of(), 200, ignored -> {}));
            var caller = executor.submit(() -> gateway.stream(snapshot, List.of(new UserMessage("请求")), List.of(), 5000, ignored -> emitted.countDown()));
            assertTrue(emitted.await(2, TimeUnit.SECONDS)); caller.cancel(true);
            assertTrue(cancelled.await(2, TimeUnit.SECONDS)); verify(model, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        }
    }
    @Test void earlyStreamEndIsNotAcceptedAsACompleteReply() {
        var model = mock(ChatModel.class); when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test-model").build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("partial"))))));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThrows(BizException.class, () -> gateway(model, executor).stream(AgentTestSupport.snapshot(List.of(), "NORMAL"), List.of(new UserMessage("请求")), List.of(), 5000, ignored -> {}));
        }
    }
}
