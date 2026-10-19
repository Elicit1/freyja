package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.AiAudioApiService;
import com.astra.freyja.util.CryptoUtil;
import com.astra.freyja.util.VoiceModelRequest;
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
    private final com.astra.freyja.service.VideoFrameExtractService videoFrameExtractService;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public AudioArchiveResult generateVoice(Long providerId, String modelCode, byte[] referenceWav,
                                            Map<String, Object> modelRequest, String archivePath) {
        VoiceModelRequest.snapshot(modelRequest);
        AiProvider provider = resolveProvider(providerId, modelCode);
        Map<String, Object> payload = new HashMap<>(modelRequest);
        if (payload.containsKey("model") && !modelCode.equals(payload.get("model")))
            throw new BizException(400, "JSON 中的 model 与所选模型不一致");
        payload.put("model", modelCode);
        Map<String, Object> audio = new HashMap<>();
        if (modelRequest.get("audio") instanceof Map<?, ?> supplied)
            supplied.forEach((key, value) -> audio.put((String) key, value));
        audio.put("format", "wav");
        if (referenceWav != null) {
            if (referenceWav.length == 0) throw new BizException(400, "基础音频不能为空");
            String voice = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(referenceWav);
            if (voice.length() > 10 * 1024 * 1024) throw new BizException(400, "基础音频编码后超过 MiMo 10 MiB 限制");
            audio.put("voice", voice);
        }
        payload.put("audio", audio);
        payload.put("stream", false);
        byte[] bytes = executeAudioRequest(buildEndpointUrl(provider.getBaseUrl()), cryptoUtil.decrypt(provider.getApiKey()), payload, provider.getTimeout());
        return new AudioArchiveResult(uploadToMinio(archivePath, bytes, "audio/wav"), calculateAudioDuration(bytes), bytes.length);
    }

    @Override
    public AudioArchiveResult designVoice(Long providerId, String modelCode, String voiceDesc, String sampleText, String archivePath) {
        if (StringUtils.isBlank(voiceDesc)) {
            throw new BizException(400, "自然语言声音设计提示词 (voiceDesc) 不能为空");
        }
        String effectiveSampleText = StringUtils.defaultIfBlank(sampleText, "你好，我是为你全新定制的专属声音，很高兴与你相遇。");
        String effectiveModel = StringUtils.trimToNull(modelCode);

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
        Double duration = calculateAudioDuration(audioBytes);

        // 归档 MinIO
        String finalUrl = uploadToMinio(archivePath, audioBytes, "audio/wav");
        log.info("[AiAudioApi] 声音设计样音归档完成: url={}, duration={}s, size={} bytes", finalUrl, duration, audioBytes.length);

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

    private Double calculateAudioDuration(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) return null;
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audioBytes))) {
            if (ais.getFrameLength() > 0 && ais.getFormat().getFrameRate() > 0) {
                return BigDecimal.valueOf(ais.getFrameLength() / (double) ais.getFormat().getFrameRate()).setScale(2, RoundingMode.HALF_UP).doubleValue();
            }
        } catch (Exception ignored) { }
        var metadata = videoFrameExtractService.probeVideoInfo(audioBytes, "audio");
        return metadata != null && metadata.getDuration() != null ? metadata.getDuration().doubleValue() : null;
    }

    private AiProvider resolveProvider(Long providerId, String modelCode) {
        if (providerId == null || StringUtils.isBlank(modelCode)) throw new BizException(400, "请明确选择已配置的语音提供商和模型");
        AiProvider provider = providerMapper.selectById(providerId);
        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus())) throw new BizException(400, "所选语音提供商不存在或已停用");
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId).eq(AiModel::getModelCode, modelCode.trim())
                .in(AiModel::getModelType, List.of("TTS", "AUDIO", "VOICE")).eq(AiModel::getStatus, 1).last("LIMIT 1"));
        if (model == null) throw new BizException(400, "所选提供商未配置该语音模型，请重新选择");
        return provider;
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
