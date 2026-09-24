package com.astra.freyja.dto.voice;

import lombok.Data;

/**
 * 角色声音设计请求 DTO (MiMo-V2.5-TTS-VoiceDesign)
 */
@Data
public class CharacterVoiceDesignDTO {

    /** 目标角色 ID (可选，若传入则自动保存回填至角色资产) */
    private Long characterId;

    /** 指定 AI 提供商 ID (可空，默认使用激活的具备 TTS/OPENAI 的提供商) */
    private Long providerId;

    /** 指定模型编码 (默认 "mimo-v2.5-tts-voicedesign") */
    private String modelCode;

    /** 自然语言音色设计提示词 (如: "28岁成熟冷峻的商界霸总男声，声线低沉磁性，咬字克制稳定") */
    private String voiceDesc;

    /** 试音参考文本 (如: "在这个商界，没有永恒的盟友，只有不可撼动的利益。") */
    private String sampleText;

    /** 是否自动保存并固化至该角色实体 (默认 true) */
    private Boolean autoSave = true;
}
