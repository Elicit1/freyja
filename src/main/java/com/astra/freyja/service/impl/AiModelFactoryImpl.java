package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 模型动态工厂实现：按提供商接入类型构建对应 ChatModel，实例缓存于内存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelFactoryImpl implements AiModelFactory {

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    @Override
    public ChatModel getChatModel(Long providerId, String modelCode) {
        if (providerId == null || StringUtils.isBlank(modelCode)) {
            return getChatModelOrDefault(providerId, modelCode);
        }
        String key = providerId + ":" + modelCode;
        ChatModel cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        AiProvider provider = providerMapper.selectById(providerId);
        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus())) {
            throw new BizException("AI 提供商不存在或已停用");
        }
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getModelCode, modelCode));
        if (model == null || !Integer.valueOf(1).equals(model.getStatus())) {
            throw new BizException("AI 模型不存在或已停用");
        }
        ChatModel delegate = build(provider, model);
        ChatModel wrapped = new CircuitBreakerChatModel(delegate, provider.getProviderName(),
                provider.getEnableBreaker(), provider.getBreakerThreshold(), provider.getBreakerTimeout());
        ChatModel existing = cache.putIfAbsent(key, wrapped);
        return existing != null ? existing : wrapped;
    }

    @Override
    public ChatModel getChatModelOrDefault(Long providerId, String modelCode) {
        Long targetProviderId = providerId;
        String targetModelCode = modelCode;

        if (targetProviderId == null) {
            AiProvider defaultProvider = providerMapper.selectOne(new LambdaQueryWrapper<AiProvider>()
                    .eq(AiProvider::getStatus, 1)
                    .orderByAsc(AiProvider::getId)
                    .last("LIMIT 1"));
            if (defaultProvider == null) {
                throw new BizException("系统未配置可用的 AI 提供商");
            }
            targetProviderId = defaultProvider.getId();
        }

        if (StringUtils.isBlank(targetModelCode)) {
            AiModel defaultModel = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getProviderId, targetProviderId)
                    .eq(AiModel::getModelType, "CHAT")
                    .eq(AiModel::getStatus, 1)
                    .orderByAsc(AiModel::getId)
                    .last("LIMIT 1"));
            if (defaultModel != null) {
                targetModelCode = defaultModel.getModelCode();
            } else {
                targetModelCode = "deepseek-chat";
            }
        }

        String key = targetProviderId + ":" + targetModelCode;
        ChatModel cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        AiProvider provider = providerMapper.selectById(targetProviderId);
        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus())) {
            throw new BizException("AI 提供商不存在或已停用");
        }
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, targetProviderId)
                .eq(AiModel::getModelCode, targetModelCode));
        if (model == null || !Integer.valueOf(1).equals(model.getStatus())) {
            throw new BizException("AI 模型不存在或已停用");
        }
        ChatModel delegate = build(provider, model);
        ChatModel wrapped = new CircuitBreakerChatModel(delegate, provider.getProviderName(),
                provider.getEnableBreaker(), provider.getBreakerThreshold(), provider.getBreakerTimeout());
        ChatModel existing = cache.putIfAbsent(key, wrapped);
        return existing != null ? existing : wrapped;
    }

    @Override
    public void evict(Long providerId) {
        String prefix = providerId + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public void evict(Long providerId, String modelCode) {
        String key = providerId + ":" + modelCode;
        cache.keySet().removeIf(k -> k.equals(key) || k.startsWith(key + ":snapshot:"));
    }

    @Override
    public ChatModel getChatModelForSnapshot(Long providerId, AiModel snapshot) {
        if (providerId == null || snapshot == null || StringUtils.isBlank(snapshot.getModelCode()))
            throw new BizException("固定模型配置不能为空");
        AiProvider provider = providerMapper.selectById(providerId);
        AiModel current = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId).eq(AiModel::getModelCode, snapshot.getModelCode()));
        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus()) || current == null
                || !Integer.valueOf(1).equals(current.getStatus()) || !"CHAT".equals(current.getModelType()))
            throw new BizException("固定模型使用的提供商或模型已停用");
        String fingerprint;
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(snapshot);
            fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (Exception e) { throw new BizException("固定模型配置序列化失败"); }
        String key = providerId + ":" + snapshot.getModelCode() + ":snapshot:" + fingerprint;
        return cache.computeIfAbsent(key, ignored -> new CircuitBreakerChatModel(build(provider, snapshot), provider.getProviderName(),
                provider.getEnableBreaker(), provider.getBreakerThreshold(), provider.getBreakerTimeout()));
    }

    private ChatModel build(AiProvider provider, AiModel model) {
        if (TYPE_OLLAMA.equals(provider.getProviderType())) {
            return buildOllama(provider, model);
        }
        return buildOpenAi(provider, model);
    }

    private ChatModel buildOpenAi(AiProvider provider, AiModel model) {
        Integer maxTokens = model.getMaxTokens() != null && model.getMaxTokens() > 0 ? model.getMaxTokens() : 16384;
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(provider.getBaseUrl())
                .model(model.getModelCode())
                .temperature(model.getTemperature())
                .maxTokens(maxTokens)
                .topP(model.getTopP())
                .timeout(Duration.ofSeconds(provider.getTimeout() == null ? 120 : provider.getTimeout()))
                .maxRetries(provider.getMaxRetries() == null ? 0 : provider.getMaxRetries());
        if ("max_completion_tokens".equals(com.astra.freyja.agent.model.ChatModelCapabilities.from(model).outputTokenParameter()))
            options.maxTokens(null).maxCompletionTokens(maxTokens);
        String apiKey = cryptoUtil.decrypt(provider.getApiKey());
        if (StringUtils.isNotBlank(apiKey)) {
            options.apiKey(apiKey);
        }
        applyOpenAiExtras(options, model.getParamsJson());
        return OpenAiChatModel.builder().options(options.build())
                .httpClientBuilderCustomizer(com.astra.freyja.agent.gateway.ReasoningReplay.customizer()).build();
    }

    private ChatModel buildOllama(AiProvider provider, AiModel model) {
        String baseUrl = StringUtils.isNotBlank(provider.getBaseUrl())
                ? provider.getBaseUrl() : "http://localhost:11434";
        Integer maxTokens = model.getMaxTokens() != null && model.getMaxTokens() > 0 ? model.getMaxTokens() : 16384;
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
        OllamaChatOptions.Builder options = OllamaChatOptions.builder()
                .model(model.getModelCode())
                .temperature(model.getTemperature())
                .numPredict(maxTokens)
                .topP(model.getTopP());
        applyOllamaExtras(options, model.getParamsJson());
        return OllamaChatModel.builder().ollamaApi(api).options(options.build()).build();
    }

    private void applyOpenAiExtras(OpenAiChatOptions.Builder builder, String paramsJson) {
        if (!StringUtils.isNotBlank(paramsJson)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            if (node == null || !node.isObject()) {
                return;
            }
            JsonNode fp = node.get("frequency_penalty");
            if (fp != null && fp.isNumber()) {
                builder.frequencyPenalty(fp.asDouble());
            }
            JsonNode pp = node.get("presence_penalty");
            if (pp != null && pp.isNumber()) {
                builder.presencePenalty(pp.asDouble());
            }
            JsonNode stop = node.get("stop");
            if (stop != null && stop.isArray()) {
                List<String> stops = new ArrayList<>();
                stop.forEach(s -> stops.add(s.asText()));
                if (!stops.isEmpty()) {
                    builder.stop(stops);
                }
            }
            JsonNode seed = node.get("seed");
            if (seed != null && seed.isNumber()) {
                builder.seed(seed.asInt());
            }
            java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
            if (node.path("reasoning_effort").isTextual()) builder.reasoningEffort(node.path("reasoning_effort").asText());
            for (String key : List.of("thinking")) if (node.has(key)) extra.put(key, objectMapper.convertValue(node.get(key), Object.class));
            if (!extra.isEmpty()) builder.extraBody(extra);
        } catch (Exception e) {
            log.warn("解析模型额外参数失败: {}", paramsJson, e);
        }
    }

    private void applyOllamaExtras(OllamaChatOptions.Builder builder, String paramsJson) {
        if (!StringUtils.isNotBlank(paramsJson)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            if (node == null || !node.isObject()) {
                return;
            }
            JsonNode topK = node.get("top_k");
            if (topK != null && topK.isNumber()) {
                builder.topK(topK.asInt());
            }
            JsonNode repeatPenalty = node.get("repeat_penalty");
            if (repeatPenalty != null && repeatPenalty.isNumber()) {
                builder.repeatPenalty(repeatPenalty.asDouble());
            }
            if (node.has("think")) { if (node.path("think").asBoolean()) builder.enableThinking(); else builder.disableThinking(); }
        } catch (Exception e) {
            log.warn("解析模型额外参数失败: {}", paramsJson, e);
        }
    }
}
