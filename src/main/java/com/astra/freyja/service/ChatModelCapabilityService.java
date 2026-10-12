package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Service @RequiredArgsConstructor
public class ChatModelCapabilityService {
    public record Discovered(String modelCode, Integer contextWindow, Integer maxOutputTokens, List<String> reasoningEfforts) {}
    private final AiProviderMapper providers;
    private final CryptoUtil crypto;
    private final ObjectMapper json;
    public List<Discovered> discover(Long providerId) {
        var provider = providers.selectById(providerId);
        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus())) throw new BizException("提供商不存在或未启用");
        if (!"OPENAI".equals(provider.getProviderType())) throw new BizException("此提供商暂不支持 /models 能力发现，请手动填写");
        var uri = java.net.URI.create(provider.getBaseUrl());
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) throw new BizException("提供商地址无效");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
        factory.setReadTimeout(Duration.ofSeconds(Math.min(60, provider.getTimeout() == null ? 30 : provider.getTimeout())));
        String key = crypto.decrypt(provider.getApiKey());
        var builder = RestClient.builder().requestFactory(factory);
        if (key != null && !key.isBlank()) builder.defaultHeader("Authorization", "Bearer " + key);
        var client = builder.build();
        String base = provider.getBaseUrl(); while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String response;
        try {
            try { response = client.get().uri(base + "/models").retrieve().body(String.class); }
            catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != 404 || base.endsWith("/v1")) throw e;
                response = client.get().uri(base + "/v1/models").retrieve().body(String.class);
            }
            var data = json.readTree(response).path("data");
            if (!data.isArray()) throw new BizException("提供商没有返回标准模型列表，请手动填写能力");
            var result = new java.util.ArrayList<Discovered>();
            for (var model : data) {
                if (!model.path("id").isTextual()) continue;
                var levels = new java.util.ArrayList<String>();
                if (model.path("effort").path("supported_levels").isArray()) model.path("effort").path("supported_levels").forEach(v -> levels.add(v.asText()));
                result.add(new Discovered(model.path("id").asText(), positive(model.path("context_window")), positive(model.path("max_output_tokens")), List.copyOf(levels)));
                if (result.size() >= 1000) break;
            }
            return List.copyOf(result);
        } catch (BizException e) { throw e; }
        catch (RestClientResponseException e) { throw new BizException("能力发现请求失败，HTTP " + e.getStatusCode().value()); }
        catch (Exception e) { throw new BizException("能力发现失败，请检查连接或手动填写能力"); }
    }
    private Integer positive(com.fasterxml.jackson.databind.JsonNode n) { return n.isIntegralNumber() && n.canConvertToInt() && n.intValue() > 0 ? n.intValue() : null; }
}
