package com.astra.freyja.service;

/**
 * AI 语音合成与 MinIO 归档统一调度服务 (支持 MiMo-V2.5-TTS 系列模型及 OpenAI 兼容 TTS)
 */
public interface AiAudioApiService {

    /**
     * 调度声音设计模型生成角色母音样音并归档至 MinIO
     *
     * @param providerId  显式选择的 AI 提供商 ID
     * @param modelCode   所选提供商已启用的语音模型编码
     * @param voiceDesc   自然语言声音设计提示词 (user role)
     * @param sampleText  试音合成台词文本 (assistant role)
     * @param archivePath MinIO 相对路径 (如 "characters/1/voice_sample.wav")
     * @return 包含 MinIO 访问 URL 与音频时长 (秒) 的结果
     */
    AudioArchiveResult designVoice(Long providerId, String modelCode, String voiceDesc, String sampleText, String archivePath);

    AudioArchiveResult cloneVoice(Long providerId, String modelCode, byte[] referenceWav, String emotion, String text, String archivePath);

    /**
     * 音频归档结果
     */
    record AudioArchiveResult(String audioUrl, Double durationSeconds, long fileSizeBytes) {}
}
