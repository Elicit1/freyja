package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dto.AiModelDTO;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.impl.AiModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiModelServiceTest {

    @Mock
    private AiModelMapper modelMapper;

    @Mock
    private AiProviderMapper providerMapper;

    @Mock
    private AiModelFactory aiModelFactory;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @org.mockito.Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private AiModelServiceImpl modelService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testCreateSuccess() {
        AiModelDTO dto = new AiModelDTO();
        dto.setProviderId(1L);
        dto.setModelCode("deepseek-chat");
        dto.setModelName("DeepSeek V3");
        dto.setModelType("CHAT");
        dto.setStatus(1);

        when(providerMapper.selectById(1L)).thenReturn(new AiProvider());
        when(modelMapper.selectCount(any())).thenReturn(0L);

        modelService.create(dto);

        verify(modelMapper, times(1)).insert(isA(AiModel.class));
        verify(redisTemplate, times(1)).delete("freyja:ai:model:1");
        verify(aiModelFactory, times(1)).evict(1L);
    }

    @Test
    void testCreateProviderNotFoundThrows() {
        AiModelDTO dto = new AiModelDTO();
        dto.setProviderId(999L);
        dto.setModelCode("gpt-4o");
        dto.setModelName("GPT-4o");
        dto.setStatus(1);

        when(providerMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> modelService.create(dto));
        assertEquals("AI 提供商不存在", ex.getMessage());
        verify(modelMapper, never()).insert(isA(AiModel.class));
    }

    @Test
    void testCreateDuplicateModelCodeThrows() {
        AiModelDTO dto = new AiModelDTO();
        dto.setProviderId(1L);
        dto.setModelCode("deepseek-chat");
        dto.setModelName("DeepSeek V3");
        dto.setStatus(1);

        when(providerMapper.selectById(1L)).thenReturn(new AiProvider());
        when(modelMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> modelService.create(dto));
        assertEquals("该提供商下模型标识已存在", ex.getMessage());
        verify(modelMapper, never()).insert(isA(AiModel.class));
    }

    @Test
    void testListByProviderIdWithCache() {
        AiModel model = new AiModel();
        model.setId(10L);
        model.setModelCode("deepseek-chat");

        when(valueOperations.get("freyja:ai:model:1")).thenReturn(List.of(model));

        List<AiModel> result = modelService.listByProviderId(1L);
        assertEquals(1, result.size());
        assertEquals("deepseek-chat", result.get(0).getModelCode());
        verify(modelMapper, never()).selectList(any());
    }

    @Test
    void testListByProviderIdCacheMiss() {
        AiModel model = new AiModel();
        model.setId(10L);
        model.setModelCode("deepseek-chat");

        when(valueOperations.get("freyja:ai:model:1")).thenReturn(null);
        when(modelMapper.selectList(any())).thenReturn(List.of(model));

        List<AiModel> result = modelService.listByProviderId(1L);
        assertEquals(1, result.size());
        assertEquals("deepseek-chat", result.get(0).getModelCode());
        verify(valueOperations, times(1)).set("freyja:ai:model:1", List.of(model));
    }

    @Test
    void testCreateImageRefModelWithoutMaxImagesThrows() {
        AiModelDTO dto = new AiModelDTO();
        dto.setProviderId(1L);
        dto.setModelCode("flux-img2img");
        dto.setModelName("FLUX Img2Img");
        dto.setModelType("IMG2IMG");
        dto.setStatus(1);
        dto.setMaxImages(null); // 未填写最大参考图限制

        BizException ex = assertThrows(BizException.class, () -> modelService.create(dto));
        assertTrue(ex.getMessage().contains("最大参考图数"));
        verify(modelMapper, never()).insert(isA(AiModel.class));

        // 负数应报错
        dto.setMaxImages(-1);
        BizException exNegative = assertThrows(BizException.class, () -> modelService.create(dto));
        assertTrue(exNegative.getMessage().contains("大于等于0"));
    }

    @Test
    void testCreateImageRefModelSuccess() {
        AiModelDTO dto = new AiModelDTO();
        dto.setProviderId(1L);
        dto.setModelCode("flux-txt-img2img");
        dto.setModelName("FLUX Txt Img2Img");
        dto.setModelType("TXT_IMG2IMG");
        dto.setStatus(1);
        dto.setMaxImages(0); // 0 为合法配置，表示不引用参考图或纯文本输入

        when(providerMapper.selectById(1L)).thenReturn(new AiProvider());
        when(modelMapper.selectCount(any())).thenReturn(0L);

        modelService.create(dto);

        verify(modelMapper, times(1)).insert(argThat((AiModel m) ->
                m.getMaxImages() != null && m.getMaxImages() == 0 && m.getMaxAudios() == null && m.getMaxVideos() == null
        ));
    }

    @Test
    void testCreateVideoModelSuccess() {
        AiModelDTO dto = new AiModelDTO();
        dto.setProviderId(1L);
        dto.setModelCode("minimax-ref2va");
        dto.setModelName("MiniMax Ref2VA");
        dto.setModelType("TXT2VIDEO_REF");
        dto.setStatus(1);
        dto.setMaxImages(5);
        dto.setMaxAudios(4);
        dto.setMaxVideos(2);

        when(providerMapper.selectById(1L)).thenReturn(new AiProvider());
        when(modelMapper.selectCount(any())).thenReturn(0L);

        modelService.create(dto);

        verify(modelMapper, times(1)).insert(argThat((AiModel m) ->
                m.getMaxImages() != null && m.getMaxImages() == 5
                        && m.getMaxAudios() != null && m.getMaxAudios() == 4
                        && m.getMaxVideos() != null && m.getMaxVideos() == 2
        ));
    }

    @Test
    void testCreateChatModelResetsRefConfig() {
        AiModelDTO dto = new AiModelDTO();
        dto.setProviderId(1L);
        dto.setModelCode("deepseek-chat");
        dto.setModelName("DeepSeek V3");
        dto.setModelType("CHAT");
        dto.setStatus(1);
        dto.setMaxImages(5); // 试图为纯文本模型配置参考图
        dto.setMaxAudios(2);

        when(providerMapper.selectById(1L)).thenReturn(new AiProvider());
        when(modelMapper.selectCount(any())).thenReturn(0L);

        modelService.create(dto);

        verify(modelMapper, times(1)).insert(argThat((AiModel m) ->
                m.getMaxImages() == null && m.getMaxAudios() == null && m.getMaxVideos() == null
        ));
    }

    @Test
    void testCreateVideoUpscaleModel_MissingParams_Throws() {
        AiModelDTO dto = new AiModelDTO();
        dto.setProviderId(1L);
        dto.setModelCode("upscale-test");
        dto.setModelName("Upscale Test");
        dto.setModelType("VIDEO_UPSCALE");
        dto.setStatus(1);

        BizException ex = assertThrows(BizException.class, () -> modelService.create(dto));
        assertTrue(ex.getMessage().contains("videoProcessing"));
    }

    @Test
    void testCreateVideoUpscaleModel_Success() {
        AiModelDTO dto = new AiModelDTO();
        dto.setProviderId(1L);
        dto.setModelCode("realesrgan-x2");
        dto.setModelName("RealESRGAN 2x");
        dto.setModelType("VIDEO_UPSCALE");
        dto.setStatus(1);
        dto.setParamsJson("""
                {
                  "videoProcessing": {
                    "engineModel": "RealESRGAN_x2plus.pth",
                    "defaultScale": 2,
                    "allowedScales": [2, 4],
                    "defaultCrf": 16,
                    "minCrf": 10,
                    "maxCrf": 30,
                    "defaultPreserveAudio": true,
                    "filenamePrefix": "video_upscale/realesrgan_x2"
                  }
                }
                """);

        when(providerMapper.selectById(1L)).thenReturn(new AiProvider());
        when(modelMapper.selectCount(any())).thenReturn(0L);

        modelService.create(dto);
        verify(modelMapper, times(1)).insert(isA(AiModel.class));
    }
}
