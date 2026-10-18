package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dto.voice.CharacterVoiceDesignDTO;
import com.astra.freyja.dto.voice.CharacterVoiceResultVO;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.service.AiAudioApiService;
import com.astra.freyja.service.CharacterVoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色专属声音设计服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterVoiceServiceImpl implements CharacterVoiceService {

    private final ResCharacterMapper characterMapper;
    private final AiAudioApiService aiAudioApiService;
    private final com.astra.freyja.service.DubbingService dubbingService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CharacterVoiceResultVO designCharacterVoice(Long characterId, CharacterVoiceDesignDTO dto) {
        if (characterId == null || characterId <= 0) {
            throw new BizException(400, "角色 ID 不能为空");
        }
        ResCharacter character = characterMapper.selectById(characterId);
        if (character == null) {
            throw new BizException(404, "目标角色不存在: " + characterId);
        }

        // 提取声音设计提示词：优先取请求参数，缺省取角色性格人设
        String voiceDesc = dto.getVoiceDesc();
        if (StringUtils.isBlank(voiceDesc)) {
            voiceDesc = character.getPersonality();
        }
        if (StringUtils.isBlank(voiceDesc)) {
            throw new BizException(400, "声音设计提示词不能为空，请在角色抽屉中输入音色描述");
        }

        String sampleText = StringUtils.defaultIfBlank(
                dto.getSampleText(),
                StringUtils.defaultIfBlank(character.getVoiceSampleText(), "我是" + character.getName() + "，这就是我的真实声音。")
        );

        if ((dto.getAutoSave() == null || dto.getAutoSave()) && dubbingService.base(characterId) != null) {
            throw new BizException(409, "角色已有基础声音，请前往配音中心管理或明确替换");
        }
        String archivePath = String.format("dubbing/%d/legacy_%s.wav", characterId, java.util.UUID.randomUUID());

        AiAudioApiService.AudioArchiveResult archiveResult = aiAudioApiService.designVoice(
                dto.getProviderId(),
                dto.getModelCode(),
                voiceDesc,
                sampleText,
                archivePath
        );

        // Compatibility endpoint adopts a base through the same uniqueness/validation rules.
        if (dto.getAutoSave() == null || dto.getAutoSave()) {
            archiveResult = dubbingService.adoptDesignedBase(characterId, sampleText, voiceDesc, archiveResult);
        }

        CharacterVoiceResultVO vo = new CharacterVoiceResultVO();
        vo.setCharacterId(characterId);
        vo.setCharacterName(character.getName());
        vo.setVoiceDesc(voiceDesc);
        vo.setSampleText(sampleText);
        vo.setVoiceSampleUrl(archiveResult.audioUrl());
        vo.setDuration(archiveResult.durationSeconds());
        vo.setModelCode(dto.getModelCode());
        return vo;
    }

    @Override
    public CharacterVoiceResultVO previewVoiceDesign(CharacterVoiceDesignDTO dto) {
        if (StringUtils.isBlank(dto.getVoiceDesc())) {
            throw new BizException(400, "试听音色设计提示词 (voiceDesc) 不能为空");
        }
        String sampleText = StringUtils.defaultIfBlank(dto.getSampleText(), "你好，这是为你试炼生成的参考音色片段。");
        String archivePath = String.format("preview/voice/sample_%d.wav", System.currentTimeMillis());

        AiAudioApiService.AudioArchiveResult archiveResult = aiAudioApiService.designVoice(
                dto.getProviderId(),
                dto.getModelCode(),
                dto.getVoiceDesc(),
                sampleText,
                archivePath
        );

        CharacterVoiceResultVO vo = new CharacterVoiceResultVO();
        vo.setCharacterId(dto.getCharacterId());
        vo.setVoiceDesc(dto.getVoiceDesc());
        vo.setSampleText(sampleText);
        vo.setVoiceSampleUrl(archiveResult.audioUrl());
        vo.setDuration(archiveResult.durationSeconds());
        vo.setModelCode(dto.getModelCode());
        return vo;
    }
}
