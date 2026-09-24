package com.astra.freyja.service;

import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResCharacterOutfitMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.comfy.ComfyRenderTaskVO;
import com.astra.freyja.dto.drama.DramaShotDTO;
import com.astra.freyja.dto.drama.DramaShotFirstFrameDTO;
import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
import com.astra.freyja.dto.drama.DramaShotReorderDTO;
import com.astra.freyja.dto.res.CharacterShotRefDTO;
import com.astra.freyja.dto.res.PromptAssembleRequestDTO;
import com.astra.freyja.dto.res.PromptAssembleResultVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.DramaShotVideoTake;
import com.astra.freyja.service.impl.DramaShotServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DramaShotServiceTest {

    @Mock
    private DramaShotMapper shotMapper;

    @Mock
    private DramaSceneMapper sceneMapper;

    @Mock
    private DramaMapper dramaMapper;

    @Mock
    private ResSceneMapper resSceneMapper;

    @Mock
    private ResCharacterMapper characterMapper;

    @Mock
    private ResCharacterOutfitMapper outfitMapper;

    @Mock
    private com.astra.freyja.dao.ResPropMapper resPropMapper;

    @Mock
    private DramaEpisodeService episodeService;

    @Mock
    private com.astra.freyja.dao.DramaShotGroupMapper shotGroupMapper;

    @Mock
    private PromptAssembleService promptAssembleService;

    @Mock
    private com.astra.freyja.service.AiImageApiService aiImageApiService;

    @Mock
    private com.astra.freyja.service.RenderTaskService renderTaskService;

    @Mock
    private java.util.concurrent.Executor renderAsyncExecutor;

    @Mock
    private ShotVideoTakeService shotVideoTakeService;

    @Mock
    private com.astra.freyja.dao.DramaShotVideoTakeMapper shotVideoTakeMapper;

    @Mock
    private com.astra.freyja.dao.RenderTaskMapper renderTaskMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DramaShotServiceImpl shotService;

    @Test
    void testCreateShotSuccess() {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        scene.setDramaId(1L);
        scene.setEpisodeId(5L);
        scene.setSceneNo(1);

        when(sceneMapper.selectById(10L)).thenReturn(scene);
        when(shotMapper.selectList(any())).thenReturn(List.of());

        DramaShotDTO dto = new DramaShotDTO();
        dto.setSceneId(10L);
        dto.setActionDescription("主角推门进入");
        dto.setShotType("MEDIUM_SHOT");
        dto.setCameraMovement("STATIC");
        dto.setCameraMovementLocked(true);
        dto.setDuration(new BigDecimal("3.00"));

        CharacterShotRefDTO ref = new CharacterShotRefDTO();
        ref.setCharacterId(100L);
        ref.setActionPrompt("walking forward");
        dto.setCharacterRefs(List.of(ref));

        doAnswer(invocation -> {
            DramaShot s = invocation.getArgument(0);
            s.setId(1000L);
            return 1;
        }).when(shotMapper).insert(any(DramaShot.class));

        Long id = shotService.create(dto);
        assertEquals(1000L, id);
        verify(shotMapper, times(1)).insert(argThat((DramaShot shot) ->
                shot != null && "MEDIUM_SHOT".equals(shot.getShotType())
                        && Boolean.FALSE.equals(shot.getShotTypeLocked())
                        && "STATIC".equals(shot.getCameraMovement())
                        && Boolean.TRUE.equals(shot.getCameraMovementLocked())));
        verify(episodeService, times(1)).recalculateEpisodeDuration(5L);
    }

    @Test
    void testUpdatePreservesExplicitCameraConstraintsWhenOlderClientOmitsThem() {
        DramaShot existing = new DramaShot();
        existing.setId(1000L);
        existing.setEpisodeId(5L);
        existing.setShotType("CLOSE_UP");
        existing.setShotTypeLocked(true);
        existing.setCameraMovement("STATIC");
        existing.setCameraMovementLocked(true);
        when(shotMapper.selectById(1000L)).thenReturn(existing);

        DramaShotDTO dto = new DramaShotDTO();
        dto.setId(1000L);
        dto.setActionDescription("更新剧情描述");

        shotService.update(dto);

        verify(shotMapper).updateById((DramaShot) argThat((DramaShot shot) -> shot != null
                && "CLOSE_UP".equals(shot.getShotType())
                && Boolean.TRUE.equals(shot.getShotTypeLocked())
                && "STATIC".equals(shot.getCameraMovement())
                && Boolean.TRUE.equals(shot.getCameraMovementLocked())));
    }

    @Test
    void testUpdateCanExplicitlyClearCameraConstraints() {
        DramaShot existing = new DramaShot();
        existing.setId(1000L);
        existing.setShotType("CLOSE_UP");
        existing.setShotTypeLocked(true);
        existing.setCameraMovement("STATIC");
        existing.setCameraMovementLocked(true);
        when(shotMapper.selectById(1000L)).thenReturn(existing);

        DramaShotDTO dto = new DramaShotDTO();
        dto.setId(1000L);
        dto.setShotTypeLocked(false);
        dto.setCameraMovementLocked(false);

        shotService.update(dto);

        verify(shotMapper).updateById((DramaShot) argThat((DramaShot shot) -> shot != null
                && shot.getShotType() == null
                && Boolean.FALSE.equals(shot.getShotTypeLocked())
                && shot.getCameraMovement() == null
                && Boolean.FALSE.equals(shot.getCameraMovementLocked())));
    }

    @Test
    void testUpdatePersistsAutoAsUnspecifiedWithoutStaticFallback() {
        DramaShot existing = new DramaShot();
        existing.setId(1000L);
        existing.setShotType("CLOSE_UP");
        existing.setShotTypeLocked(true);
        existing.setCameraMovement("STATIC");
        existing.setCameraMovementLocked(true);
        when(shotMapper.selectById(1000L)).thenReturn(existing);

        DramaShotDTO dto = new DramaShotDTO();
        dto.setId(1000L);
        dto.setShotType("AUTO");
        dto.setCameraMovement("AUTO");
        dto.setShotTypeLocked(false);
        dto.setCameraMovementLocked(false);

        shotService.update(dto);

        ArgumentCaptor<DramaShot> savedShot = ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).updateById(savedShot.capture());
        assertEquals("AUTO", savedShot.getValue().getShotType());
        assertEquals("AUTO", savedShot.getValue().getCameraMovement());
        assertFalse(savedShot.getValue().getShotTypeLocked());
        assertFalse(savedShot.getValue().getCameraMovementLocked());

        when(shotMapper.selectById(1000L)).thenReturn(savedShot.getValue());
        var loaded = shotService.getById(1000L);
        assertEquals("AUTO", loaded.getShotType());
        assertEquals("AUTO", loaded.getCameraMovement());
    }

    @Test
    void testCloneShotSuccess() {
        DramaShot source = new DramaShot();
        source.setId(1000L);
        source.setSceneId(10L);
        source.setEpisodeId(5L);
        source.setDramaId(1L);
        source.setShotNo(1);
        source.setShotName("S01-01");
        source.setPrompt("1man, cinematic");
        source.setRenderStatus("SUCCESS");

        when(shotMapper.selectById(1000L)).thenReturn(source);
        when(shotMapper.selectList(any())).thenReturn(List.of(source));

        doAnswer(invocation -> {
            DramaShot s = invocation.getArgument(0);
            s.setId(1001L);
            return 1;
        }).when(shotMapper).insert(any(DramaShot.class));

        Long clonedId = shotService.cloneShot(1000L);
        assertEquals(1001L, clonedId);

        verify(shotMapper, times(1)).insert((DramaShot) argThat(s ->
                s != null &&
                ((DramaShot) s).getShotNo() == 2 &&
                "INIT".equals(((DramaShot) s).getRenderStatus()) &&
                "S01-01 (副本)".equals(((DramaShot) s).getShotName())
        ));
    }

    @Test
    void testReorderShots() {
        DramaScene targetScene = new DramaScene();
        targetScene.setId(10L);
        targetScene.setEpisodeId(5L);
        targetScene.setDramaId(1L);

        when(sceneMapper.selectById(10L)).thenReturn(targetScene);

        DramaShotReorderDTO reorderDTO = new DramaShotReorderDTO();
        reorderDTO.setSceneId(10L);
        reorderDTO.setShotIds(List.of(1002L, 1001L, 1003L));

        shotService.reorderShots(reorderDTO);

        verify(shotMapper, times(3)).updateById(any(DramaShot.class));
        verify(episodeService, times(1)).recalculateEpisodeDuration(5L);
    }

    @Test
    void testAssembleShotPrompt() {
        DramaShot shot = new DramaShot();
        shot.setId(1000L);
        shot.setSceneId(10L);
        shot.setDramaId(1L);
        shot.setShotType("CLOSE_UP");
        shot.setCameraMovement("PUSH_IN");
        shot.setActionDescription("主角怒目而视");

        DramaScene scene = new DramaScene();
        scene.setId(10L);
        scene.setResSceneId(20L);

        Drama drama = new Drama();
        drama.setId(1L);
        drama.setStylePreset("cinematic-realism");

        when(shotMapper.selectById(1000L)).thenReturn(shot);
        when(sceneMapper.selectById(10L)).thenReturn(scene);
        when(dramaMapper.selectById(1L)).thenReturn(drama);

        PromptAssembleResultVO assembleResult = PromptAssembleResultVO.builder()
                .positivePrompt("masterpiece, 1man, close up, push in, angry, cinematic-realism")
                .negativePrompt("low quality, bad anatomy")
                .build();

        when(promptAssembleService.assemble(any(PromptAssembleRequestDTO.class))).thenReturn(assembleResult);

        PromptAssembleResultVO res = shotService.assembleShotPrompt(1000L);
        assertNotNull(res);
        assertEquals(assembleResult.getPositivePrompt(), res.getPositivePrompt());

        verify(shotMapper, times(1)).updateById((DramaShot) argThat(s ->
                s != null &&
                assembleResult.getPositivePrompt().equals(((DramaShot) s).getPrompt()) &&
                assembleResult.getNegativePrompt().equals(((DramaShot) s).getNegativePrompt())
        ));
    }

    @Test
    void testSubmitShotRender() {
        DramaShot shot = new DramaShot();
        shot.setId(1000L);
        shot.setSceneId(10L);
        shot.setDramaId(1L);
        shot.setPrompt("1man, masterpiece");
        shot.setNegativePrompt("bad");
        shot.setComfyWorkflowTemplateId("SDXL_TXT2IMG");

        when(shotMapper.selectById(1000L)).thenReturn(shot);

        DramaShotRenderRequestDTO req = new DramaShotRenderRequestDTO();
        req.setSeed(9999L);

        ComfyRenderTaskVO result = shotService.submitShotRender(1000L, req);
        assertNotNull(result);
        assertNotNull(result.getTaskId());

        verify(shotMapper, times(1)).updateById((DramaShot) argThat(s ->
                s != null &&
                "QUEUED".equals(((DramaShot) s).getRenderStatus())
        ));
        verify(renderAsyncExecutor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void testExecuteShotVideoRenderAsync_success() {
        DramaShot shot = new DramaShot();
        shot.setId(1000L);
        shot.setDramaId(1L);
        shot.setShotNo(1);
        shot.setPrompt("1man, cyberpunk street");
        shot.setRenderStatus("QUEUED");

        when(shotMapper.selectById(1000L)).thenReturn(shot);

        com.astra.freyja.dto.drama.VideoGenerationResultVO genResult = com.astra.freyja.dto.drama.VideoGenerationResultVO.builder()
                .videoUrl("http://minio/video_123.mp4")
                .lastFrameUrl(null)
                .build();
        when(aiImageApiService.generateAndArchiveVideo(eq(1000L), eq(1L), any(), any()))
                .thenReturn(genResult);
        when(shotVideoTakeService.recordGeneratedTake(any(), any(), any(), any())).thenAnswer(inv -> {
            shot.setVideoUrl(genResult.getVideoUrl());
            shot.setRenderStatus("SUCCESS");
            shot.setLastFrameUrl(null);
            shot.setLastFrameSourceVideoUrl(null);
            DramaShotVideoTake take = new DramaShotVideoTake();
            take.setId(9001L);
            take.setVideoUrl(genResult.getVideoUrl());
            return take;
        });

        shotService.executeShotVideoRenderAsync(1000L, "TASK_123", new DramaShotRenderRequestDTO());

        verify(renderTaskService).updateProgress("TASK_123", 0, "正在生成视频并归档至 MinIO...");
        verify(aiImageApiService).generateAndArchiveVideo(eq(1000L), eq(1L), any(), any());
        verify(shotVideoTakeService).recordGeneratedTake(eq(shot), any(), any(), eq(genResult));
        verify(renderTaskService).finishTask("TASK_123", "http://minio/video_123.mp4", null);
        assertEquals("http://minio/video_123.mp4", shot.getVideoUrl());
        assertNull(shot.getLastFrameUrl());
        assertNull(shot.getLastFrameSourceVideoUrl());
        assertEquals("SUCCESS", shot.getRenderStatus());
    }

    @Test
    void testCreateShotWithProps() {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        scene.setDramaId(1L);
        scene.setEpisodeId(5L);
        scene.setSceneNo(1);

        when(sceneMapper.selectById(10L)).thenReturn(scene);
        when(shotMapper.selectList(any())).thenReturn(List.of());

        DramaShotDTO dto = new DramaShotDTO();
        dto.setSceneId(10L);
        dto.setActionDescription("特写桌上的古董座钟");
        dto.setShotType("CLOSE_UP");
        dto.setDuration(new BigDecimal("4.00"));
        com.astra.freyja.dto.drama.PropShotRefDTO propRef = com.astra.freyja.dto.drama.PropShotRefDTO.builder()
                .propId(101L)
                .propName("古董黄铜座钟")
                .propType("KEY_PROP")
                .propPrompt("ornate antique brass desk clock")
                .build();
        dto.setPropRefs(List.of(propRef));

        doAnswer(invocation -> {
            DramaShot s = invocation.getArgument(0);
            s.setId(2000L);
            return 1;
        }).when(shotMapper).insert(any(DramaShot.class));

        Long id = shotService.create(dto);
        assertEquals(2000L, id);

        verify(shotMapper).insert(argThat((DramaShot s) ->
                s != null &&
                s.getPropRefsJson() != null &&
                s.getPropRefsJson().contains("古董黄铜座钟")
        ));
    }

    @Test
    void testGenerateFirstFrameInheritsDramaAspectRatio() {
        DramaShot shot = new DramaShot();
        shot.setId(1000L);
        shot.setDramaId(1L);
        shot.setShotNo(1);
        shot.setPrompt("1man, cyberpunk street, neon lights");
        when(shotMapper.selectById(1000L)).thenReturn(shot);

        Drama drama = new Drama();
        drama.setId(1L);
        drama.setAspectRatio("1920*1080");
        when(dramaMapper.selectById(1L)).thenReturn(drama);

        when(aiImageApiService.generateAndArchiveImage(eq(1000L), eq(1L), anyString(), any()))
                .thenReturn("http://minio/test.png");

        DramaShotFirstFrameDTO req = new DramaShotFirstFrameDTO();
        // 未指定 size
        ComfyRenderTaskVO taskVO = shotService.generateFirstFrame(1000L, req);

        assertNotNull(taskVO);
        assertEquals("http://minio/test.png", taskVO.getOutputUrl());
        assertEquals("1920x1080", req.getSize());
    }

    @Test
    void testCreateShot_DurationExceedsMax_ThrowsBizException() {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        scene.setDramaId(1L);
        scene.setEpisodeId(5L);
        scene.setSceneNo(1);
        when(sceneMapper.selectById(10L)).thenReturn(scene);

        DramaShotDTO dto = new DramaShotDTO();
        dto.setSceneId(10L);
        dto.setDuration(new BigDecimal("15.50"));

        com.astra.freyja.common.BizException ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.astra.freyja.common.BizException.class,
                () -> shotService.create(dto)
        );
        assertTrue(ex.getMessage().contains("镜头时长最长不得超过 15 秒"));
    }

    @Test
    void testUpdateShot_DurationExceedsMax_ThrowsBizException() {
        DramaShot existing = new DramaShot();
        existing.setId(1000L);
        when(shotMapper.selectById(1000L)).thenReturn(existing);

        DramaShotDTO dto = new DramaShotDTO();
        dto.setId(1000L);
        dto.setDuration(new BigDecimal("16.00"));

        com.astra.freyja.common.BizException ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.astra.freyja.common.BizException.class,
                () -> shotService.update(dto)
        );
        assertTrue(ex.getMessage().contains("镜头时长最长不得超过 15 秒"));
    }
}
