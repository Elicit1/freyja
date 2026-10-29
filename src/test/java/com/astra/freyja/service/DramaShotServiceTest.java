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
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.res.CharacterShotRefDTO;
import com.astra.freyja.dto.res.PromptAssembleRequestDTO;
import com.astra.freyja.dto.res.PromptAssembleResultVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.DramaShotVideoTake;
import com.astra.freyja.service.impl.DramaShotServiceImpl;
import com.astra.freyja.util.ShotSeedUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Mock
    private com.astra.freyja.service.video.VideoRenderBatchService videoBatches;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DramaShotServiceImpl shotService;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,101,0", "false,101,1", "true,102,1", "false,102,2", "false,103,3"})
    void insertsShotAtRequestedPositionAndRenumbersOnlyItsGroup(boolean before, long anchorId, int insertionIndex) {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        scene.setEpisodeId(5L);
        scene.setDramaId(1L);
        scene.setSceneNo(1);
        var group = new com.astra.freyja.entity.DramaShotGroup();
        group.setId(20L);
        group.setSceneId(10L);
        var existing = new java.util.ArrayList<DramaShot>();
        for (long id = 101; id <= 103; id++) {
            DramaShot shot = new DramaShot();
            shot.setId(id);
            shot.setSceneId(10L);
            shot.setShotGroupId(20L);
            shot.setShotNo((int) (id - 100) * 10);
            shot.setSortOrder((int) (id - 100) * 5);
            shot.setShotType("CLOSE_UP");
            shot.setVideoUrl("video-" + id);
            existing.add(shot);
        }
        when(sceneMapper.selectById(10L)).thenReturn(scene);
        when(shotMapper.selectById(anchorId)).thenReturn(existing.get((int) (anchorId - 101)));
        when(shotGroupMapper.selectForUpdate(20L)).thenReturn(group);
        when(shotMapper.selectList(any())).thenReturn(existing);
        when(shotMapper.updateOrderInGroup(anyLong(), eq(20L), anyInt())).thenReturn(1);
        doAnswer(invocation -> {
            ((DramaShot) invocation.getArgument(0)).setId(1000L);
            return 1;
        }).when(shotMapper).insert(any(DramaShot.class));
        DramaShotDTO dto = new DramaShotDTO();
        dto.setSceneId(10L);
        dto.setShotNo(99);
        dto.setSortOrder(99);
        dto.setDramaId(999L);
        dto.setEpisodeId(999L);
        if (before) dto.setInsertBeforeShotId(anchorId); else dto.setInsertAfterShotId(anchorId);

        assertEquals(1000L, shotService.create(dto));

        var ids = new java.util.ArrayList<>(List.of(101L, 102L, 103L));
        ids.add(insertionIndex, 1000L);
        for (int i = 0; i < ids.size(); i++) verify(shotMapper).updateOrderInGroup(ids.get(i), 20L, i + 1);
        verify(shotMapper, times(4)).updateOrderInGroup(anyLong(), anyLong(), anyInt());
        verify(shotMapper, never()).updateById(any(DramaShot.class));
        ArgumentCaptor<DramaShot> inserted = ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).insert(inserted.capture());
        assertEquals(insertionIndex + 1, inserted.getValue().getSortOrder());
        assertEquals(insertionIndex + 1, inserted.getValue().getShotNo());
        assertEquals(20L, inserted.getValue().getShotGroupId());
        assertEquals(1L, inserted.getValue().getDramaId());
        assertEquals(5L, inserted.getValue().getEpisodeId());
        verify(episodeService).recalculateEpisodeDuration(5L);
    }

    @Test
    void singleVideoSubmissionAlwaysUsesTheGlobalQueue() {
        DramaShotRenderRequestDTO request = new DramaShotRenderRequestDTO();
        ComfyRenderTaskVO queued = new ComfyRenderTaskVO(); queued.setTaskId("RENDER_123");
        when(videoBatches.enqueueSingle(1000L, request)).thenReturn(queued);
        assertSame(queued, shotService.submitShotRender(1000L, request));
        verify(videoBatches).enqueueSingle(1000L, request);
        verifyNoInteractions(renderAsyncExecutor, aiImageApiService, renderTaskService);
    }
    @Test
    void insertionRejectsTwoAnchorsWithoutCreatingShot() {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        when(sceneMapper.selectById(10L)).thenReturn(scene);
        DramaShotDTO dto = new DramaShotDTO();
        dto.setSceneId(10L);
        dto.setInsertBeforeShotId(101L);
        dto.setInsertAfterShotId(102L);
        assertEquals(400, assertThrows(BizException.class, () -> shotService.create(dto)).getCode());
        verify(shotMapper, never()).insert(any(DramaShot.class));
    }

    @Test
    void insertionRejectsDeletedAnchor() {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        when(sceneMapper.selectById(10L)).thenReturn(scene);
        DramaShotDTO dto = new DramaShotDTO();
        dto.setSceneId(10L);
        dto.setInsertAfterShotId(101L);
        assertEquals(404, assertThrows(BizException.class, () -> shotService.create(dto)).getCode());
        verify(shotMapper, never()).insert(any(DramaShot.class));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"11,20", "10,21"})
    void insertionRejectsAnchorOutsideRequestedSceneOrGroup(long anchorScene, long requestedGroup) {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        DramaShot anchor = new DramaShot();
        anchor.setId(101L);
        anchor.setSceneId(anchorScene);
        anchor.setShotGroupId(20L);
        when(sceneMapper.selectById(10L)).thenReturn(scene);
        when(shotMapper.selectById(101L)).thenReturn(anchor);
        DramaShotDTO dto = new DramaShotDTO();
        dto.setSceneId(10L);
        dto.setShotGroupId(requestedGroup);
        dto.setInsertAfterShotId(101L);
        assertEquals(400, assertThrows(BizException.class, () -> shotService.create(dto)).getCode());
        verify(shotMapper, never()).insert(any(DramaShot.class));
    }

    @Test
    void insertionRejectsAnchorMovedWhileWaitingForGroupLock() {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        var group = new com.astra.freyja.entity.DramaShotGroup();
        group.setId(20L);
        group.setSceneId(10L);
        DramaShot anchor = new DramaShot();
        anchor.setId(101L);
        anchor.setSceneId(10L);
        anchor.setShotGroupId(20L);
        when(sceneMapper.selectById(10L)).thenReturn(scene);
        when(shotMapper.selectById(101L)).thenReturn(anchor);
        when(shotGroupMapper.selectForUpdate(20L)).thenReturn(group);
        when(shotMapper.selectList(any())).thenReturn(List.of());
        DramaShotDTO dto = new DramaShotDTO();
        dto.setSceneId(10L);
        dto.setInsertBeforeShotId(101L);
        assertEquals(409, assertThrows(BizException.class, () -> shotService.create(dto)).getCode());
        verify(shotMapper, never()).insert(any(DramaShot.class));
    }

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
        ArgumentCaptor<DramaShot> inserted = ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).insert(inserted.capture());
        assertTrue(inserted.getValue().getSeed() > 0);
        assertTrue(inserted.getValue().getSeed() <= ShotSeedUtil.MAX_SEED);
        verify(shotMapper, times(1)).insert(argThat((DramaShot shot) ->
                shot != null && "MEDIUM_SHOT".equals(shot.getShotType())
                        && Boolean.FALSE.equals(shot.getShotTypeLocked())
                        && "STATIC".equals(shot.getCameraMovement())
                        && Boolean.TRUE.equals(shot.getCameraMovementLocked())));
        verify(episodeService, times(1)).recalculateEpisodeDuration(5L);
    }

    @Test
    void testCreateShotPersistsManualSeedFromStringWithoutPrecisionLoss() throws Exception {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        scene.setDramaId(1L);
        scene.setEpisodeId(5L);
        scene.setSceneNo(1);
        when(sceneMapper.selectById(10L)).thenReturn(scene);
        when(shotMapper.selectList(any())).thenReturn(List.of());

        DramaShotDTO dto = objectMapper.readValue(
                "{\"sceneId\":\"10\",\"seed\":\"9007199254740991\"}", DramaShotDTO.class);
        dto.setShotGroupId(20L);
        shotService.create(dto);

        ArgumentCaptor<DramaShot> inserted = ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).insert(inserted.capture());
        assertEquals(ShotSeedUtil.MAX_SEED, inserted.getValue().getSeed());
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 123456L, 9007199254740991L})
    void testUpdateShotPersistsManuallyEditedSeed(long seed) {
        DramaShot existing = new DramaShot();
        existing.setId(1000L);
        existing.setSeed(1234L);
        when(shotMapper.selectById(1000L)).thenReturn(existing);

        DramaShotDTO dto = new DramaShotDTO();
        dto.setId(1000L);
        dto.setSeed(seed);
        shotService.update(dto);

        ArgumentCaptor<DramaShot> updated = ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).updateById(updated.capture());
        assertEquals(seed, updated.getValue().getSeed());
    }

    @Test
    void testUpdateWithoutSeedDoesNotOverwriteRefreshedSeed() {
        DramaShot existing = new DramaShot();
        existing.setId(1000L);
        existing.setSeed(5678L);
        when(shotMapper.selectById(1000L)).thenReturn(existing);

        DramaShotDTO dto = new DramaShotDTO();
        dto.setId(1000L);
        dto.setActionDescription("更新剧情描述");
        shotService.update(dto);

        ArgumentCaptor<DramaShot> updated = ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).updateById(updated.capture());
        assertNull(updated.getValue().getSeed(), "未提交种子时不写入该列，保留数据库中的最新值");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, 9007199254740992L, Long.MAX_VALUE})
    void testInvalidManualSeedsAreRejectedBeforePersistence(long seed) {
        DramaShotDTO dto = new DramaShotDTO();
        dto.setSceneId(10L);
        dto.setId(1000L);
        dto.setSeed(seed);

        assertEquals(400, assertThrows(BizException.class, () -> shotService.create(dto)).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> shotService.update(dto)).getCode());
        verifyNoInteractions(shotMapper, sceneMapper, shotGroupMapper);
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
        source.setSeed(1234L);

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
                ((DramaShot) s).getSeed() != null &&
                !((DramaShot) s).getSeed().equals(source.getSeed()) &&
                "INIT".equals(((DramaShot) s).getRenderStatus()) &&
                "S01-01 (副本)".equals(((DramaShot) s).getShotName())
        ));
    }

    @Test
    void testRefreshSeedUpdatesOnlySeed() {
        DramaShot shot = new DramaShot();
        shot.setId(1000L);
        shot.setSeed(1234L);
        when(shotMapper.selectById(1000L)).thenReturn(shot);

        Long refreshed = shotService.refreshSeed(1000L);

        assertNotEquals(1234L, refreshed);
        assertTrue(refreshed > 0 && refreshed <= ShotSeedUtil.MAX_SEED);
        verify(shotMapper).updateById(argThat((DramaShot update) ->
                update.getId().equals(1000L) && update.getSeed().equals(refreshed)
                        && update.getPrompt() == null && update.getRenderStatus() == null));
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
    void testGenerateFirstFramePersistsSubmittedPromptForNextOpen() {
        DramaShot shot = new DramaShot();
        shot.setId(1000L);
        shot.setDramaId(1L);
        shot.setPrompt("old prompt");
        when(shotMapper.selectById(1000L)).thenReturn(shot);
        when(aiImageApiService.generateAndArchiveImage(eq(1000L), eq(1L), eq("edited frame prompt"), any()))
                .thenReturn("http://minio/frame.png");

        DramaShotFirstFrameDTO request = new DramaShotFirstFrameDTO();
        request.setCustomPrompt("edited frame prompt");
        request.setSize("720x1280");
        shotService.generateFirstFrame(1000L, request);

        verify(shotMapper).updateById(argThat((DramaShot saved) ->
                "edited frame prompt".equals(saved.getFirstFramePrompt())
                        && "http://minio/frame.png".equals(saved.getPreviewImageUrl())));
    }

    @Test
    void lastFrameRequestUsesEndPromptAndOnlyUpdatesEndImage() {
        DramaShot shot = new DramaShot();
        shot.setId(1000L);
        shot.setDramaId(1L);
        shot.setFirstFramePrompt("first prompt");
        shot.setEndFramePrompt("last prompt");
        shot.setPreviewImageUrl("http://minio/first.png");
        when(shotMapper.selectById(1000L)).thenReturn(shot);
        when(aiImageApiService.generateAndArchiveImage(eq(1000L), eq(1L), eq("last prompt"), any()))
                .thenReturn("http://minio/last.png");
        DramaShotFirstFrameDTO request = new DramaShotFirstFrameDTO();
        request.setFrameType("LAST_FRAME");
        request.setSize("720x1280");

        shotService.generateFirstFrame(1000L, request);

        assertEquals("http://minio/last.png", shot.getEndFrameImageUrl());
        assertEquals("http://minio/first.png", shot.getPreviewImageUrl());
        assertEquals("first prompt", shot.getFirstFramePrompt());
    }

    @Test
    void invalidFrameRequestsDoNotCreateGenerationTasks() {
        DramaShot shot = new DramaShot();
        shot.setId(1000L);
        when(shotMapper.selectById(1000L)).thenReturn(shot);
        for (String type : List.of("END_FRAME", "KEYFRAME", "UNKNOWN")) {
            DramaShotFirstFrameDTO request = new DramaShotFirstFrameDTO();
            request.setFrameType(type);
            assertEquals(400, assertThrows(BizException.class,
                    () -> shotService.generateFirstFrame(1000L, request)).getCode());
        }
        verifyNoInteractions(aiImageApiService, renderTaskService);
        verify(shotMapper, never()).updateById(any(DramaShot.class));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @ValueSource(strings = {" ", "ACTION_BEAT", "主体", "subject", " SUBJECT ", "IDENTITY"})
    void savingInvalidImageUsageIsRejectedBeforeDatabaseWrites(String usageRole) {
        for (String sourceType : com.astra.freyja.util.ShotImageReferenceValidator.SOURCE_TYPES) {
            List<ShotRefImageDTO> refs = List.of(ShotRefImageDTO.builder()
                    .sourceType(sourceType).usageRole(usageRole).build());
            DramaShotDTO create = new DramaShotDTO();
            create.setSceneId(10L);
            create.setRefImages(refs);
            BizException createError = assertThrows(BizException.class, () -> shotService.create(create));
            assertEquals(400, createError.getCode());
            assertTrue(createError.getMessage().contains("refImages[0].usageRole"));
            DramaShotDTO update = new DramaShotDTO();
            update.setId(1000L);
            update.setRefImages(refs);
            BizException updateError = assertThrows(BizException.class, () -> shotService.update(update));
            assertEquals(400, updateError.getCode());
            assertTrue(updateError.getMessage().contains("refImages[0].usageRole"));
        }
        verifyNoInteractions(shotMapper, sceneMapper);
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

    @Test void firstFrameFreezesSceneImageBeforeDispatchAndRecordsItsSnapshot() throws Exception {
        DramaShot shot = new DramaShot(); shot.setId(1000L); shot.setDramaId(1L); shot.setFirstFramePrompt("office");
        var scene = new com.astra.freyja.entity.ResScene();
        scene.setId(2032095628944588801L); scene.setDramaId(1L); scene.setStatus(1);
        scene.setReferenceImageUrl("http://minio/office-v1.png");
        when(resSceneMapper.selectById(scene.getId())).thenReturn(scene);
        shot.setRefImagesJson(objectMapper.writeValueAsString(List.of(ShotRefImageDTO.builder()
                .id("stable-slot").name("办公室").sourceType("SCENE").sourceId(scene.getId()).usageRole("SCENE")
                .bindingMode("ASSET_LINK").sourceSlot("SCENE_REFERENCE").build())));
        when(shotMapper.selectById(1000L)).thenReturn(shot);
        when(aiImageApiService.generateAndArchiveImage(eq(1000L),eq(1L),eq("office"),any())).thenAnswer(call -> {
            scene.setReferenceImageUrl("http://minio/office-v2.png");
            DramaShotFirstFrameDTO frozen=call.getArgument(3);
            assertEquals(List.of("http://minio/office-v1.png"),frozen.getReferenceImageUrls());
            assertEquals("SNAPSHOT",frozen.getRefImages().getFirst().getBindingMode());
            return "http://minio/result.png";
        });
        DramaShotFirstFrameDTO request=new DramaShotFirstFrameDTO(); request.setSize("1024x1024");
        shotService.generateFirstFrame(1000L,request);
        var task=ArgumentCaptor.forClass(com.astra.freyja.dto.render.RenderTaskVO.class);
        verify(renderTaskService).createTask(task.capture());
        assertTrue(task.getValue().getRequestSnapshotJson().contains("office-v1.png"));
        assertTrue(task.getValue().getRequestSnapshotJson().contains("\"sourceId\":\"2032095628944588801\""));
        assertTrue(shot.getRefImagesJson().contains("ASSET_LINK"));
    }

    @Test void missingLinkedSceneImageDoesNotCreateAFrameTask() throws Exception {
        DramaShot shot=new DramaShot(); shot.setId(1000L);shot.setDramaId(1L);shot.setFirstFramePrompt("office");
        var scene=new com.astra.freyja.entity.ResScene();scene.setId(500L);scene.setDramaId(1L);scene.setStatus(1);
        when(resSceneMapper.selectById(500L)).thenReturn(scene);when(shotMapper.selectById(1000L)).thenReturn(shot);
        shot.setRefImagesJson(objectMapper.writeValueAsString(List.of(ShotRefImageDTO.builder().id("scene-slot")
                .sourceType("SCENE").sourceId(500L).usageRole("SCENE").bindingMode("ASSET_LINK").sourceSlot("SCENE_REFERENCE").build())));
        assertTrue(assertThrows(BizException.class,()->shotService.generateFirstFrame(1000L,new DramaShotFirstFrameDTO()))
                .getMessage().contains("Picture 1"));
        verifyNoInteractions(aiImageApiService,renderTaskService);
    }
}
