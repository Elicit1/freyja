package com.astra.freyja.dto.voice;

import lombok.Data;

/**
 * 分镜台词克隆配音请求 DTO (MiMo-V2.5-TTS-VoiceClone)
 */
@Data
public class ShotVoiceGenerateDTO {

    /** 分镜 ID */
    private Long shotId;

    /** 指定说话人角色 ID (可选，若不传则从分镜自动推断) */
    private Long characterId;

    /** 覆盖台词文本 (可选，若不传则使用分镜 dialogue) */
    private String customDialogue;

    /** 导演/情绪指令 (如: "冷酷、略带嘲讽", "悲伤抽泣", "低沉私语") */
    private String emotion;

    /** 指定参考样音 URL (可选，若不传则从角色 assets 读取) */
    private String referenceAudioUrl;

    /** 指定 AI 提供商 ID (可空) */
    private Long providerId;

    /** 指定模型编码 (默认 "mimo-v2.5-tts-voiceclone") */
    private String modelCode;

    /** 是否自动覆盖分镜 audio_url 并校准时长 (默认 true) */
    private Boolean autoSave = true;
}
