package com.astra.freyja.service;

import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dto.voice.BatchVoiceResultVO;
import com.astra.freyja.dto.voice.ShotVoiceGenerateDTO;
import com.astra.freyja.entity.DramaEpisode;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.service.impl.DramaShotVoiceServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DramaShotVoiceServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DramaShot.class);
        TableInfoHelper.initTableInfo(assistant, DramaEpisode.class);
        TableInfoHelper.initTableInfo(assistant, ResCharacter.class);
    }

    @Mock
    private DramaShotMapper shotMapper;

    @Mock
    private DramaEpisodeMapper episodeMapper;

    @Mock
    private ResCharacterMapper characterMapper;

    @Mock
    private AiAudioApiService aiAudioApiService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DramaShotVoiceServiceImpl dramaShotVoiceService;

    @Test
    void testGenerateShotVoice_Success() {
        Long shotId = 501L;
        DramaShot shot = new DramaShot();
        shot.setId(shotId);
        shot.setDramaId(1L);
        shot.setEpisodeId(10L);
        shot.setShotNo(1);
        shot.setShotName("S01-01");
        shot.setDialogue("这笔订单，我们吃定了。");
        shot.setDialogueSpeaker("顾总");
        shot.setDuration(new BigDecimal("3.0"));

        ResCharacter character = new ResCharacter();
        character.setId(201L);
        character.setName("顾总");
        character.setVoiceSampleUrl("http://minio/sample.wav");

        when(shotMapper.selectById(shotId)).thenReturn(shot);
        when(characterMapper.selectOne(any())).thenReturn(character);
        when(aiAudioApiService.cloneVoice(any(), any(), eq("http://minio/sample.wav"), eq("这笔订单，我们吃定了。"), any(), anyString()))
                .thenReturn(new AiAudioApiService.AudioArchiveResult("http://minio/shot501.mp3", 4.5, 65000L));

        ShotVoiceGenerateDTO dto = new ShotVoiceGenerateDTO();
        dto.setShotId(shotId);
        dto.setEmotion("霸气冷傲");

        BatchVoiceResultVO.ShotVoiceItemResult result = dramaShotVoiceService.generateShotVoice(shotId, dto);

        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertEquals("http://minio/shot501.mp3", result.getAudioUrl());
        assertEquals(4.5, result.getDuration());

        // 验证时长从 3.0 校准延展为 4.5
        verify(shotMapper, times(1)).updateById((DramaShot) argThat(s ->
                "http://minio/shot501.mp3".equals(((DramaShot) s).getAudioUrl()) &&
                        new BigDecimal("4.5").compareTo(((DramaShot) s).getDuration()) == 0
        ));
    }

    @Test
    void testBatchGenerateEpisodeVoice_Success() {
        Long episodeId = 10L;
        DramaEpisode episode = new DramaEpisode();
        episode.setId(episodeId);

        DramaShot shot1 = new DramaShot();
        shot1.setId(501L);
        shot1.setDramaId(1L);
        shot1.setEpisodeId(episodeId);
        shot1.setShotNo(1);
        shot1.setShotName("S01-01");
        shot1.setDialogue("你好");
        shot1.setDialogueSpeaker("顾总");

        DramaShot shot2 = new DramaShot();
        shot2.setId(502L);
        shot2.setDramaId(1L);
        shot2.setEpisodeId(episodeId);
        shot2.setShotNo(2);
        shot2.setShotName("S01-02");
        shot2.setDialogue(null); // 无对白，应跳过

        ResCharacter character = new ResCharacter();
        character.setId(201L);
        character.setName("顾总");
        character.setVoiceSampleUrl("http://minio/sample.wav");

        when(episodeMapper.selectById(episodeId)).thenReturn(episode);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));
        when(shotMapper.selectById(501L)).thenReturn(shot1);
        when(characterMapper.selectOne(any())).thenReturn(character);
        when(aiAudioApiService.cloneVoice(any(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new AiAudioApiService.AudioArchiveResult("http://minio/shot501.mp3", 1.8, 20000L));

        BatchVoiceResultVO batchResult = dramaShotVoiceService.batchGenerateEpisodeVoice(episodeId);

        assertNotNull(batchResult);
        assertEquals(2, batchResult.getTotalShots());
        assertEquals(1, batchResult.getMatchedShots());
        assertEquals(1, batchResult.getSuccessCount());
        assertEquals(1, batchResult.getSkippedCount());
        assertEquals(0, batchResult.getFailureCount());
    }
}
