package com.astra.freyja.agent.model;

import com.astra.freyja.common.BizException;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declared provider capabilities; never inferred from a model's name. */
public record ChatModelCapabilities(Integer contextWindow, Integer maxOutputTokens, Integer recommendedOutputTokens,
                                    String reasoningProtocol, List<String> reasoningEfforts, String outputTokenParameter) {
    public static final Set<String> PROTOCOLS = Set.of("NONE", "DEEPSEEK_CHAT", "OPENAI", "OLLAMA", "MIMO_CHAT");
    private static final Set<String> FIELDS = Set.of("contextWindow", "maxOutputTokens", "recommendedOutputTokens",
            "reasoningProtocol", "reasoningEfforts", "outputTokenParameter", "toolCalling");
    private static final ObjectMapper JSON = new ObjectMapper();
    public static ChatModelCapabilities from(AiModel model) { return from(model == null ? null : model.getParamsJson()); }
    public static ChatModelCapabilities from(AiModel model, AiProvider provider) { return from(resolve(model, provider).getParamsJson()); }

    /** Resolve once for normal calls or run snapshots. Pinned snapshots must not inherit again. */
    public static AiModel resolve(AiModel model, AiProvider provider) {
        var resolved = new AiModel();
        if (model != null) org.springframework.beans.BeanUtils.copyProperties(model, resolved);
        try {
            var root = object(model == null ? null : model.getParamsJson());
            var defaults = object(provider == null ? null : provider.getChatCapabilitiesJson());
            var overrides = root.get("capabilities");
            if (overrides != null && !overrides.isNull() && !overrides.isObject()) throw new BizException("模型 capabilities 必须是 JSON 对象");
            if (overrides != null && overrides.isObject()) overrides.properties().forEach(entry -> {
                if (!entry.getValue().isNull()) defaults.set(entry.getKey(), entry.getValue());
            });
            // Freeze even the legacy fallbacks, so later provider edits cannot affect a run.
            if (!defaults.hasNonNull("reasoningProtocol")) defaults.put("reasoningProtocol", "NONE");
            if (!defaults.hasNonNull("outputTokenParameter")) defaults.put("outputTokenParameter", "max_tokens");
            root.set("capabilities", defaults);
            resolved.setParamsJson(JSON.writeValueAsString(root));
            from(resolved);
            return resolved;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException("对话能力默认配置格式无效"); }
    }
    public static void validateDefaults(String parameters) {
        try {
            var defaults = object(parameters);
            for (var entry : defaults.properties()) if (!FIELDS.contains(entry.getKey())) throw new BizException("提供商默认能力不支持字段：" + entry.getKey());
            var root = JSON.createObjectNode(); root.set("capabilities", defaults);
            from(JSON.writeValueAsString(root));
            if (defaults.hasNonNull("toolCalling") && !defaults.get("toolCalling").isBoolean()) throw new BizException("toolCalling 必须为布尔值");
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException("提供商默认能力必须是 JSON 对象"); }
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode object(String value) throws java.io.IOException {
        if (value == null || value.isBlank()) return JSON.createObjectNode();
        var node = JSON.readTree(value);
        if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode result)) throw new BizException("能力配置必须是 JSON 对象");
        return result;
    }
    public static ChatModelCapabilities from(String params) {
        try {
            var root = params == null || params.isBlank() ? JSON.createObjectNode() : JSON.readTree(params);
            var node = root.path("capabilities");
            var efforts = node.path("reasoningEfforts");
            var list = new java.util.ArrayList<String>();
            if (efforts.isArray()) efforts.forEach(e -> list.add(e.asText()));
            var c = new ChatModelCapabilities(integer(node, "contextWindow"), integer(node, "maxOutputTokens"),
                    integer(node, "recommendedOutputTokens"), node.path("reasoningProtocol").asText("NONE"), List.copyOf(list), node.path("outputTokenParameter").asText("max_tokens"));
            if (!PROTOCOLS.contains(c.reasoningProtocol())) throw new BizException("不支持的思考参数协议");
            if ("MIMO_CHAT".equals(c.reasoningProtocol()) && !c.reasoningEfforts().isEmpty())
                throw new BizException("MiMo 仅支持思考开关，不支持推理强度列表");
            if (!Set.of("max_tokens", "max_completion_tokens").contains(c.outputTokenParameter())) throw new BizException("不支持的输出预算参数");
            if (c.contextWindow() != null && c.contextWindow() < 2048 || c.maxOutputTokens() != null && c.maxOutputTokens() < 1
                    || c.recommendedOutputTokens() != null && c.recommendedOutputTokens() < 1)
                throw new BizException("模型能力的 Token 数必须为正整数，上下文至少 2048 Tokens");
            if (c.maxOutputTokens() != null && c.recommendedOutputTokens() != null && c.recommendedOutputTokens() > c.maxOutputTokens())
                throw new BizException("建议输出预算不能超过模型最大输出");
            return c;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException("CHAT 模型能力参数格式无效"); }
    }
    private static Integer integer(com.fasterxml.jackson.databind.JsonNode node, String name) {
        var v = node.get(name);
        if (v == null || v.isNull()) return null;
        if (!v.isIntegralNumber() || !v.canConvertToInt()) throw new BizException("模型能力 " + name + " 必须是整数");
        return v.intValue();
    }
    public int outputLimit() { return maxOutputTokens == null ? Integer.MAX_VALUE : maxOutputTokens; }
    public int contextLimit() { return contextWindow == null ? Integer.MAX_VALUE : contextWindow; }
    public int recommended(AiModel model) {
        int value = recommendedOutputTokens != null ? recommendedOutputTokens
                : model != null && model.getMaxTokens() != null && model.getMaxTokens() > 0 ? model.getMaxTokens() : 65536;
        return Math.min(value, Math.min(outputLimit(), contextLimit() == Integer.MAX_VALUE ? Integer.MAX_VALUE : contextLimit() / 4));
    }
    public Map<String, Object> reasoningExtras(String mode) {
        if (mode == null || "INHERIT".equals(mode) || "NONE".equals(reasoningProtocol)) return Map.of();
        if ("MIMO_CHAT".equals(reasoningProtocol)) {
            if (!Set.of("DISABLED", "ENABLED").contains(mode)) throw new BizException("MiMo 仅支持跟随模型、开启思考或关闭思考");
            return Map.of("thinking", Map.of("type", "DISABLED".equals(mode) ? "disabled" : "enabled"));
        }
        if ("ENABLED".equals(mode)) return switch (reasoningProtocol) {
            case "DEEPSEEK_CHAT" -> Map.of("thinking", Map.of("type", "enabled"));
            case "OLLAMA" -> Map.of("think", true);
            default -> throw new BizException("此协议需要选择具体推理强度");
        };
        String effort = "DISABLED".equals(mode) ? "none" : mode.toLowerCase(java.util.Locale.ROOT);
        if (!"none".equals(effort) && !reasoningEfforts.isEmpty() && !reasoningEfforts.contains(effort))
            throw new BizException("模型不支持该推理强度");
        return switch (reasoningProtocol) {
            case "DEEPSEEK_CHAT" -> "none".equals(effort)
                    ? Map.of("thinking", Map.of("type", "disabled"), "reasoning_effort", "none")
                    : Map.of("thinking", Map.of("type", "enabled"), "reasoning_effort", effort);
            case "OPENAI" -> Map.of("reasoning_effort", effort);
            case "OLLAMA" -> Map.of("think", !"none".equals(effort));
            default -> Map.of();
        };
    }
}
