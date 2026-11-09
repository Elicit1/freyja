package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.util.CryptoUtil;
import com.astra.freyja.service.video.VideoBatchPreparation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShotVideoCapabilityService {
    private final AiModelMapper models;
    private final AiProviderMapper providers;
    private final CryptoUtil crypto;
    private final ObjectMapper json;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public List<ShotVideoModelOptionVO> options(Long providerId) {
        AiProvider provider = provider(providerId);
        return models.selectList(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getModelType, "TXT2VIDEO_REF").eq(AiModel::getStatus, 1).orderByAsc(AiModel::getSortOrder))
                .stream().map(model -> describe(model, provider)).toList();
    }

    public ShotVideoModelOptionVO describe(AiModel model) { return describe(model, provider(model.getProviderId())); }

    public ShotVideoModelOptionVO describe(AiModel model, AiProvider provider) {
        var result = new ShotVideoModelOptionVO();
        result.setId(model.getId()); result.setProviderId(model.getProviderId()); result.setModelCode(model.getModelCode());
        result.setModelName(model.getModelName()); result.setMaxImages(model.getMaxImages()); result.setMaxAudios(model.getMaxAudios()); result.setMaxVideos(model.getMaxVideos());
        result.setModelFingerprint(fingerprint(model, provider));
        var local = new ReferenceVideoCapability();
        result.setReferenceVideo(local);
        try {
            var root = json.readTree(model.getParamsJson() == null || model.getParamsJson().isBlank() ? "{}" : model.getParamsJson()).path("videoGeneration").path("referenceVideo");
            if (model.getMaxVideos() == null || model.getMaxVideos() < 1) {
                result.setErrorCode("REFERENCE_VIDEO_UNSUPPORTED"); result.setReason("模型中心未开放参考视频数量；仍可保存素材草稿"); return result;
            }
            String base = apiRoot(provider.getBaseUrl());
            var request = HttpRequest.newBuilder(URI.create(base + "/models/" + URLEncoder.encode(model.getModelCode(), StandardCharsets.UTF_8) + "/capabilities"))
                    .timeout(Duration.ofSeconds(5)).GET().header("Accept", "application/json");
            String apiKey = crypto.decrypt(provider.getApiKey());
            if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
            var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { result.setReason("网关未提供此模型的参考视频能力契约"); return result; }
            var remote = json.readTree(response.body());
            result.setNodeId(remote.path("nodeId").asText(null)); result.setWorkflowRevision(remote.path("workflowRevision").asText(null));
            result.setHealthy(remote.path("healthy").asBoolean(false));
            var capability = json.treeToValue(remote.path("referenceVideo"), ReferenceVideoCapability.class);
            if (!complete(capability)) { result.setErrorCode("REFERENCE_VIDEO_UNSUPPORTED"); result.setReason("网关不支持参考视频或媒体限制配置不完整"); return result; }
            if (result.getNodeId() == null || result.getWorkflowRevision() == null
                    || root.hasNonNull("adapter") && !root.path("adapter").asText().equals(capability.getAdapter())
                    || root.hasNonNull("decodeFps") && root.path("decodeFps").asInt() != capability.getDecodeFps()) {
                result.setErrorCode("REFERENCE_VIDEO_WORKFLOW_MISMATCH"); result.setReason("模型与网关的参考视频适配器或帧率配置不一致"); return result;
            }
            // Use the live contract. Optional model-center limits may narrow it;
            // historical acceptance stamps/revisions do not prevent fresh preparation.
            var effective = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(capability);
            for(String field:List.of("maxDecodedFrames","maxDecodeWidth","maxDecodeHeight","maxMixedAssets"))
                if(root.hasNonNull(field))effective.put(field,Math.min(root.path(field).asInt(),effective.path(field).asInt()));
            for(String field:List.of("maxClipSeconds","maxTotalClipSeconds"))
                if(root.hasNonNull(field))effective.put(field,root.path(field).decimalValue().min(effective.path(field).decimalValue()));
            if(root.hasNonNull("minClipSeconds"))effective.put("minClipSeconds",root.path("minClipSeconds").decimalValue().max(capability.getMinClipSeconds()));
            if(root.hasNonNull("maxDownloadBytes"))effective.put("maxDownloadBytes",Math.min(root.path("maxDownloadBytes").asLong(),capability.getMaxDownloadBytes()==null?Long.MAX_VALUE:capability.getMaxDownloadBytes()));
            if(root.hasNonNull("supportsVideoAudio"))effective.put("supportsVideoAudio",root.path("supportsVideoAudio").asBoolean()&&Boolean.TRUE.equals(capability.getSupportsVideoAudio()));
            local=json.treeToValue(effective,ReferenceVideoCapability.class);result.setReferenceVideo(local);
            local.setMaxVideos(Math.min(model.getMaxVideos(),capability.getMaxVideos()));
            local.setWorkflowRevision(result.getWorkflowRevision());
            if (!complete(local)) { result.setErrorCode("REFERENCE_VIDEO_WORKFLOW_MISMATCH"); result.setReason("平台与网关的参考视频限制没有有效交集"); return result; }
            if (capability.getMaxDownloadBytes() != null) local.setMaxDownloadBytes(local.getMaxDownloadBytes() == null ? capability.getMaxDownloadBytes() : Math.min(local.getMaxDownloadBytes(), capability.getMaxDownloadBytes()));
            result.setRenderable(Boolean.TRUE.equals(result.getHealthy()));
            result.setErrorCode(Boolean.TRUE.equals(result.getHealthy()) ? null : "COMPUTE_NODE_OFFLINE");
            result.setReason(Boolean.TRUE.equals(result.getHealthy()) ? null : "算力节点离线，可准备素材，恢复在线后再渲染");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); result.setReason("读取网关能力已中断");
        } catch (Exception e) { result.setErrorCode("REFERENCE_VIDEO_GATEWAY_UNAVAILABLE"); result.setReason("网关不可达或参考视频能力配置无效，可保存素材草稿"); }
        return result;
    }

    public void requireRenderable(ShotVideoModelOptionVO option) {
        if (!Boolean.TRUE.equals(option.getRenderable())) {
            throw new BizException(409, option.getErrorCode() + ": " + option.getReason());
        }
    }

    public String fingerprint(AiModel model, AiProvider provider) {
        return VideoBatchPreparation.hash(String.join("|", String.valueOf(model.getId()), String.valueOf(model.getProviderId()), String.valueOf(model.getModelCode()),
                String.valueOf(model.getParamsJson()), String.valueOf(model.getMaxVideos()), String.valueOf(model.getMaxImages()), String.valueOf(model.getMaxAudios()),
                String.valueOf(model.getStatus()), String.valueOf(provider.getStatus()), String.valueOf(provider.getBaseUrl())));
    }

    public static boolean complete(ReferenceVideoCapability cap) {
        return cap != null && Integer.valueOf(1).equals(cap.getSchemaVersion())
                && "MINIMAX_H3_REF_VIDEO_V1".equals(cap.getAdapter()) && cap.getMaxVideos() != null && cap.getMaxVideos() > 0
                && positive(cap.getMinClipSeconds()) && positive(cap.getMaxClipSeconds()) && positive(cap.getMaxTotalClipSeconds())
                && cap.getMaxClipSeconds().compareTo(cap.getMinClipSeconds()) >= 0 && cap.getDecodeFps() != null && cap.getDecodeFps() > 0
                && cap.getMaxDecodedFrames() != null && cap.getMaxDecodedFrames() > 0 && cap.getMaxDecodeWidth() != null && cap.getMaxDecodeWidth() >= 2
                && cap.getMaxDecodeHeight() != null && cap.getMaxDecodeHeight() >= 2 && cap.getMaxMixedAssets() != null && cap.getMaxMixedAssets() > 0;
    }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private AiProvider provider(Long id) {
        var provider = providers.selectById(id);
        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus()) || provider.getBaseUrl() == null) throw new BizException(400, "请选择已启用的视频提供商");
        return provider;
    }
    public static String apiRoot(String baseUrl) {
        String base = baseUrl.replaceAll("/+$", "");
        for (String suffix : List.of("/videos/generations", "/images/generations")) if (base.endsWith(suffix)) base = base.substring(0, base.length() - suffix.length());
        return base.endsWith("/v1") ? base : base + "/v1";
    }
}
