package com.astra.freyja.dto.voice;

import lombok.Data;

/**
 * 角色声音设计/打样返回 VO
 */
@Data
public class CharacterVoiceResultVO {

    /** 关联角色 ID (若有) */
    private Long characterId;

    /** 角色名称 */
    private String characterName;

    /** 生成/固化的角色基准参考样音 (MinIO URL) */
    private String voiceSampleUrl;

    /** 音色设计提示词 */
    private String voiceDesc;

    /** 试音参考文本 */
    private String sampleText;

    /** 实际音频时长 (秒) */
    private Double duration;

    /** 选用模型标识 */
    private String modelCode;
}
