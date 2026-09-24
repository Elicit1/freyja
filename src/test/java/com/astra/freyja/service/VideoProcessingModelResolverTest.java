package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dto.video.ResolvedVideoProcessingModel;
import com.astra.freyja.dto.video.VideoProcessingModelOptionVO;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.service.impl.VideoProcessingModelResolverImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class VideoProcessingModelResolverTest {

    @Mock
    private AiModelMapper modelMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private VideoProcessingModelResolverImpl resolver;

    private AiModel upscaleModel;
    private AiModel interpolationModel;

    @BeforeEach
    void setUp() {
        upscaleModel = new AiModel();
        upscaleModel.setId(101L);
        upscaleModel.setProviderId(1L);
        upscaleModel.setModelCode("realesrgan-x2-video");
        upscaleModel.setModelName("RealESRGAN 2x");
        upscaleModel.setModelType("VIDEO_UPSCALE");
        upscaleModel.setStatus(1);
        upscaleModel.setParamsJson("""
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

        interpolationModel = new AiModel();
        interpolationModel.setId(102L);
        interpolationModel.setProviderId(1L);
        interpolationModel.setModelCode("rife49-video-48fps");
        interpolationModel.setModelName("RIFE 4.9 插帧");
        interpolationModel.setModelType("FRAME_INTERPOLATION");
        interpolationModel.setStatus(1);
        interpolationModel.setParamsJson("""
                {
                  "videoProcessing": {
                    "engineModel": "rife49.pth",
                    "defaultMultiplier": 2,
                    "allowedMultipliers": [2, 4],
                    "defaultCrf": 19,
                    "minCrf": 12,
                    "maxCrf": 28,
                    "defaultClearCacheFrames": 100,
                    "minClearCacheFrames": 50,
                    "maxClearCacheFrames": 300,
                    "defaultPreserveAudio": true,
                    "filenamePrefix": "video_rife/rife_48fps"
                  }
                }
                """);
    }

    @Test
    @DisplayName("modelId 不存在时抛出 404")
    void testModelNotFound_Throws404() {
        when(modelMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(
                1L, 999L, null, "VIDEO_UPSCALE", null, null, null, null, null, null));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("模型已停用时拒绝")
    void testModelDisabled_Throws() {
        upscaleModel.setStatus(0);
        when(modelMapper.selectById(101L)).thenReturn(upscaleModel);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(
                1L, 101L, null, "VIDEO_UPSCALE", null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("停用"));
    }

    @Test
    @DisplayName("模型不属于所选提供商时拒绝")
    void testModelMismatchProvider_Throws() {
        when(modelMapper.selectById(101L)).thenReturn(upscaleModel);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(
                2L, 101L, null, "VIDEO_UPSCALE", null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("不属于当前 AI 提供商"));
    }

    @Test
    @DisplayName("超分任务选择补帧模型时拒绝")
    void testUpscaleOp_WithInterpolationModel_Throws() {
        when(modelMapper.selectById(102L)).thenReturn(interpolationModel);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(
                1L, 102L, null, "VIDEO_UPSCALE", null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("不匹配"));
    }

    @Test
    @DisplayName("正确解析超分模型配置并生成受控 extraBody")
    void testResolveUpscale_Success() {
        when(modelMapper.selectById(101L)).thenReturn(upscaleModel);

        ResolvedVideoProcessingModel result = resolver.resolve(
                1L, 101L, null, "VIDEO_UPSCALE", 4, null, 18, 24, 24, null);

        assertNotNull(result);
        assertEquals(4, result.getScale());
        assertEquals(18, result.getCrf());
        assertEquals("RealESRGAN 2x", result.getModelName());
        assertNotNull(result.getExtraBody());
        assertEquals("RealESRGAN_x2plus.pth", result.getExtraBody().get("upscale_model"));
        assertEquals("video_upscale/realesrgan_x2", result.getExtraBody().get("filename_prefix"));
        assertEquals(18, result.getExtraBody().get("crf"));
    }

    @Test
    @DisplayName("超分倍率超出模型配置范围时拒绝")
    void testResolveUpscale_ScaleOutOfRange_Throws() {
        when(modelMapper.selectById(101L)).thenReturn(upscaleModel);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(
                1L, 101L, null, "VIDEO_UPSCALE", 8, null, null, 24, 24, null));
        assertTrue(ex.getMessage().contains("不在模型允许的范围内"));
    }

    @Test
    @DisplayName("旧 modelCode 调用从模型中心解析")
    void testResolveByModelCode_Success() {
        when(modelMapper.selectList(any())).thenReturn(List.of(interpolationModel));

        ResolvedVideoProcessingModel result = resolver.resolve(
                1L, null, "rife49-video-48fps", "FRAME_INTERPOLATION", null, null, null, 24, null, null);

        assertNotNull(result);
        assertEquals("rife49-video-48fps", result.getModelCode());
        assertEquals(48, result.getTargetFps());
        assertEquals("rife49.pth", result.getExtraBody().get("filename_prefix") != null ? "rife49.pth" : null);
        assertEquals("video_rife/rife_48fps", result.getExtraBody().get("filename_prefix"));
    }

    @Test
    @DisplayName("listModelOptions 脱敏并不暴露底层引擎文件名与路径前缀")
    void testListModelOptions_SafeMasking() {
        when(modelMapper.selectList(any())).thenReturn(List.of(upscaleModel));

        List<VideoProcessingModelOptionVO> options = resolver.listModelOptions(1L, "VIDEO_UPSCALE");
        assertNotNull(options);
        assertEquals(1, options.size());
        VideoProcessingModelOptionVO opt = options.get(0);
        assertEquals("101", opt.getId());
        assertEquals("realesrgan-x2-video", opt.getModelCode());
        assertNotNull(opt.getConfig());
        assertEquals(2, opt.getConfig().getDefaultScale());
        assertEquals(List.of(2, 4), opt.getConfig().getAllowedScales());
        assertEquals(16, opt.getConfig().getDefaultCrf());
    }
}
