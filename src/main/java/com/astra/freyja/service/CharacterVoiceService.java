package com.astra.freyja.service;

import com.astra.freyja.dto.voice.CharacterVoiceDesignDTO;
import com.astra.freyja.dto.voice.CharacterVoiceResultVO;

/**
 * 角色专属声音设计与母音资产管理服务
 */
public interface CharacterVoiceService {

    /**
     * 为指定角色设计并固化专属母音样音 (MiMo-V2.5-TTS-VoiceDesign)
     *
     * @param characterId 角色 ID
     * @param dto         声音设计请求
     * @return 设计结果与音频访问 VO
     */
    CharacterVoiceResultVO designCharacterVoice(Long characterId, CharacterVoiceDesignDTO dto);

    /**
     * 音色打样试听 (仅生成试听音频，不强制持久化至角色)
     *
     * @param dto 声音设计请求
     * @return 试听结果与音频访问 VO
     */
    CharacterVoiceResultVO previewVoiceDesign(CharacterVoiceDesignDTO dto);
}
