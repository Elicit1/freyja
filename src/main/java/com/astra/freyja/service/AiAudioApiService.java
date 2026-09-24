package com.astra.freyja.service;

/**
 * AI 语音合成与 MinIO 归档统一调度服务 (支持 MiMo-V2.5-TTS 系列模型及 OpenAI 兼容 TTS)
 */
public interface AiAudioApiService {

    /**
     * 调度声音设计模型生成角色母音样音并归档至 MinIO
     *
     * @param providerId  AI 提供商 ID (可空)
     * @param modelCode   模型编码 (可空，默认 "mimo-v2.5-tts-voicedesign")
     * @param voiceDesc   自然语言声音设计提示词 (user role)
     * @param sampleText  试音合成台词文本 (assistant role)
     * @param archivePath MinIO 相对路径 (如 "characters/1/voice_sample.wav")
     * @return 包含 MinIO 访问 URL 与音频时长 (秒) 的结果
     */
    AudioArchiveResult designVoice(Long providerId, String modelCode, String voiceDesc, String sampleText, String archivePath);

    /**
     * 调度声音克隆模型克隆角色音色并合成台词，自动归档至 MinIO
     *
     * @param providerId        AI 提供商 ID (可空)
     * @param modelCode         模型编码 (可空，默认 "mimo-v2.5-tts-voiceclone")
     * @param referenceAudioUrl 参考音源 MinIO/HTTP URL
     * @param targetDialogue    待合成目标台词文本 (assistant role)
     * @param emotion           情绪/导演指令 (user role, 可空)
     * @param archivePath       MinIO 相对路径 (如 "shots/10/dialogue.mp3")
     * @return 包含 MinIO 访问 URL 与音频时长 (秒) 的结果
     */
    AudioArchiveResult cloneVoice(Long providerId, String modelCode, String referenceAudioUrl, String targetDialogue, String emotion, String archivePath);

    /**
     * 音频归档结果
     */
    record AudioArchiveResult(String audioUrl, Double durationSeconds, long fileSizeBytes) {}
}
