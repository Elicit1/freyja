package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 分镜参考音频对象 (上限 3 段, 单段 2-15s, 总长 ≤ 15s)。
 * 来源: 人物 TTS 配音或自行上传。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotRefAudioDTO {

    /** 唯一标识 (前端生成或自增) */
    private String id;

    /** 来源类型: TTS(人物TTS配音)/UPLOAD(自行上传) */
    private String sourceType;

    /** 关联角色 ID (当 sourceType=TTS 时) */
    private Long characterId;

    /** 说话人名称 */
    private String characterName;

    /** 音频 URL (MinIO URL) */
    private String audioUrl;

    /** 音频时长 (秒, 3.0 ~ 8.0) */
    private BigDecimal duration;

    /** 音频名称/文件名/标题 */
    private String name;

    /** 台词或文本内容 */
    private String text;

    /**
     * 音频用途模式 (MiniMax H3):
     * DIALOGUE_REUSE: 直接复用该段对白声音
     * VOICE_TIMBRE: 只参考音色、语速和演绎方式
     * BGM_REUSE: 直接复用背景音乐
     * BGM_STYLE: 只参考音乐风格、节奏或配器
     * SFX_REUSE: 直接复用音效
     * SFX_REFERENCE: 只参考音效质感
     * RHYTHM_REFERENCE: 只参考节拍和时间节奏
     */
    private String usageMode;

    /** 发音语言 (如 Chinese, English, Japanese, 默认 Chinese) */
    private String language;
}
