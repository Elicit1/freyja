package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dto.drama.DramaShotFirstFrameDTO;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.AiImageApiService;
import com.astra.freyja.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 云端/第三方 AI 生图服务实现 (OpenAI 规范 /v1/images/generations)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiImageApiServiceImpl implements AiImageApiService {

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final CryptoUtil cryptoUtil;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final ObjectMapper objectMapper;
    private final com.astra.freyja.service.VideoFrameExtractService videoFrameExtractService;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ThreadLocal<String> archivePrefix = new ThreadLocal<>();

    @Override
    public String generateAndArchiveImage(Long shotId, Long dramaId, String prompt, DramaShotFirstFrameDTO dto) {
        if (StringUtils.isBlank(prompt)) {
            throw new BizException(400, "生图提示词不能为空");
        }

        // 1. 获取 AI 提供商
        AiProvider provider = resolveProvider(dto != null ? dto.getProviderId() : null);
        String apiKey = cryptoUtil.decrypt(provider.getApiKey());
        String baseUrl = provider.getBaseUrl();
        if (StringUtils.isBlank(baseUrl)) {
            throw new BizException(400, "AI 提供商未配置 baseUrl: " + provider.getProviderName());
        }

        // 2. 获取模型标识
        String modelCode = resolveModelCode(dto, provider.getId());

        // 3. 构造请求 Payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", modelCode);
        payload.put("prompt", prompt);
        payload.put("n", 1);
        String size = (dto != null && StringUtils.isNotBlank(dto.getSize())) ? dto.getSize() : "1024x1024";
        payload.put("size", size);
        payload.put("response_format", "url");

        if (dto != null && StringUtils.isNotBlank(dto.getTaskId())) {
            payload.put("task_id", dto.getTaskId().trim());
        }
        if (shotId != null) {
            payload.put("shot_id", String.valueOf(shotId));
        }

        if (dto != null && dto.getSeed() != null && dto.getSeed() >= 0) {
            payload.put("seed", dto.getSeed());
        }

        if (dto != null && StringUtils.isNotBlank(dto.getNegativePrompt())) {
            payload.put("negative_prompt", dto.getNegativePrompt().trim());
        }

        if (dto != null && dto.getReferenceImageUrls() != null && !dto.getReferenceImageUrls().isEmpty()) {
            List<String> validRefs = dto.getReferenceImageUrls().stream()
                    .filter(StringUtils::isNotBlank)
                    .map(String::trim)
                    .toList();
            if (!validRefs.isEmpty()) {
                AiModel modelObj = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getProviderId, provider.getId())
                        .eq(AiModel::getModelCode, modelCode)
                        .last("LIMIT 1"));
                if (modelObj != null && modelObj.getMaxImages() != null && validRefs.size() > modelObj.getMaxImages()) {
                    throw new BizException(400, "参考图数量 (" + validRefs.size() + ") 超过了模型最大允许数量 (" + modelObj.getMaxImages() + " 张)");
                }

                List<String> resolvedRefs = new ArrayList<>();
                for (String ref : validRefs) {
                    resolvedRefs.add(prepareReferenceImage(ref));
                }
                payload.put("ref_images", resolvedRefs);
                Map<String, Object> extraBody = new HashMap<>();
                extraBody.put("ref_images", resolvedRefs);
                payload.put("extra_body", extraBody);
                log.info("[AiImageApi] 携带参考图生成: count={}", resolvedRefs.size());
            }
        }

        String requestUrl = buildEndpointUrl(baseUrl);
        log.info("[AiImageApi] 正在调用云端生图 API: url={}, model={}, promptLen={}",
                requestUrl, modelCode, prompt.length());

        byte[] imageBytes;
        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                    .timeout(Duration.ofSeconds(provider.getTimeout() != null && provider.getTimeout() > 0 ? provider.getTimeout() : 600))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.error("[AiImageApi] 云端生图 API 请求失败: status={}, body={}", response.statusCode(), response.body());
                throw new BizException(500, "云端生图接口调用失败 (" + response.statusCode() + "): " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray() || dataArray.isEmpty()) {
                throw new BizException(500, "云端生图返回数据为空: " + response.body());
            }

            JsonNode firstItem = dataArray.get(0);
            if (firstItem.hasNonNull("url")) {
                String remoteImageUrl = firstItem.get("url").asText();
                String targetUrl = resolveRemoteImageUrl(remoteImageUrl, baseUrl);
                log.info("[AiImageApi] 成功获取远程图片 URL，正在下载转存至 MinIO: targetUrl={}, originalUrl={}", targetUrl, remoteImageUrl);
                imageBytes = downloadRemoteImage(targetUrl);
            } else if (firstItem.hasNonNull("b64_json")) {
                String b64 = firstItem.get("b64_json").asText();
                imageBytes = Base64.getDecoder().decode(b64);
            } else {
                throw new BizException(500, "云端生图未返回有效的 url 或 b64_json");
            }
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.error("[AiImageApi] 云端生图执行异常: error={}", e.getMessage(), e);
            throw new BizException(500, "云端生图执行异常: " + e.getMessage());
        }

        // 4. 上传归档至 MinIO
        return uploadToMinio(shotId, dramaId, imageBytes);
    }

    @Override
    public String generateAndArchiveAssetImage(String targetType, Long targetId, String slot,
                                               String prompt, DramaShotFirstFrameDTO dto) {
        if (StringUtils.isBlank(targetType) || targetId == null || StringUtils.isBlank(slot)) {
            throw new BizException(400, "资产图片归档目标不能为空");
        }
        archivePrefix.set(String.format("assets/%s/%d/%s", targetType.toLowerCase(), targetId, slot.toLowerCase()));
        try {
            return generateAndArchiveImage(targetId, null, prompt, dto);
        } finally {
            archivePrefix.remove();
        }
    }

    @Override
    public AiProvider resolveProvider(Long providerId) {
        if (providerId != null && providerId > 0) {
            AiProvider p = providerMapper.selectById(providerId);
            if (p == null) {
                throw new BizException(404, "AI 提供商不存在");
            }
            if (!Integer.valueOf(1).equals(p.getStatus())) {
                throw new BizException(400, "AI 提供商已停用: " + p.getProviderName());
            }
            return p;
        }
        // 查找状态启用的提供商
        AiProvider p = providerMapper.selectOne(
                new LambdaQueryWrapper<AiProvider>()
                        .eq(AiProvider::getStatus, 1)
                        .orderByAsc(AiProvider::getId)
                        .last("LIMIT 1")
        );
        if (p == null) {
            throw new BizException(404, "未找到已启用的 AI 提供商配置");
        }
        return p;
    }

    private static final List<String> IMAGE_MODEL_TYPES = List.of("TXT_IMG2IMG", "TXT2IMG", "IMAGE");

    @Override
    public String resolveModelCode(DramaShotFirstFrameDTO dto, Long providerId) {
        if (dto != null && StringUtils.isNotBlank(dto.getModelCode())) {
            String requestedCode = dto.getModelCode().trim();
            AiModel selected = modelMapper.selectOne(
                    new LambdaQueryWrapper<AiModel>()
                            .eq(AiModel::getProviderId, providerId)
                            .eq(AiModel::getModelCode, requestedCode)
                            .in(AiModel::getModelType, IMAGE_MODEL_TYPES)
                            .eq(AiModel::getStatus, 1)
                            .last("LIMIT 1")
            );
            if (selected != null) {
                return selected.getModelCode();
            }

            // 若生图类型未匹配，检查该模型是否在提供商下被配置为其他类型或已停用
            AiModel anyModel = modelMapper.selectOne(
                    new LambdaQueryWrapper<AiModel>()
                            .eq(AiModel::getProviderId, providerId)
                            .eq(AiModel::getModelCode, requestedCode)
                            .last("LIMIT 1")
            );
            if (anyModel != null) {
                if (!Integer.valueOf(1).equals(anyModel.getStatus())) {
                    throw new BizException(400, "所选生图模型已停用: " + requestedCode);
                }
                throw new BizException(400, "所选模型类型为 [" + anyModel.getModelType() + "]，不是生图模型 (需为 TXT_IMG2IMG 或 TXT2IMG)");
            }

            // 若数据库未录入该模型，但用户在前端显式输入了模型标识，直接放行透传给网关
            return requestedCode;
        }

        // 查询提供商下启用的生图模型
        AiModel model = modelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getProviderId, providerId)
                        .in(AiModel::getModelType, IMAGE_MODEL_TYPES)
                        .eq(AiModel::getStatus, 1)
                        .orderByAsc(AiModel::getSortOrder)
                        .last("LIMIT 1")
        );
        if (model != null && StringUtils.isNotBlank(model.getModelCode())) {
            return model.getModelCode();
        }
        throw new BizException(400, "当前供应商未配置启用的文生图/图生图模型");
    }

    private String buildEndpointUrl(String baseUrl) {
        String clean = baseUrl.trim();
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/v1/images/generations") || clean.endsWith("/images/generations")) {
            return clean;
        }
        if (clean.endsWith("/v1")) {
            return clean + "/images/generations";
        }
        return clean + "/v1/images/generations";
    }

    private String resolveRemoteImageUrl(String remoteUrl, String providerBaseUrl) {
        if (StringUtils.isBlank(remoteUrl) || StringUtils.isBlank(providerBaseUrl)) {
            return remoteUrl;
        }
        try {
            URI imageUri = URI.create(remoteUrl);
            URI providerUri = URI.create(providerBaseUrl);
            String imageHost = imageUri.getHost();
            String providerHost = providerUri.getHost();

            if (("127.0.0.1".equals(imageHost) || "localhost".equalsIgnoreCase(imageHost))
                    && StringUtils.isNotBlank(providerHost)
                    && !"127.0.0.1".equals(providerHost)
                    && !"localhost".equalsIgnoreCase(providerHost)) {
                int port = imageUri.getPort() != -1 ? imageUri.getPort() : providerUri.getPort();
                String portPart = (port != -1 && port != 80 && port != 443) ? ":" + port : "";
                String correctedUrl = String.format("%s://%s%s%s%s",
                        providerUri.getScheme() != null ? providerUri.getScheme() : imageUri.getScheme(),
                        providerHost,
                        portPart,
                        imageUri.getRawPath() != null ? imageUri.getRawPath() : "",
                        StringUtils.isNotBlank(imageUri.getRawQuery()) ? "?" + imageUri.getRawQuery() : "");
                log.info("[AiImageApi] 发现远程图片 URL 包含本地回环地址 ({})，已智能校正为提供商网络地址: {}", remoteUrl, correctedUrl);
                return correctedUrl;
            }
        } catch (Exception e) {
            log.warn("[AiImageApi] 解析/校正远程图片 URL 失败，保留原始地址: url={}, err={}", remoteUrl, e.getMessage());
        }
        return remoteUrl;
    }

    private byte[] downloadRemoteBytes(String remoteUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(remoteUrl))
                    .timeout(Duration.ofSeconds(300))
                    .GET()
                    .build();
            HttpResponse<byte[]> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() >= 300) {
                throw new IllegalStateException("下载远程媒体资源失败 HTTP " + res.statusCode());
            }
            return res.body();
        } catch (Exception e) {
            throw new BizException(500, "下载远程媒体资源流失败: " + e.getMessage());
        }
    }

    private byte[] downloadRemoteImage(String remoteUrl) {
        return downloadRemoteBytes(remoteUrl);
    }

    private String prepareMediaAsBase64(String mediaUrl, String defaultMime) {
        if (StringUtils.isBlank(mediaUrl)) {
            return mediaUrl;
        }
        String trimmed = mediaUrl.trim();
        // 已经是 Base64 Data URI，直接透传
        if (trimmed.startsWith("data:") || trimmed.contains(";base64,")) {
            return trimmed;
        }

        byte[] bytes = null;
        String bucket = minioProperties.getBucketName();

        // 1. 优先尝试从本地 MinIO 存储中直接读取对象（解决跨网段/跨机器/WSL 虚拟子网无法回连 MinIO 的网络隔离问题）
        if (trimmed.contains("/" + bucket + "/")) {
            try {
                int bucketIndex = trimmed.indexOf("/" + bucket + "/");
                String objectName = trimmed.substring(bucketIndex + bucket.length() + 2);
                if (objectName.contains("?")) {
                    objectName = objectName.substring(0, objectName.indexOf("?"));
                }
                log.info("[AiImageApi] 正在从 MinIO 本地存储直接提取媒体文件: bucket={}, objectName={}", bucket, objectName);
                try (InputStream stream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectName)
                                .build())) {
                    bytes = stream.readAllBytes();
                }
            } catch (Exception e) {
                log.warn("[AiImageApi] 从 MinIO 提取媒体文件失败，尝试 HTTP 下载: err={}", e.getMessage());
            }
        }

        // 2. 若未从 MinIO 成功读取，尝试通过 HTTP 下载
        if (bytes == null && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            try {
                bytes = downloadRemoteBytes(trimmed);
            } catch (Exception e) {
                log.warn("[AiImageApi] HTTP 下载媒体文件失败: url={}, err={}", trimmed, e.getMessage());
            }
        }

        // 3. 将字节序列编码为 Base64 Data URI（网关原生免下载解码支持）
        if (bytes != null && bytes.length > 0) {
            String mime = defaultMime;
            String lower = trimmed.toLowerCase();
            if (lower.contains(".jpg") || lower.contains(".jpeg")) {
                mime = "image/jpeg";
            } else if (lower.contains(".png")) {
                mime = "image/png";
            } else if (lower.contains(".webp")) {
                mime = "image/webp";
            } else if (lower.contains(".mp4")) {
                mime = "video/mp4";
            } else if (lower.contains(".wav")) {
                mime = "audio/wav";
            } else if (lower.contains(".mp3")) {
                mime = "audio/mpeg";
            }
            String b64 = Base64.getEncoder().encodeToString(bytes);
            log.info("[AiImageApi] 成功将媒体文件转换为 Base64 Data URI (mime={}, size={} KB)",
                    mime, bytes.length / 1024);
            return "data:" + mime + ";base64," + b64;
        }

        return trimmed;
    }

    private String prepareReferenceImage(String refUrl) {
        return prepareMediaAsBase64(refUrl, "image/png");
    }

    private static final List<String> VIDEO_MODEL_TYPES = List.of(
            "TXT2VIDEO_REF", "TXT2VIDEO_FIRST_LAST", "TXT2VIDEO", "VIDEO", "I2V", "T2V", "IMG2VIDEO"
    );

    @Override
    public String resolveVideoModelCode(com.astra.freyja.dto.drama.DramaShotRenderRequestDTO dto, Long providerId, String generationMode) {
        if (dto != null && StringUtils.isNotBlank(dto.getWorkflowTemplateId())) {
            String requestedCode = dto.getWorkflowTemplateId().trim();
            AiModel selected = modelMapper.selectOne(
                    new LambdaQueryWrapper<AiModel>()
                            .eq(AiModel::getProviderId, providerId)
                            .eq(AiModel::getModelCode, requestedCode)
                            .in(AiModel::getModelType, VIDEO_MODEL_TYPES)
                            .eq(AiModel::getStatus, 1)
                            .last("LIMIT 1")
            );
            if (selected != null) {
                return selected.getModelCode();
            }
            // 若数据库没有严格类型匹配或未录入，放行透传给网关 (如 minimax-h3-fl2va 等)
            return requestedCode;
        }

        // 根据生成模式自动选型
        boolean isRefMode = "REFERENCE_MODE".equalsIgnoreCase(generationMode);
        String targetModelPrefix = isRefMode ? "minimax-h3-ref2va" : "minimax-h3-fl2va";

        AiModel model = modelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getProviderId, providerId)
                        .like(AiModel::getModelCode, targetModelPrefix)
                        .in(AiModel::getModelType, VIDEO_MODEL_TYPES)
                        .eq(AiModel::getStatus, 1)
                        .orderByAsc(AiModel::getSortOrder)
                        .last("LIMIT 1")
        );
        if (model != null && StringUtils.isNotBlank(model.getModelCode())) {
            return model.getModelCode();
        }

        // 查询任一启用的视频模型
        AiModel fallbackModel = modelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getProviderId, providerId)
                        .in(AiModel::getModelType, VIDEO_MODEL_TYPES)
                        .eq(AiModel::getStatus, 1)
                        .orderByAsc(AiModel::getSortOrder)
                        .last("LIMIT 1")
        );
        if (fallbackModel != null && StringUtils.isNotBlank(fallbackModel.getModelCode())) {
            return fallbackModel.getModelCode();
        }

        // 默认按 MiniMax 标准模型下发
        return isRefMode ? "minimax-h3-ref2va" : "minimax-h3-fl2va";
    }

    private String buildVideoEndpointUrl(String baseUrl) {
        String clean = baseUrl.trim();
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/v1/videos/generations") || clean.endsWith("/videos/generations")) {
            return clean;
        }
        if (clean.endsWith("/v1")) {
            return clean + "/videos/generations";
        }
        return clean + "/v1/videos/generations";
    }

    @Override
    public com.astra.freyja.dto.drama.VideoGenerationResultVO generateAndArchiveVideo(
            Long shotId, Long dramaId, com.astra.freyja.entity.DramaShot shot, com.astra.freyja.dto.drama.DramaShotRenderRequestDTO dto) {
        if (shot == null) {
            throw new BizException(404, "分镜实体不能为空");
        }

        // 1. 获取 AI 提供商
        AiProvider provider = resolveProvider(dto != null ? dto.getProviderId() : null);
        String apiKey = cryptoUtil.decrypt(provider.getApiKey());
        String baseUrl = provider.getBaseUrl();
        if (StringUtils.isBlank(baseUrl)) {
            throw new BizException(400, "AI 提供商未配置 baseUrl: " + provider.getProviderName());
        }

        // 2. 解析视频模型标识
        String modelCode = resolveVideoModelCode(dto, provider.getId(), shot.getGenerationMode());

        // 3. 构造请求 Payload (全部媒体使用 Base64 Data URI)
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", modelCode);

        // 优先使用运镜提示词，若无则使用正向提示词
        String prompt = StringUtils.firstNonBlank(shot.getVideoPrompt(), shot.getPrompt());
        if (StringUtils.isBlank(prompt)) {
            throw new BizException(400, "分镜正向提示词或视频运镜提示词不能为空");
        }
        payload.put("prompt", prompt);

        if (StringUtils.isNotBlank(shot.getNegativePrompt())) {
            payload.put("negative_prompt", shot.getNegativePrompt().trim());
        }

        if (dto != null && dto.getSeed() != null && dto.getSeed() >= 0) {
            payload.put("seed", dto.getSeed());
        }

        if (shot.getDuration() != null) {
            payload.put("duration", shot.getDuration().doubleValue());
        }

        if (dto != null && StringUtils.isNotBlank(dto.getSize())) {
            payload.put("size", dto.getSize().trim());
        }

        if (dto != null && StringUtils.isNotBlank(dto.getTaskId())) {
            payload.put("task_id", dto.getTaskId().trim());
        }
        payload.put("shot_id", String.valueOf(shotId));
        payload.put("response_format", "url");

        // 首尾帧 Base64 转换
        if (StringUtils.isNotBlank(shot.getPreviewImageUrl())) {
            String firstFrameB64 = prepareMediaAsBase64(shot.getPreviewImageUrl(), "image/png");
            payload.put("first_frame", firstFrameB64);
        }
        if (StringUtils.isNotBlank(shot.getEndFrameImageUrl())) {
            String lastFrameB64 = prepareMediaAsBase64(shot.getEndFrameImageUrl(), "image/png");
            payload.put("last_frame", lastFrameB64);
        }

        // TTS 配音 Base64 转换
        if (StringUtils.isNotBlank(shot.getAudioUrl())) {
            String audioB64 = prepareMediaAsBase64(shot.getAudioUrl(), "audio/wav");
            payload.put("audio_url", audioB64);
        }

        // 解析并组装 references 绑定 (人物造型、场景、道具等)
        List<Map<String, Object>> references = new ArrayList<>();

        // 确定提示词格式声明规范
        String promptFormat = "REFERENCE_MODE".equalsIgnoreCase(shot.getGenerationMode())
                ? "MINIMAX_H3_REF2VA_V1"
                : "MINIMAX_H3_FL2VA_V1";
        payload.put("prompt_format", promptFormat);

        // a. 处理参考图列表 (refImagesJson)
        if (StringUtils.isNotBlank(shot.getRefImagesJson())) {
            try {
                List<com.astra.freyja.dto.drama.ShotRefImageDTO> refImgList = objectMapper.readValue(
                        shot.getRefImagesJson(), new com.fasterxml.jackson.core.type.TypeReference<List<com.astra.freyja.dto.drama.ShotRefImageDTO>>() {});
                if (refImgList != null) {
                    for (com.astra.freyja.dto.drama.ShotRefImageDTO refImg : refImgList) {
                        if (StringUtils.isNotBlank(refImg.getImageUrl())) {
                            String b64Img = prepareMediaAsBase64(refImg.getImageUrl(), "image/png");
                            Map<String, Object> binding = new HashMap<>();
                            binding.put("referenceId", refImg.getId());
                            String refType = StringUtils.isNotBlank(refImg.getSourceType()) ? refImg.getSourceType().toUpperCase() : "CHARACTER";
                            binding.put("referenceType", refType);
                            binding.put("entityId", refImg.getSourceId() != null ? String.valueOf(refImg.getSourceId()) : null);
                            binding.put("characterId", refImg.getCharacterId() != null ? String.valueOf(refImg.getCharacterId()) : null);
                            binding.put("lookId", refImg.getLookId() != null ? String.valueOf(refImg.getLookId()) : null);
                            binding.put("entityName", refImg.getName());
                            binding.put("usageRole", refImg.getUsageRole());
                            binding.put("referenceRole", refImg.getReferenceRole());
                            binding.put("referenceImages", List.of(b64Img));
                            references.add(binding);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[AiImageApi] 解析分镜 refImagesJson 失败: {}", e.getMessage());
            }
        }

        // b. 处理参考音频列表 (refAudiosJson, 最多3段按顺序透传)
        if (StringUtils.isNotBlank(shot.getRefAudiosJson())) {
            try {
                List<com.astra.freyja.dto.drama.ShotRefAudioDTO> refAudioList = objectMapper.readValue(
                        shot.getRefAudiosJson(), new com.fasterxml.jackson.core.type.TypeReference<List<com.astra.freyja.dto.drama.ShotRefAudioDTO>>() {});
                if (refAudioList != null) {
                    int aCount = 0;
                    for (com.astra.freyja.dto.drama.ShotRefAudioDTO refAudio : refAudioList) {
                        if (aCount >= 3) break;
                        if (StringUtils.isNotBlank(refAudio.getAudioUrl())) {
                            String b64Audio = prepareMediaAsBase64(refAudio.getAudioUrl(), "audio/wav");
                            Map<String, Object> binding = new HashMap<>();
                            binding.put("referenceId", refAudio.getId());
                            binding.put("referenceType", "REFERENCE_AUDIO");
                            binding.put("entityId", refAudio.getCharacterId() != null ? String.valueOf(refAudio.getCharacterId()) : null);
                            binding.put("entityName", StringUtils.firstNonBlank(refAudio.getCharacterName(), refAudio.getName()));
                            binding.put("usageMode", StringUtils.defaultIfBlank(refAudio.getUsageMode(), "VOICE_TIMBRE"));
                            binding.put("language", StringUtils.defaultIfBlank(refAudio.getLanguage(), "Chinese"));
                            binding.put("referenceAudio", b64Audio);
                            references.add(binding);
                            aCount++;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[AiImageApi] 解析分镜 refAudiosJson 失败: {}", e.getMessage());
            }
        }

        if (!references.isEmpty()) {
            payload.put("references", references);
            payload.put("enable_reference_prompt", true);
            log.info("[AiImageApi] 分镜携带 {} 个结构化参考绑定 (已全部 Base64 化)", references.size());
        }

        // extra_body 透传
        Map<String, Object> extraBody = new HashMap<>();
        extraBody.put("shot_id", String.valueOf(shotId));
        extraBody.put("prompt_format", promptFormat);
        if (payload.containsKey("size")) {
            extraBody.put("size", payload.get("size"));
        }
        if (payload.containsKey("first_frame")) {
            extraBody.put("first_frame", payload.get("first_frame"));
        }
        if (payload.containsKey("last_frame")) {
            extraBody.put("last_frame", payload.get("last_frame"));
        }
        if (payload.containsKey("audio_url")) {
            extraBody.put("audio_url", payload.get("audio_url"));
        }
        if (!references.isEmpty()) {
            extraBody.put("references", references);
            extraBody.put("enable_reference_prompt", true);
        }
        payload.put("extra_body", extraBody);

        // 4. 调用网关 API
        String requestUrl = buildVideoEndpointUrl(baseUrl);
        log.info("[AiImageApi] 正在调用云端/网关视频生成 API: url={}, model={}, shotId={}, requestedSize={}",
                requestUrl, modelCode, shotId, payload.get("size"));

        byte[] videoBytes;
        String revisedPrompt = null;
        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                    .timeout(Duration.ofSeconds(provider.getTimeout() != null && provider.getTimeout() > 0 ? provider.getTimeout() : 1800))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.error("[AiImageApi] 视频生成 API 请求失败: status={}, body={}", response.statusCode(), response.body());
                throw new BizException(500, "视频生成接口调用失败 (" + response.statusCode() + "): " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray() || dataArray.isEmpty()) {
                throw new BizException(500, "视频生成返回数据为空: " + response.body());
            }

            JsonNode firstItem = dataArray.get(0);
            if (firstItem.hasNonNull("revised_prompt")) {
                revisedPrompt = firstItem.get("revised_prompt").asText();
            }

            if (firstItem.hasNonNull("url")) {
                String remoteVideoUrl = firstItem.get("url").asText();
                String targetUrl = resolveRemoteImageUrl(remoteVideoUrl, baseUrl);
                log.info("[AiImageApi] 成功获取远程视频 URL，正在下载转存至 MinIO: targetUrl={}", targetUrl);
                videoBytes = downloadRemoteBytes(targetUrl);
            } else if (firstItem.hasNonNull("b64_json")) {
                String b64 = firstItem.get("b64_json").asText();
                videoBytes = Base64.getDecoder().decode(b64);
            } else {
                throw new BizException(500, "视频生成未返回有效的 url 或 b64_json");
            }
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.error("[AiImageApi] 视频生成执行异常: error={}", e.getMessage(), e);
            throw new BizException(500, "视频生成执行异常: " + e.getMessage());
        }

        // 5. 上传视频至 MinIO
        String videoUrl = uploadVideoToMinio(shotId, dramaId, videoBytes);

        // 注意：根据按需提取架构设计，视频生成完成时不自动抽取尾帧，只有用户主动引用时才由 ShotFrameContinuityService 按需提取
        return com.astra.freyja.dto.drama.VideoGenerationResultVO.builder()
                .videoUrl(videoUrl)
                .lastFrameUrl(null)
                .revisedPrompt(revisedPrompt)
                .build();
    }

    private String uploadVideoToMinio(Long shotId, Long dramaId, byte[] videoBytes) {
        long timestamp = System.currentTimeMillis();
        long dId = dramaId != null ? dramaId : 0L;
        long sId = shotId != null ? shotId : 0L;

        String objectPath = String.format("projects/%d/shots/%d/takes/video_%d.mp4", dId, sId, timestamp);
        String bucket = minioProperties.getBucketName();
        ensureBucketExists(bucket);

        try (InputStream is = new ByteArrayInputStream(videoBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectPath)
                            .stream(is, videoBytes.length, -1)
                            .contentType("video/mp4")
                            .build()
            );
        } catch (Exception e) {
            log.error("[AiImageApi] 视频上传 MinIO 失败: error={}", e.getMessage(), e);
            throw new BizException(500, "视频上传 MinIO 失败: " + e.getMessage());
        }

        String base = StringUtils.defaultIfBlank(minioProperties.getExternalEndpoint(), minioProperties.getEndpoint());
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanObject = objectPath.startsWith("/") ? objectPath.substring(1) : objectPath;
        String finalUrl = String.format("%s/%s/%s", cleanBase, bucket, cleanObject);
        log.info("[AiImageApi] 视频成功归档至 MinIO: url={}", finalUrl);
        return finalUrl;
    }

    private String uploadFrameToMinio(Long shotId, Long dramaId, byte[] frameBytes) {
        long timestamp = System.currentTimeMillis();
        long dId = dramaId != null ? dramaId : 0L;
        long sId = shotId != null ? shotId : 0L;

        String objectPath = String.format("projects/%d/shots/%d/candidates/last_frame_%d.jpg", dId, sId, timestamp);
        String bucket = minioProperties.getBucketName();
        ensureBucketExists(bucket);

        try (InputStream is = new ByteArrayInputStream(frameBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectPath)
                            .stream(is, frameBytes.length, -1)
                            .contentType("image/jpeg")
                            .build()
            );
        } catch (Exception e) {
            log.error("[AiImageApi] 末帧图上传 MinIO 失败: error={}", e.getMessage(), e);
            throw new BizException(500, "末帧图上传 MinIO 失败: " + e.getMessage());
        }

        String base = StringUtils.defaultIfBlank(minioProperties.getExternalEndpoint(), minioProperties.getEndpoint());
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanObject = objectPath.startsWith("/") ? objectPath.substring(1) : objectPath;
        return String.format("%s/%s/%s", cleanBase, bucket, cleanObject);
    }

    private String uploadToMinio(Long shotId, Long dramaId, byte[] imageBytes) {
        long timestamp = System.currentTimeMillis();
        long dId = dramaId != null ? dramaId : 0L;
        long sId = shotId != null ? shotId : 0L;

        String prefix = archivePrefix.get();
        String objectPath = prefix != null
                ? String.format("%s/api_%d_%d.png", prefix, timestamp, ThreadLocalRandom.current().nextInt(1000))
                : String.format("projects/%d/shots/%d/candidates/api_%d.png", dId, sId, timestamp);
        String bucket = minioProperties.getBucketName();
        ensureBucketExists(bucket);

        try (InputStream is = new ByteArrayInputStream(imageBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectPath)
                            .stream(is, imageBytes.length, -1)
                            .contentType("image/png")
                            .build()
            );
        } catch (Exception e) {
            log.error("[AiImageApi] 上传 MinIO 失败: error={}", e.getMessage(), e);
            throw new BizException(500, "上传 MinIO 失败: " + e.getMessage());
        }

        String base = StringUtils.defaultIfBlank(minioProperties.getExternalEndpoint(), minioProperties.getEndpoint());
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanObject = objectPath.startsWith("/") ? objectPath.substring(1) : objectPath;
        String finalUrl = String.format("%s/%s/%s", cleanBase, bucket, cleanObject);
        log.info("[AiImageApi] 首帧图成功归档至 MinIO: url={}", finalUrl);
        return finalUrl;
    }

    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            log.warn("[AiImageApi] 检查/创建存储桶警告: {}", e.getMessage());
        }
    }

    @Override
    public com.astra.freyja.dto.render.RemoteCancelResultVO cancelRemoteTask(Long providerId, String taskId, Long shotId) {
        log.info("[AiImageApi] 收到远程任务取消请求: providerId={}, taskId={}, shotId={}", providerId, taskId, shotId);
        try {
            AiProvider provider = null;
            try {
                provider = resolveProvider(providerId);
            } catch (Exception pe) {
                log.warn("[AiImageApi] resolveProvider 失败: {}", pe.getMessage());
            }

            String baseUrl = provider != null ? provider.getBaseUrl() : null;
            if (StringUtils.isBlank(baseUrl)) {
                log.warn("[AiImageApi] 未能获取 AI 提供商 baseUrl，跳过远程取消通知");
                return com.astra.freyja.dto.render.RemoteCancelResultVO.builder()
                        .success(false)
                        .message("未能获取 AI 提供商服务地址")
                        .detailStatus("NOT_CONFIGURED")
                        .build();
            }

            String clean = baseUrl.trim();
            if (clean.endsWith("/")) {
                clean = clean.substring(0, clean.length() - 1);
            }
            if (clean.endsWith("/v1")) {
                clean = clean.substring(0, clean.length() - 3);
            }

            Map<String, Object> body = new HashMap<>();
            if (StringUtils.isNotBlank(taskId)) {
                body.put("task_id", taskId);
            }
            if (shotId != null) {
                body.put("shot_id", String.valueOf(shotId));
            }

            String jsonPayload = objectMapper.writeValueAsString(body);
            String cancelUrl = clean + "/v1/tasks/cancel";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cancelUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            log.info("[AiImageApi] 正在向远程网关发送取消指令: url={}, payload={}", cancelUrl, jsonPayload);
            HttpResponse<String> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("[AiImageApi] 远程任务取消返回非200状态: status={}, body={}", res.statusCode(), res.body());
                return com.astra.freyja.dto.render.RemoteCancelResultVO.builder()
                        .success(false)
                        .message("网关返回异常状态码: " + res.statusCode())
                        .detailStatus("FAILED")
                        .build();
            }

            // 解析网关返回的 JSON 结构
            JsonNode root = objectMapper.readTree(res.body());
            boolean success = root.path("success").asBoolean(false);
            String message = root.path("message").asText("");
            String detailStatus = root.path("detail_status").asText(success ? "CANCELLED" : "FAILED");

            List<String> interruptedNodes = new ArrayList<>();
            JsonNode nodesNode = root.path("interrupted_nodes");
            if (nodesNode.isArray()) {
                for (JsonNode n : nodesNode) {
                    interruptedNodes.add(n.asText());
                }
            }

            List<String> cancelledPrompts = new ArrayList<>();
            JsonNode promptsNode = root.path("cancelled_prompt_ids");
            if (promptsNode.isArray()) {
                for (JsonNode p : promptsNode) {
                    cancelledPrompts.add(p.asText());
                }
            }

            log.info("[AiImageApi] 远程任务取消响应解析: success={}, detailStatus={}, message={}, nodes={}",
                    success, detailStatus, message, interruptedNodes);

            return com.astra.freyja.dto.render.RemoteCancelResultVO.builder()
                    .success(success)
                    .message(message)
                    .detailStatus(detailStatus)
                    .interruptedNodes(interruptedNodes)
                    .cancelledPromptIds(cancelledPrompts)
                    .build();
        } catch (Exception e) {
            log.error("[AiImageApi] 向远程网关发送取消指令异常: {}", e.getMessage(), e);
            return com.astra.freyja.dto.render.RemoteCancelResultVO.builder()
                    .success(false)
                    .message("网关通信异常: " + e.getMessage())
                    .detailStatus("FAILED")
                    .build();
        }
    }
}
