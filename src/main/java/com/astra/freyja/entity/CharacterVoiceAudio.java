package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** A clone owns an immutable dry recording and an optional, replaceable FFmpeg result. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("character_voice_audio")
public class CharacterVoiceAudio extends BaseEntity {
    private Long characterId;
    private Long baseAudioId;
    private String kind;
    private String name;
    private String text;
    private String emotion;
    private String voiceDesc;
    private String rawAudioUrl;
    private Double rawDuration;
    private String processedAudioUrl;
    private Double processedDuration;
    private String effectsJson;
    private Integer revision;
    private String status;
    private String errorMessage;
    private Long providerId;
    private String modelCode;
    private Long taskId;
}
