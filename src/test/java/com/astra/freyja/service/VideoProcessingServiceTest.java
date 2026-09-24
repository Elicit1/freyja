package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.render.RenderTaskVO;
import com.astra.freyja.dto.video.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.impl.VideoProcessingServiceImpl;
import com.astra.freyja.util.CryptoUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class VideoProcessingServiceTest {

    @Mock
    private MediaProcessTaskMapper taskMapper;

    @Mock
    private RenderTaskService renderTaskService;

    @Mock
    private AiImageApiService aiImageApiService;

    @Mock
    private AiProviderMapper providerMapper;

    @Mock
    private AiModelMapper modelMapper;

    @Mock
    private VideoFrameExtractService videoFrameExtractService;

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioProperties minioProperties;

    @Mock
    private CryptoUtil cryptoUtil;

    @Mock
    private VideoProcessSourceResolver sourceResolver;

    @Mock
    private VideoProcessingModelResolver modelResolver;

    @Mock
    private DramaShotMapper shotMapper;

    @Mock
    private DramaShotVideoTakeMapper videoTakeMapper;

    @Mock
    private ShotVideoTakeService shotVideoTakeService;

    @Mock
    private DramaMapper dramaMapper;

    @Mock
    private DramaEpisodeMapper episodeMapper;

    @Mock
    private DramaSceneMapper sceneMapper;

    private ObjectMapper objectMapper;
    private Executor directExecutor = Runnable::run;
    private VideoProcessingServiceImpl videoProcessingService;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MediaProcessTask.class);
        TableInfoHelper.initTableInfo(assistant, DramaShot.class);
        TableInfoHelper.initTableInfo(assistant, DramaShotVideoTake.class);

        objectMapper = new ObjectMapper();
        videoProcessingService = new VideoProcessingServiceImpl(
                taskMapper,
                renderTaskService,
                aiImageApiService,
                providerMapper,
                modelMapper,
                videoFrameExtractService,
                minioClient,
                minioProperties,
                cryptoUtil,
                objectMapper,
                directExecutor,
                sourceResolver,
                modelResolver,
                shotMapper,
                videoTakeMapper,
                shotVideoTakeService,
                dramaMapper,
                episodeMapper,
                sceneMapper
        );
    }

    @Test
    @DisplayName("提交视频超分任务成功创建并同步注册 RenderTask")
    void testSubmitVideoUpscaleTask_Success() {
        AiProvider provider = new AiProvider();
        provider.setId(2032095628944588801L);
        provider.setProviderName("本地 ComfyUI");
        provider.setBaseUrl("http://127.0.0.1:8000/v1");
        provider.setApiKey("encrypted_key");
        provider.setStatus(1);

        when(aiImageApiService.resolveProvider(any())).thenReturn(provider);

        AiModel model = new AiModel();
        model.setId(5001L);
        model.setProviderId(provider.getId());
        model.setModelCode("realesrgan-x2-video");
        model.setModelName("RealESRGAN 2x");

        ResolvedVideoProcessingModel mockModel = ResolvedVideoProcessingModel.builder()
                .model(model)
                .modelId(5001L)
                .modelCode("realesrgan-x2-video")
                .modelName("RealESRGAN 2x")
                .sourceFps(24)
                .targetFps(24)
                .scale(2)
                .crf(16)
                .clearCacheFrames(100)
                .extraBody(Map.of("upscale_model", "RealESRGAN_x2plus.pth"))
                .build();

        when(modelResolver.resolve(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockModel);

        ResolvedVideoProcessSource mockSource = ResolvedVideoProcessSource.builder()
                .sourceType("DIRECT_URL")
                .videoUrl("http://127.0.0.1:9000/video-assets/input.mp4")
                .build();

        when(sourceResolver.resolve(any())).thenReturn(mockSource);

        RenderTaskVO mockRenderVO = new RenderTaskVO();
        mockRenderVO.setId(3000000000000000001L);
        mockRenderVO.setTaskId("VP_TEST_001");
        when(renderTaskService.createTask(any())).thenReturn(mockRenderVO);

        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setOperation("VIDEO_UPSCALE");
        dto.setSourceVideoUrl("http://127.0.0.1:9000/video-assets/input.mp4");
        dto.setProviderId(2032095628944588801L);
        dto.setModelId(5001L);
        dto.setScale(2);
        dto.setSourceFps(24);
        dto.setTargetFps(24);
        dto.setCrf(16);

        VideoProcessResultVO vo = videoProcessingService.submitTask(dto);

        assertNotNull(vo);
        assertTrue(vo.getTaskId().startsWith("VP_"));
        assertEquals("VIDEO_UPSCALE", vo.getOperation());
        assertEquals("realesrgan-x2-video", vo.getModelCode());
        assertEquals("5001", vo.getModelId());
        assertEquals("QUEUED", vo.getStatus());
        assertEquals(2, vo.getScale());
        assertEquals(24, vo.getSourceFps());
        assertEquals(16, vo.getCrf());

        ArgumentCaptor<MediaProcessTask> taskCaptor = ArgumentCaptor.forClass(MediaProcessTask.class);
        verify(taskMapper, atLeastOnce()).insert(taskCaptor.capture());
        MediaProcessTask inserted = taskCaptor.getValue();
        assertEquals("VIDEO_UPSCALE", inserted.getOperation());
        assertEquals("http://127.0.0.1:9000/video-assets/input.mp4", inserted.getSourceVideoUrl());
        assertEquals(5001L, inserted.getModelId());

        ArgumentCaptor<RenderTaskVO> renderCaptor = ArgumentCaptor.forClass(RenderTaskVO.class);
        verify(renderTaskService).createTask(renderCaptor.capture());
        assertEquals("VIDEO_UPSCALE", renderCaptor.getValue().getTaskType());
    }

    @Test
    @DisplayName("SHOT_CURRENT 分镜来源创建带有 drama/episode/scene/shot 上下文的 RenderTask")
    void testSubmitVideoUpscaleTask_WithShotSource_Success() {
        AiProvider provider = new AiProvider();
        provider.setId(100L);
        provider.setProviderName("网关");
        when(aiImageApiService.resolveProvider(any())).thenReturn(provider);

        AiModel model = new AiModel();
        model.setId(5001L);
        model.setProviderId(100L);
        model.setModelCode("realesrgan-x2-video");
        model.setModelName("RealESRGAN 2x");

        ResolvedVideoProcessingModel mockModel = ResolvedVideoProcessingModel.builder()
                .model(model)
                .modelId(5001L)
                .modelCode("realesrgan-x2-video")
                .modelName("RealESRGAN 2x")
                .sourceFps(24)
                .targetFps(24)
                .scale(2)
                .crf(16)
                .clearCacheFrames(100)
                .extraBody(Map.of())
                .build();

        when(modelResolver.resolve(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockModel);

        ResolvedVideoProcessSource mockSource = ResolvedVideoProcessSource.builder()
                .sourceType("SHOT_CURRENT")
                .videoUrl("http://127.0.0.1:9000/video-assets/shot_s03.mp4")
                .dramaId(11L)
                .episodeId(22L)
                .sceneId(33L)
                .shotId(44L)
                .shotNo(3)
                .shotName("夜路追逐")
                .build();

        when(sourceResolver.resolve(any())).thenReturn(mockSource);

        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setOperation("VIDEO_UPSCALE");
        dto.setSourceType("SHOT_CURRENT");
        dto.setSourceShotId(44L);
        dto.setModelId(5001L);

        VideoProcessResultVO vo = videoProcessingService.submitTask(dto);
        assertNotNull(vo);
        assertEquals("44", vo.getSourceShotId());

        ArgumentCaptor<RenderTaskVO> renderCaptor = ArgumentCaptor.forClass(RenderTaskVO.class);
        verify(renderTaskService).createTask(renderCaptor.capture());
        RenderTaskVO renderVO = renderCaptor.getValue();
        assertEquals(11L, renderVO.getDramaId());
        assertEquals(22L, renderVO.getEpisodeId());
        assertEquals(33L, renderVO.getSceneId());
        assertEquals(44L, renderVO.getShotId());
        assertTrue(renderVO.getTaskName().contains("[S03 夜路追逐]"));
    }

    @Test
    @DisplayName("提交不支持的处理类型抛出 BizException")
    void testSubmitInvalidOperation_ThrowsException() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setOperation("UNKNOWN_OP");
        dto.setSourceVideoUrl("http://minio/video.mp4");

        assertThrows(BizException.class, () -> videoProcessingService.submitTask(dto));
    }

    @Test
    @DisplayName("querySourceShots 仅查询有视频的分镜并正确拼装各层级信息")
    void testQuerySourceShots() {
        DramaShot shot = new DramaShot();
        shot.setId(101L);
        shot.setDramaId(1L);
        shot.setEpisodeId(2L);
        shot.setSceneId(3L);
        shot.setShotNo(1);
        shot.setShotName("镜头一");
        shot.setVideoUrl("http://127.0.0.1:9000/video-assets/101.mp4");
        shot.setDuration(new BigDecimal("5.00"));

        Page<DramaShot> shotPage = new Page<>(1, 20, 1);
        shotPage.setRecords(List.of(shot));

        when(shotMapper.selectPage(any(), any())).thenReturn(shotPage);

        Drama drama = new Drama();
        drama.setId(1L);
        drama.setTitle("测试剧");
        when(dramaMapper.selectBatchIds(any())).thenReturn(List.of(drama));

        DramaEpisode ep = new DramaEpisode();
        ep.setId(2L);
        ep.setTitle("第1集");
        ep.setEpisodeNo(1);
        when(episodeMapper.selectBatchIds(any())).thenReturn(List.of(ep));

        DramaScene sc = new DramaScene();
        sc.setId(3L);
        sc.setName("客厅");
        sc.setSceneNo(1);
        when(sceneMapper.selectBatchIds(any())).thenReturn(List.of(sc));

        DramaShotVideoTake take = new DramaShotVideoTake();
        take.setId(888L);
        take.setShotId(101L);
        take.setStatus("AVAILABLE");
        when(videoTakeMapper.selectList(any())).thenReturn(List.of(take));

        ShotVideoSourceQuery query = new ShotVideoSourceQuery();
        query.setDramaId(1L);

        Page<ShotVideoSourceOptionVO> result = videoProcessingService.querySourceShots(query);
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        ShotVideoSourceOptionVO vo = result.getRecords().get(0);
        assertEquals("101", vo.getShotId());
        assertEquals("测试剧", vo.getDramaTitle());
        assertEquals("第1集", vo.getEpisodeTitle());
        assertEquals("客厅", vo.getSceneName());
        assertEquals(1, vo.getVideoTakeCount());
    }

    @Test
    @DisplayName("后处理产物保存为分镜历史：创建新 Take 并设为当前生效视频")
    void testSaveToShot_Success() {
        String taskId = "VP_TEST_SAVE_001";
        MediaProcessTask task = new MediaProcessTask();
        task.setId(10L);
        task.setTaskId(taskId);
        task.setOperation("VIDEO_UPSCALE");
        task.setStatus("SUCCESS");
        task.setOutputVideoUrl("http://minio/bucket/video-processing/output.mp4");
        task.setCoverImageUrl("http://minio/bucket/video-processing/cover.jpg");
        task.setSourceShotId(101L);
        task.setProviderId(1L);
        task.setProviderName("ComfyUI");
        task.setModelCode("seedvr-upscale");
        task.setWidth(1920);
        task.setHeight(1080);
        when(taskMapper.selectOne(any())).thenReturn(task);

        DramaShot shot = new DramaShot();
        shot.setId(101L);
        shot.setDramaId(1L);
        shot.setEpisodeId(2L);
        shot.setSceneId(3L);
        shot.setShotGroupId(4L);
        when(shotMapper.selectById(101L)).thenReturn(shot);

        // 未录入过
        when(videoTakeMapper.selectOne(any())).thenReturn(null);
        when(shotVideoTakeService.allocateNextTakeNo(101L)).thenReturn(3);

        com.astra.freyja.dto.video.VideoProcessSaveToShotDTO dto = new com.astra.freyja.dto.video.VideoProcessSaveToShotDTO();
        dto.setSetAsCurrent(true);

        var result = videoProcessingService.saveToShot(taskId, dto);

        assertNotNull(result);
        assertEquals(101L, result.getShotId());
        assertEquals(3, result.getTakeNo());
        assertEquals("VIDEO_UPSCALE", result.getSourceType());
        assertEquals("http://minio/bucket/video-processing/output.mp4", result.getVideoUrl());
        verify(videoTakeMapper).insert(any(DramaShotVideoTake.class));
        verify(shotVideoTakeService).selectTake(eq(101L), any());
    }
}
