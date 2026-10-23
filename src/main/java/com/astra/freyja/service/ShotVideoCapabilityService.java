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
            var root = json.readTree(model.getParamsJson() == null ? "{}" : model.getParamsJson()).path("videoGeneration").path("referenceVideo");
            if (root.isMissingNode() || root.isNull() || model.getMaxVideos() == null || model.getMaxVideos() < 1) {
                result.setErrorCode("REFERENCE_VIDEO_UNSUPPORTED"); result.setReason("该模型尚未配置参考视频能力；仍可保存素材草稿"); return result;
            }
            local = json.treeToValue(root, ReferenceVideoCapability.class); result.setReferenceVideo(local);
            // Model-center maxVideos is the platform count setting; the verified gateway may narrow it.
            local.setMaxVideos(model.getMaxVideos());
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
            if (!complete(local) || !complete(capability)) { result.setReason("参考视频能力尚未完成在线验证"); return result; }
            if (!java.util.Objects.equals(local.getWorkflowRevision(), result.getWorkflowRevision()) || !java.util.Objects.equals(local.getAdapter(), capability.getAdapter()) || result.getNodeId() == null) {
                result.setErrorCode("REFERENCE_VIDEO_WORKFLOW_MISMATCH"); result.setReason("模型与网关的参考视频版本不一致，请重新验收配置"); return result;
            }
            local.setMaxVideos(Math.min(model.getMaxVideos(), Math.min(local.getMaxVideos(), capability.getMaxVideos())));
            local.setMinClipSeconds(local.getMinClipSeconds().max(capability.getMinClipSeconds()));
            local.setMaxClipSeconds(local.getMaxClipSeconds().min(capability.getMaxClipSeconds()));
            local.setMaxTotalClipSeconds(local.getMaxTotalClipSeconds().min(capability.getMaxTotalClipSeconds()));
            local.setMaxDecodedFrames(Math.min(local.getMaxDecodedFrames(), capability.getMaxDecodedFrames()));
            local.setMaxDecodeWidth(Math.min(local.getMaxDecodeWidth(), capability.getMaxDecodeWidth()));
            local.setMaxDecodeHeight(Math.min(local.getMaxDecodeHeight(), capability.getMaxDecodeHeight()));
            local.setMaxMixedAssets(Math.min(local.getMaxMixedAssets(), capability.getMaxMixedAssets()));
            if (!local.getDecodeFps().equals(capability.getDecodeFps())) { result.setReason("模型与网关的参考视频帧率约定不一致"); return result; }
            local.setSupportsVideoAudio(Boolean.TRUE.equals(local.getSupportsVideoAudio()) && Boolean.TRUE.equals(capability.getSupportsVideoAudio()));
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
        return cap != null && "VERIFIED".equals(cap.getStatus()) && Integer.valueOf(1).equals(cap.getSchemaVersion())
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
