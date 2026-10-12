package com.astra.freyja.agent.gateway;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.RequestBody;
import okio.Buffer;
import java.util.*;
import java.util.function.Supplier;

/** Spring AI omits empty reasoning strings; some compatible servers require the original empty field too. */
public final class ReasoningReplay {
    private ReasoningReplay() {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<List<Map<String, Object>>> ORIGINAL = new ThreadLocal<>();
    // Request-local transport field, stripped by the interceptor before any bytes reach the provider.
    // Async SDK interceptors cannot read the caller's ThreadLocal.
    public static final String STREAM_REPLAY_FIELD = "__freyja_reasoning_replay_messages";
    public static <T> T withMessages(List<Message> messages, Supplier<T> call) {
        if (!needed(messages)) return call.get();
        var previous = ORIGINAL.get();
        ORIGINAL.set(messages(messages));
        try { return call.get(); }
        finally { if (previous == null) ORIGINAL.remove(); else ORIGINAL.set(previous); }
    }
    public static OpenAiHttpClientBuilderCustomizer customizer() {
        return customizer(null);
    }
    public static OpenAiHttpClientBuilderCustomizer customizer(com.astra.freyja.agent.model.ChatModelCapabilities capabilities) {
        return builder -> builder.interceptor(chain -> {
            var request = chain.request(); var original = ORIGINAL.get();
            if (request.body() == null) return chain.proceed(request);
            var buffer = new Buffer(); request.body().writeTo(buffer);
            ObjectNode body = (ObjectNode) JSON.readTree(buffer.readUtf8());
            var replay = body.remove(STREAM_REPLAY_FIELD);
            if (!body.has("messages")) return chain.proceed(request);
            boolean normalized = OpenAiRequestCompatibility.normalize(body, capabilities);
            if (replay == null && original == null && !normalized) return chain.proceed(request);
            // extraBody adds duplicate SDK fields instead of replacing them. Replace the
            // single serialized field at the transport boundary, preserving all options.
            if (replay != null || original != null) body.set("messages", replay == null ? JSON.valueToTree(original) : replay);
            return chain.proceed(request.newBuilder().removeHeader("Content-Length")
                    .method(request.method(), RequestBody.create(JSON.writeValueAsBytes(body), request.body().contentType())).build());
        });
    }
    public static boolean needed(List<Message> messages) {
        for (Message m : messages) if (m instanceof AssistantMessage a) {
            Object value = a.getMetadata().get("reasoningContent");
            if (value instanceof String s && s.isEmpty()) return true;
        }
        return false;
    }
    public static List<Map<String, Object>> messages(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage t) {
                for (var response : t.getResponses()) result.add(Map.of("role", "tool", "tool_call_id", response.id(), "name", response.name(), "content", response.responseData()));
            } else {
                Map<String, Object> item = new LinkedHashMap<>(); item.put("role", m.getMessageType().getValue()); item.put("content", Objects.toString(m.getText(), ""));
                if (m instanceof AssistantMessage a) {
                    if (!a.getToolCalls().isEmpty()) item.put("tool_calls", a.getToolCalls().stream().map(c -> {
                        Map<String, Object> call = new LinkedHashMap<>(); call.put("id", c.id()); call.put("type", c.type());
                        call.put("function", Map.of("name", c.name(), "arguments", c.arguments()));
                        Object extra = a.getMetadata().get("openai.tool_calls.additional_properties");
                        if (extra instanceof Map<?, ?> extras) {
                            Object properties = extras.get(c.id());
                            if (properties instanceof String encoded) {
                                try { properties = JSON.readValue(encoded, Map.class); }
                                catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid saved tool properties", invalid); }
                            }
                            if (properties instanceof Map<?, ?> values)
                                values.forEach((key, value) -> { if (!call.containsKey(key.toString())) call.put(key.toString(), value); });
                        }
                        return call;
                    }).toList());
                    if (a.getMetadata().get("reasoningContent") instanceof String s) item.put("reasoning_content", s);
                }
                result.add(item);
            }
        }
        return result;
    }
}
