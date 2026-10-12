package com.astra.freyja.agent.gateway;

import com.astra.freyja.agent.model.ChatModelCapabilities;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Apply declared protocol rules after the SDK has merged model and per-call defaults. */
final class OpenAiRequestCompatibility {
    private OpenAiRequestCompatibility() {}

    static boolean normalize(ObjectNode body, ChatModelCapabilities capabilities) {
        if (capabilities == null) return false;
        String protocol = capabilities.reasoningProtocol();
        if (!"DEEPSEEK_CHAT".equals(protocol) && !"MIMO_CHAT".equals(protocol)) return false;
        // Both protocols enable thinking by default. No model-name inference is needed.
        boolean thinking = !"disabled".equals(body.path("thinking").path("type").asText());
        if ("MIMO_CHAT".equals(protocol)) {
            body.remove("reasoning_effort");
            if (thinking) { body.remove("temperature"); body.remove("top_p"); }
        } else {
            if (!thinking) { body.remove("reasoning_effort"); body.remove("top_p"); }
            else {
                body.remove("temperature"); body.remove("presence_penalty"); body.remove("frequency_penalty");
                if (body.path("top_p").isNumber()) body.put("top_p", Math.clamp(body.path("top_p").asDouble(), 0.95, 1.0));
            }
        }
        return true;
    }
}
