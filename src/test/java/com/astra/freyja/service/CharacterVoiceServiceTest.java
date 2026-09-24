package com.astra.freyja.service;

import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dto.voice.CharacterVoiceDesignDTO;
import com.astra.freyja.dto.voice.CharacterVoiceResultVO;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.service.impl.CharacterVoiceServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CharacterVoiceServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ResCharacter.class);
    }

    @Mock
    private ResCharacterMapper characterMapper;

    @Mock
    private AiAudioApiService aiAudioApiService;

    @InjectMocks
    private CharacterVoiceServiceImpl characterVoiceService;

    @Test
    void testDesignCharacterVoice_Success() {
        Long charId = 101L;
        ResCharacter character = new ResCharacter();
        character.setId(charId);
        character.setName("顾总");
        character.setPersonality("冷酷霸道，心思缜密");

        when(characterMapper.selectById(charId)).thenReturn(character);
        when(aiAudioApiService.designVoice(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new AiAudioApiService.AudioArchiveResult("http://minio/sample.wav", 4.2, 102400L));

        CharacterVoiceDesignDTO dto = new CharacterVoiceDesignDTO();
        dto.setVoiceDesc("30岁低沉磁性男声");
        dto.setSampleText("商界战场，胜者为王。");
        dto.setAutoSave(true);

        CharacterVoiceResultVO result = characterVoiceService.designCharacterVoice(charId, dto);

        assertNotNull(result);
        assertEquals(charId, result.getCharacterId());
        assertEquals("http://minio/sample.wav", result.getVoiceSampleUrl());
        assertEquals(4.2, result.getDuration());

        verify(characterMapper, times(1)).updateById((ResCharacter) argThat(c ->
                "http://minio/sample.wav".equals(((ResCharacter) c).getVoiceSampleUrl()) &&
                        "30岁低沉磁性男声".equals(((ResCharacter) c).getVoiceDesc())
        ));
    }

    @Test
    void testPreviewVoiceDesign_Success() {
        when(aiAudioApiService.designVoice(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new AiAudioApiService.AudioArchiveResult("http://minio/preview.wav", 3.5, 80000L));

        CharacterVoiceDesignDTO dto = new CharacterVoiceDesignDTO();
        dto.setVoiceDesc("清朗明快的少年音");
        dto.setSampleText("快看，流星划过去了！");

        CharacterVoiceResultVO result = characterVoiceService.previewVoiceDesign(dto);

        assertNotNull(result);
        assertEquals("http://minio/preview.wav", result.getVoiceSampleUrl());
        assertEquals(3.5, result.getDuration());
    }
}
