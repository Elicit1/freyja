package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.AiAudioApiService;
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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 语音合成与 MinIO 归档统一调度服务实现 (支持 MiMo-V2.5-TTS 系列模型)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAudioApiServiceImpl implements AiAudioApiService {

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public static final String DEFAULT_VOICE_DESIGN_MODEL = "mimo-v2.5-tts-voicedesign";
    public static final String DEFAULT_VOICE_CLONE_MODEL = "mimo-v2.5-tts-voiceclone";

    @Override
    public AudioArchiveResult designVoice(Long providerId, String modelCode, String voiceDesc, String sampleText, String archivePath) {
        if (StringUtils.isBlank(voiceDesc)) {
            throw new BizException(400, "自然语言声音设计提示词 (voiceDesc) 不能为空");
        }
        String effectiveSampleText = StringUtils.defaultIfBlank(sampleText, "你好，我是为你全新定制的专属声音，很高兴与你相遇。");
        String effectiveModel = StringUtils.defaultIfBlank(modelCode, DEFAULT_VOICE_DESIGN_MODEL);

        AiProvider provider = resolveProvider(providerId, effectiveModel);
        String baseUrl = provider.getBaseUrl();
        String apiKey = cryptoUtil.decrypt(provider.getApiKey());

        // 构造 OpenAI Chat Completions + Audio 请求 Payload (对标 Xiaomi MiMo 官方文档规范)
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", effectiveModel);
        payload.put("messages", List.of(
                Map.of("role", "user", "content", voiceDesc.trim()),
                Map.of("role", "assistant", "content", effectiveSampleText.trim())
        ));
        Map<String, Object> audioConfig = new HashMap<>();
        audioConfig.put("format", "wav");
        payload.put("audio", audioConfig);
        payload.put("stream", false);

        String requestUrl = buildEndpointUrl(baseUrl);
        log.info("[AiAudioApi] 正在调用声音设计模型: url={}, model={}, promptLen={}", requestUrl, effectiveModel, voiceDesc.length());

        byte[] audioBytes = executeAudioRequest(requestUrl, apiKey, payload, provider.getTimeout());
        audioBytes = prependSilenceIfWav(audioBytes, 0.25);

        // 计算音频时长
        Double duration = calculateAudioDuration(audioBytes, effectiveSampleText);

        // 归档 MinIO
        String finalUrl = uploadToMinio(archivePath, audioBytes, "audio/wav");
        log.info("[AiAudioApi] 声音设计样音归档完成: url={}, duration={}s, size={} bytes", finalUrl, duration, audioBytes.length);

        return new AudioArchiveResult(finalUrl, duration, audioBytes.length);
    }

    @Override
    public AudioArchiveResult cloneVoice(Long providerId, String modelCode, String referenceAudioUrl, String targetDialogue, String emotion, String archivePath) {
        if (StringUtils.isBlank(referenceAudioUrl)) {
            throw new BizException(400, "参考基准音源 (referenceAudioUrl) 不能为空，请先在角色管理中设计或上传声音母音");
        }
        if (StringUtils.isBlank(targetDialogue)) {
            throw new BizException(400, "目标配音台词文本 (targetDialogue) 不能为空");
        }
        String effectiveModel = StringUtils.defaultIfBlank(modelCode, DEFAULT_VOICE_CLONE_MODEL);

        AiProvider provider = resolveProvider(providerId, effectiveModel);
        String baseUrl = provider.getBaseUrl();
        String apiKey = cryptoUtil.decrypt(provider.getApiKey());

        // 准备参考音频的 Base64 Data URI
        String referenceAudioDataUri = prepareReferenceAudioDataUri(referenceAudioUrl);

        // 构造请求
        String instruction = StringUtils.isNotBlank(emotion) ? emotion.trim() : "自然流畅、富有情感与戏剧表现力地进行台词配音。";
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", effectiveModel);
        payload.put("messages", List.of(
                Map.of("role", "user", "content", instruction),
                Map.of("role", "assistant", "content", targetDialogue.trim())
        ));
        payload.put("audio", Map.of(
                "format", "mp3",
                "voice", referenceAudioDataUri
        ));
        payload.put("stream", false);

        String requestUrl = buildEndpointUrl(baseUrl);
        log.info("[AiAudioApi] 正在调用声音克隆模型: url={}, model={}, dialogueLen={}", requestUrl, effectiveModel, targetDialogue.length());

        byte[] audioBytes = executeAudioRequest(requestUrl, apiKey, payload, provider.getTimeout());
        audioBytes = prependSilenceIfWav(audioBytes, 0.25);

        // 计算音频时长
        Double duration = calculateAudioDuration(audioBytes, targetDialogue);

        // 归档 MinIO
        String finalUrl = uploadToMinio(archivePath, audioBytes, "audio/mpeg");
        log.info("[AiAudioApi] 声音克隆台词归档完成: url={}, duration={}s, size={} bytes", finalUrl, duration, audioBytes.length);

        return new AudioArchiveResult(finalUrl, duration, audioBytes.length);
    }

    private byte[] executeAudioRequest(String requestUrl, String apiKey, Map<String, Object> payload, Integer timeoutSeconds) {
        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                    .timeout(Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 120));
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("api-key", apiKey.trim());
            }
            HttpRequest request = reqBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300) {
                String errBody = new String(response.body());
                log.error("[AiAudioApi] 语音生成 API 请求失败: status={}, body={}", response.statusCode(), errBody);
                throw new BizException(500, "语音生成接口调用失败 (" + response.statusCode() + "): " + errBody);
            }

            // 检查 Content-Type 是否为直接二进制音频流
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.startsWith("audio/") || contentType.equals("application/octet-stream")) {
                return response.body();
            }

            // 若为 JSON 响应，解析 choices[0].message.audio.data 或 choices[0].delta.audio.data
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                if (message.hasNonNull("audio")) {
                    JsonNode audioNode = message.path("audio");
                    if (audioNode.hasNonNull("data")) {
                        String b64 = audioNode.get("data").asText();
                        return Base64.getDecoder().decode(b64);
                    }
                }
                // 兼容 delta.audio.data
                JsonNode delta = choices.get(0).path("delta");
                if (delta.hasNonNull("audio") && delta.path("audio").hasNonNull("data")) {
                    String b64 = delta.path("audio").get("data").asText();
                    return Base64.getDecoder().decode(b64);
                }
            }

            // 兼容 OpenAI Speech API /data/ 数组格式
            if (root.has("data") && root.path("data").isArray() && !root.path("data").isEmpty()) {
                JsonNode firstItem = root.path("data").get(0);
                if (firstItem.hasNonNull("b64_json")) {
                    return Base64.getDecoder().decode(firstItem.get("b64_json").asText());
                }
            }

            // 若为纯文本 Base64 格式
            String textBody = new String(response.body()).trim();
            if (textBody.startsWith("data:audio") && textBody.contains(";base64,")) {
                String b64 = textBody.substring(textBody.indexOf(";base64,") + 8);
                return Base64.getDecoder().decode(b64);
            }

            throw new BizException(500, "语音生成 API 未返回可识别的音频数据: " + textBody);
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.error("[AiAudioApi] 语音接口调用异常: {}", e.getMessage(), e);
            throw new BizException(500, "语音生成异常: " + e.getMessage());
        }
    }

    private String prepareReferenceAudioDataUri(String referenceAudioUrl) {
        if (StringUtils.isBlank(referenceAudioUrl)) {
            return "";
        }
        String trimmed = referenceAudioUrl.trim();
        if (trimmed.startsWith("data:audio")) {
            return trimmed;
        }

        byte[] bytes = null;
        String bucket = minioProperties.getBucketName();

        // 1. 优先尝试从本地 MinIO 存储直接读取对象
        if (trimmed.contains("/" + bucket + "/")) {
            try {
                int bucketIndex = trimmed.indexOf("/" + bucket + "/");
                String objectName = trimmed.substring(bucketIndex + bucket.length() + 2);
                if (objectName.contains("?")) {
                    objectName = objectName.substring(0, objectName.indexOf("?"));
                }
                log.info("[AiAudioApi] 正在从本地 MinIO 提取参考样音: bucket={}, objectName={}", bucket, objectName);
                try (InputStream stream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectName)
                                .build())) {
                    bytes = stream.readAllBytes();
                }
            } catch (Exception e) {
                log.warn("[AiAudioApi] 从 MinIO 提取样音失败，尝试 HTTP 下载: err={}", e.getMessage());
            }
        }

        // 2. HTTP 下载兜底
        if (bytes == null && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(trimmed))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    bytes = response.body();
                }
            } catch (Exception e) {
                log.warn("[AiAudioApi] HTTP 下载参考样音失败: url={}, err={}", trimmed, e.getMessage());
            }
        }

        if (bytes != null && bytes.length > 0) {
            String mime = "audio/wav";
            String lower = trimmed.toLowerCase();
            if (lower.contains(".mp3")) {
                mime = "audio/mp3";
            } else if (lower.contains(".ogg")) {
                mime = "audio/ogg";
            } else if (lower.contains(".flac")) {
                mime = "audio/flac";
            }
            String b64 = Base64.getEncoder().encodeToString(bytes);
            log.info("[AiAudioApi] 成功转换参考样音为 Base64 Data URI (size={} KB)", bytes.length / 1024);
            return "data:" + mime + ";base64," + b64;
        }

        return trimmed;
    }

    private String uploadToMinio(String archivePath, byte[] audioBytes, String contentType) {
        String bucket = minioProperties.getBucketName();
        ensureBucketExists(bucket);

        String cleanObject = archivePath.startsWith("/") ? archivePath.substring(1) : archivePath;
        try (InputStream is = new ByteArrayInputStream(audioBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(cleanObject)
                            .stream(is, audioBytes.length, -1)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception e) {
            log.error("[AiAudioApi] 上传音频至 MinIO 失败: error={}", e.getMessage(), e);
            throw new BizException(500, "音频上传 MinIO 失败: " + e.getMessage());
        }

        String base = StringUtils.defaultIfBlank(minioProperties.getExternalEndpoint(), minioProperties.getEndpoint());
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return String.format("%s/%s/%s", cleanBase, bucket, cleanObject);
    }

    private Double calculateAudioDuration(byte[] audioBytes, String fallbackText) {
        if (audioBytes == null || audioBytes.length == 0) {
            return 0.0;
        }
        try {
            // 尝试使用标准 Java AudioSystem 读取 (支持 WAV 等)
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audioBytes))) {
                AudioFormat format = ais.getFormat();
                long frames = ais.getFrameLength();
                if (frames > 0 && format.getFrameRate() > 0) {
                    double sec = (double) frames / format.getFrameRate();
                    return BigDecimal.valueOf(sec).setScale(2, RoundingMode.HALF_UP).doubleValue();
                }
            }
        } catch (Exception ignored) {
            // 对于 MP3 等非原生 SPI 格式，使用字节或文本长度估算
        }

        // WAV 头部直接计算兜底
        if (audioBytes.length > 44 && audioBytes[0] == 'R' && audioBytes[1] == 'I' && audioBytes[2] == 'F' && audioBytes[3] == 'F') {
            try {
                int channels = ((audioBytes[23] & 0xFF) << 8) | (audioBytes[22] & 0xFF);
                int sampleRate = ((audioBytes[27] & 0xFF) << 24) | ((audioBytes[26] & 0xFF) << 16) | ((audioBytes[25] & 0xFF) << 8) | (audioBytes[24] & 0xFF);
                int bitsPerSample = ((audioBytes[35] & 0xFF) << 8) | (audioBytes[34] & 0xFF);
                if (channels > 0 && sampleRate > 0 && bitsPerSample > 0) {
                    int bytesPerSec = sampleRate * channels * (bitsPerSample / 8);
                    double sec = (double) (audioBytes.length - 44) / bytesPerSec;
                    return BigDecimal.valueOf(Math.max(0.5, sec)).setScale(2, RoundingMode.HALF_UP).doubleValue();
                }
            } catch (Exception ignored) {}
        }

        // 文本语速估算 (中文正常语速约 3.8 ~ 4.2 字/秒 + 停顿)
        if (StringUtils.isNotBlank(fallbackText)) {
            double estimatedSec = Math.max(1.0, (double) fallbackText.length() / 4.0 + 0.5);
            return BigDecimal.valueOf(estimatedSec).setScale(2, RoundingMode.HALF_UP).doubleValue();
        }

        return 2.5;
    }

    private AiProvider resolveProvider(Long providerId, String modelCode) {
        AiProvider specifiedProvider = null;
        if (providerId != null && providerId > 0) {
            specifiedProvider = providerMapper.selectById(providerId);
            if (specifiedProvider != null && Integer.valueOf(1).equals(specifiedProvider.getStatus())) {
                // 若指定了模型，校验该提供商下是否存在该模型或支持语音模型
                if (StringUtils.isNotBlank(modelCode)) {
                    Long modelCount = modelMapper.selectCount(new LambdaQueryWrapper<AiModel>()
                            .eq(AiModel::getProviderId, specifiedProvider.getId())
                            .eq(AiModel::getModelCode, modelCode.trim())
                            .eq(AiModel::getStatus, 1));
                    if (modelCount != null && modelCount > 0) {
                        return specifiedProvider;
                    }
                    // 检查该提供商是否挂载了任何语音/TTS模型
                    Long ttsCount = modelMapper.selectCount(new LambdaQueryWrapper<AiModel>()
                            .eq(AiModel::getProviderId, specifiedProvider.getId())
                            .in(AiModel::getModelType, List.of("TTS", "AUDIO", "VOICE"))
                            .eq(AiModel::getStatus, 1));
                    if (ttsCount != null && ttsCount > 0) {
                        return specifiedProvider;
                    }
                    // 若传入的提供商不具备语音模型（如误选了本地生图网关），则发出告警并自动通过模型编码重定向
                    log.warn("[AiAudioApi] 指定提供商 (id={}, name={}) 未配置语音模型 {}，自动重定向到所属语音提供商",
                            specifiedProvider.getId(), specifiedProvider.getProviderName(), modelCode);
                } else {
                    return specifiedProvider;
                }
            }
        }

        // 1. 根据 modelCode 查找所属真实提供商 (如 mimo-v2.5-tts-* -> Xiaomi)
        if (StringUtils.isNotBlank(modelCode)) {
            AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getModelCode, modelCode.trim())
                    .eq(AiModel::getStatus, 1)
                    .orderByDesc(AiModel::getId)
                    .last("LIMIT 1"));
            if (model != null && model.getProviderId() != null) {
                AiProvider p = providerMapper.selectById(model.getProviderId());
                if (p != null && p.getStatus() != null && p.getStatus() == 1) {
                    return p;
                }
            }
        }

        // 2. 查找任意配置了 TTS 类型模型的激活提供商
        AiModel anyTtsModel = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .in(AiModel::getModelType, List.of("TTS", "AUDIO", "VOICE"))
                .eq(AiModel::getStatus, 1)
                .orderByDesc(AiModel::getId)
                .last("LIMIT 1"));
        if (anyTtsModel != null && anyTtsModel.getProviderId() != null) {
            AiProvider p = providerMapper.selectById(anyTtsModel.getProviderId());
            if (p != null && p.getStatus() != null && p.getStatus() == 1) {
                return p;
            }
        }

        // 3. 兜底已启用的 OPENAI 协议提供商
        AiProvider fallback = providerMapper.selectOne(new LambdaQueryWrapper<AiProvider>()
                .eq(AiProvider::getStatus, 1)
                .eq(AiProvider::getProviderType, "OPENAI")
                .orderByAsc(AiProvider::getId)
                .last("LIMIT 1"));

        if (fallback != null) {
            return fallback;
        }

        if (specifiedProvider != null) {
            return specifiedProvider;
        }

        throw new BizException(400, "系统未配置可用的语音 AI 提供商，请在系统设置中添加并启用提供商");
    }

    private String buildEndpointUrl(String baseUrl) {
        String clean = baseUrl.trim();
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/chat/completions")) {
            return clean;
        }
        if (clean.endsWith("/v1")) {
            return clean + "/chat/completions";
        }
        return clean + "/v1/chat/completions";
    }

    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            log.warn("[AiAudioApi] 检查/创建存储桶警告: {}", e.getMessage());
        }
    }

    /**
     * 为 WAV 音频头部添加微小的前置静音缓冲帧 (默认 250ms)。
     * 解决客户端播放器 / 声卡 DAC 起步唤醒延迟导致的头字（如“早安”）被吞、爆音或吞字问题。
     */
    private byte[] prependSilenceIfWav(byte[] audioBytes, double silenceSeconds) {
        if (audioBytes == null || audioBytes.length < 44 || silenceSeconds <= 0) {
            return audioBytes;
        }
        if (audioBytes[0] != 'R' || audioBytes[1] != 'I' || audioBytes[2] != 'F' || audioBytes[3] != 'F'
                || audioBytes[8] != 'W' || audioBytes[9] != 'A' || audioBytes[10] != 'V' || audioBytes[11] != 'E') {
            return audioBytes;
        }
        try {
            int channels = ((audioBytes[23] & 0xFF) << 8) | (audioBytes[22] & 0xFF);
            int sampleRate = ((audioBytes[27] & 0xFF) << 24) | ((audioBytes[26] & 0xFF) << 16)
                    | ((audioBytes[25] & 0xFF) << 8) | (audioBytes[24] & 0xFF);
            int bitsPerSample = ((audioBytes[35] & 0xFF) << 8) | (audioBytes[34] & 0xFF);
            if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0) {
                return audioBytes;
            }
            int bytesPerSample = bitsPerSample / 8;
            int silenceBytesCount = (int) (sampleRate * channels * bytesPerSample * silenceSeconds);
            silenceBytesCount = silenceBytesCount - (silenceBytesCount % (channels * bytesPerSample));
            if (silenceBytesCount <= 0) {
                return audioBytes;
            }

            int dataIndex = -1;
            for (int i = 12; i < audioBytes.length - 8; i++) {
                if (audioBytes[i] == 'd' && audioBytes[i + 1] == 'a' && audioBytes[i + 2] == 't' && audioBytes[i + 3] == 'a') {
                    dataIndex = i;
                    break;
                }
            }
            if (dataIndex == -1) {
                return audioBytes;
            }

            int headerLen = dataIndex + 8;
            int oldDataSize = ((audioBytes[dataIndex + 7] & 0xFF) << 24)
                    | ((audioBytes[dataIndex + 6] & 0xFF) << 16)
                    | ((audioBytes[dataIndex + 5] & 0xFF) << 8)
                    | (audioBytes[dataIndex + 4] & 0xFF);
            int newDataSize = oldDataSize + silenceBytesCount;
            int newTotalSize = audioBytes.length + silenceBytesCount;

            byte[] newWav = new byte[newTotalSize];
            System.arraycopy(audioBytes, 0, newWav, 0, headerLen);
            int riffSize = newTotalSize - 8;
            newWav[4] = (byte) (riffSize & 0xFF);
            newWav[5] = (byte) ((riffSize >> 8) & 0xFF);
            newWav[6] = (byte) ((riffSize >> 16) & 0xFF);
            newWav[7] = (byte) ((riffSize >> 24) & 0xFF);

            newWav[dataIndex + 4] = (byte) (newDataSize & 0xFF);
            newWav[dataIndex + 5] = (byte) ((newDataSize >> 8) & 0xFF);
            newWav[dataIndex + 6] = (byte) ((newDataSize >> 16) & 0xFF);
            newWav[dataIndex + 7] = (byte) ((newDataSize >> 24) & 0xFF);

            System.arraycopy(audioBytes, headerLen, newWav, headerLen + silenceBytesCount, audioBytes.length - headerLen);
            log.info("[AiAudioApi] 已自动为 WAV 添加 {}ms 前置静音缓冲帧，防范播放设备起步吞字", (int)(silenceSeconds * 1000));
            return newWav;
        } catch (Exception e) {
            log.warn("[AiAudioApi] 补充前置静音缓冲失败，使用原始音频: {}", e.getMessage());
            return audioBytes;
        }
    }
}
