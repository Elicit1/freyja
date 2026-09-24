package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.voice.CharacterVoiceDesignDTO;
import com.astra.freyja.dto.voice.CharacterVoiceResultVO;
import com.astra.freyja.service.CharacterVoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 角色声音设计与母音资产管理 Controller (MiMo-V2.5-TTS-VoiceDesign)
 */
@RestController
@RequestMapping("/res/character")
@RequiredArgsConstructor
public class CharacterVoiceController {

    private final CharacterVoiceService characterVoiceService;

    /**
     * 为指定角色设计并固化专属母音样音
     */
    @PostMapping("/{id}/voice/design")
    public R<CharacterVoiceResultVO> designVoice(
            @PathVariable("id") Long id,
            @RequestBody CharacterVoiceDesignDTO dto) {
        dto.setCharacterId(id);
        return R.ok(characterVoiceService.designCharacterVoice(id, dto));
    }

    /**
     * 音色打样试听 (仅生成试听音频，不强制持久化至角色实体)
     */
    @PostMapping("/voice/preview")
    public R<CharacterVoiceResultVO> previewVoice(@RequestBody CharacterVoiceDesignDTO dto) {
        return R.ok(characterVoiceService.previewVoiceDesign(dto));
    }
}
