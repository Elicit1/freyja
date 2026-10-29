package com.astra.freyja.service;

import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.render.RemoteCancelResultVO;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.video.*;
import com.astra.freyja.service.video.VideoBatchModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQL/transactions cover queue state, phase ordering, cancellation and duplicate submission. */
class VideoRenderBatchTest {
    @org.junit.jupiter.api.BeforeAll static void mapperMetadata() {
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant=new org.apache.ibatis.builder.MapperBuilderAssistant(config,"video-batch-test");
        for(Class<?> type:List.of(RenderTask.class,AiModel.class,DramaShot.class,DramaEpisode.class,DramaScene.class,DramaShotGroup.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,type);
    }
    DramaShotMapper shots;AiImageApiService api;RenderTaskService rendersService;RenderTaskMapper renders;
    VideoBatchStore store;VideoBatchPreparation preparation;VideoRenderBatchService service;VideoBatchFrameService frames;
    ApplicationEventPublisher events;
    ResSceneMapper sceneAssets;
    final Map<Long,DramaShot> shotData=new LinkedHashMap<>();final Map<String,RenderTask> renderData=new HashMap<>();
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();JdbcTemplate jdbc;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        String sql=new ClassPathResource("sql/video-render-batch.sql").getContentAsString(StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        for(String statement:sql.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        shots=mock(DramaShotMapper.class);var dramas=mock(DramaMapper.class);var episodes=mock(DramaEpisodeMapper.class);var scenes=mock(DramaSceneMapper.class);var groups=mock(DramaShotGroupMapper.class);
        var models=mock(AiModelMapper.class);var providers=mock(AiProviderMapper.class);var keyframes=mock(ResKeyframeMapper.class);renders=mock(RenderTaskMapper.class);
        api=mock(AiImageApiService.class);rendersService=mock(RenderTaskService.class);frames=mock(VideoBatchFrameService.class);var takes=mock(ShotVideoTakeService.class);
        store=new VideoBatchStore(jdbc);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        preparation=new VideoBatchPreparation(mock(ShotVideoReferenceService.class),mock(ShotVideoCapabilityService.class),shots,dramas,episodes,scenes,groups,models,providers,keyframes,(sceneAssets=mock(ResSceneMapper.class)),renders,store,json);
        events=mock(ApplicationEventPublisher.class);
        service=new VideoRenderBatchService(store,preparation,frames,shots,renders,models,api,rendersService,takes,tx,json,events);
        when(dramas.selectById(1L)).thenReturn(new Drama());
        DramaEpisode ep=new DramaEpisode();ep.setId(10L);ep.setDramaId(1L);ep.setTitle("第一集");when(episodes.selectList(any())).thenReturn(List.of(ep));
        DramaScene sc=new DramaScene();sc.setId(20L);sc.setDramaId(1L);sc.setName("场景");when(scenes.selectList(any())).thenReturn(List.of(sc));
        DramaShotGroup gr=new DramaShotGroup();gr.setId(30L);gr.setDramaId(1L);gr.setName("镜头组");when(groups.selectList(any())).thenReturn(List.of(gr));
        when(shots.selectActiveByDramaId(1L)).thenAnswer(i->new ArrayList<>(shotData.values()));when(shots.selectById(any())).thenAnswer(i->shotData.get(i.getArgument(0)));when(shots.lockById(anyLong())).thenAnswer(i->shotData.get(i.getArgument(0)));
        AiProvider p=new AiProvider();p.setId(100L);p.setStatus(1);p.setBaseUrl("http://gateway/v1");when(providers.selectById(100L)).thenReturn(p);
        AiModel ref=model(101L,"ref", "TXT2VIDEO_REF"),fl=model(102L,"fl","TXT2VIDEO_FIRST_LAST");when(models.selectById(101L)).thenReturn(ref);when(models.selectById(102L)).thenReturn(fl);
        when(models.selectOne(any())).thenAnswer(i->{String sqlSegment=((com.baomidou.mybatisplus.core.conditions.Wrapper<?>)i.getArgument(0)).getSqlSegment();
            var values=((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)i.getArgument(0)).getParamNameValuePairs().values();return values.contains("ref")?ref:fl;});
        when(renders.selectCount(any())).thenReturn(0L);
        when(rendersService.createTask(any())).thenAnswer(i->{var vo=(com.astra.freyja.dto.render.RenderTaskVO)i.getArgument(0);RenderTask r=new RenderTask();r.setTaskId(vo.getTaskId());r.setStatus("QUEUED");renderData.put(r.getTaskId(),r);return vo;});
        when(renders.selectOne(any())).thenAnswer(i->{var wrapper=(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)i.getArgument(0);wrapper.getSqlSegment();return wrapper.getParamNameValuePairs().values().stream().filter(String.class::isInstance).map(v->renderData.get(String.valueOf(v))).filter(Objects::nonNull).findFirst().orElse(null);});
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(i->VideoGenerationResultVO.builder().videoUrl("http://minio/video/"+i.getArgument(0)+".mp4").build());
        when(frames.extract(anyLong(),anyString(),any(),anyString())).thenAnswer(i->"http://minio/frame/"+i.getArgument(0)+"/"+i.getArgument(3)+".jpg");
    }
    private AiModel model(long id,String code,String type){AiModel m=new AiModel();m.setId(id);m.setProviderId(100L);m.setStatus(1);m.setModelCode(code);m.setModelType(type);return m;}
    private DramaShot shot(long id,String mode,int order){DramaShot s=new DramaShot();s.setId(id);s.setDramaId(1L);s.setEpisodeId(10L);s.setSceneId(20L);s.setShotGroupId(30L);s.setShotNo(order);s.setSortOrder(order);s.setShotName("镜头"+order);s.setGenerationMode(mode);s.setSeed(123L);s.setDuration(BigDecimal.valueOf(3));s.setPrompt("A saved cinematic prompt");s.setVideoPrompt("A saved motion prompt");shotData.put(id,s);return s;}
    private VideoRenderBatchRequest request(){var req=new VideoRenderBatchRequest();req.setDramaId(1L);req.setClientRequestId(UUID.randomUUID().toString());req.setShotIds(new ArrayList<>(shotData.keySet()));req.setAspectRatio("16:9");req.setExistingVideoPolicy("CREATE_NEW_TAKE");
        var r=new VideoRenderBatchRequest.ModeConfig();r.setProviderId(100L);r.setModelId(101L);var f=new VideoRenderBatchRequest.ModeConfig();f.setProviderId(100L);f.setModelId(102L);f.setAutoUseNextVideoHeadAsLastFrame(true);req.setModeConfigs(Map.of(VideoBatchPreparation.REF,r,VideoBatchPreparation.FL,f));return req;}
    private Batch create(VideoRenderBatchRequest req){req.setExpectedPreviewHash(service.preview(req).getPreviewHash());return service.create(req);}

    @Test void explicitlySelectedSizeIsSharedByBothPhasesAndFrozenAtSubmission() {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.FL,2);
        var req=request();req.setSize("1344x768");
        Preview preview=service.preview(req);
        assertEquals(List.of("1344x768","1344x768"),preview.getItems().stream().map(Candidate::getSize).toList());
        Batch batch=create(req);assertEquals("1344x768",batch.getOptions().getSize());
        req.setSize("1024x576");
        assertThrows(com.astra.freyja.common.BizException.class,()->service.create(req));
        service.runNext();service.runNext();
        var calls=mockingDetails(api).getInvocations().stream().filter(i->i.getMethod().getName().equals("generateAndArchiveVideo")).toList();
        assertEquals(2,calls.size());
        for(var call:calls){DramaShotRenderRequestDTO dto=call.getArgument(3);assertEquals("1344x768",dto.getSize());}
        assertEquals("SUCCESS",service.detail(batch.getId()).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings={"1280x720","0x544","bad","999999999999x768",""})
    void invalidSharedSizeIsRejectedBeforeCreatingAnyTasks(String size) {
        shot(41,VideoBatchPreparation.REF,1);var req=request();req.setSize(size);
        var error=assertThrows(com.astra.freyja.common.BizException.class,()->service.preview(req));
        assertEquals(400,error.getCode());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM video_render_batch",Integer.class));
        verifyNoInteractions(api,rendersService);
    }

    @Test void sizeChangeAfterPreviewRequiresAnewPreviewWithoutPartialWrites() {
        shot(41,VideoBatchPreparation.REF,1);var req=request();req.setSize("1024x576");
        req.setExpectedPreviewHash(service.preview(req).getPreviewHash());req.setSize("1344x768");
        var error=assertThrows(com.astra.freyja.common.BizException.class,()->service.create(req));assertEquals(409,error.getCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM video_render_batch",Integer.class));assertFalse(store.occupied(41L));
    }

    @Test void originalSnapshotRetryCannotChangeTheSelectedSize() {
        shot(41,VideoBatchPreparation.REF,1);var original=request();original.setSize("1344x768");Batch batch=create(original);
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(i->{VideoExecutionState.phase("REJECTED");throw new com.astra.freyja.common.BizException("Request rejected");});
        service.runNext();assertEquals("FAILED",service.detail(batch.getId()).getStatus());
        var retry=request();retry.setRetryOfBatchId(batch.getId());retry.setInputSource("ORIGINAL_SNAPSHOT");retry.setSize("1024x576");
        Preview blocked=service.preview(retry);assertEquals(0,blocked.getAccepted());assertTrue(blocked.getItems().getFirst().getReason().contains("尺寸"));
        retry.setSize("1344x768");Preview allowed=service.preview(retry);assertEquals(1,allowed.getAccepted());assertEquals("1344x768",allowed.getItems().getFirst().getSize());
    }

    @Test void singleVideoWaitsBehindEveryShotInTheEarlierBatch() {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.REF,2);shot(43,VideoBatchPreparation.REF,3);
        var batchRequest=request();batchRequest.setShotIds(List.of(41L,42L));create(batchRequest);
        AiProvider provider=new AiProvider();provider.setId(100L);
        when(api.resolveProvider(100L)).thenReturn(provider);
        when(api.resolveVideoModelCode(any(),eq(100L),eq(VideoBatchPreparation.REF))).thenReturn("ref");
        var singleRequest=new DramaShotRenderRequestDTO();singleRequest.setProviderId(100L);singleRequest.setWorkflowTemplateId("ref");singleRequest.setSize("960x544");
        var queued=service.enqueueSingle(43L,singleRequest);
        assertEquals(com.astra.freyja.entity.enums.ComfyTaskStatus.PENDING,queued.getStatus());
        verify(api,never()).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        for(int n=0;n<3;n++)assertTrue(service.runNext());
        assertFalse(service.runNext());
        var order=mockingDetails(api).getInvocations().stream().filter(i->i.getMethod().getName().equals("generateAndArchiveVideo")).map(i->(Long)i.getArgument(0)).toList();
        assertEquals(List.of(41L,42L,43L),order);
    }

    @Test void phasesAreSerialAndNeighborFramesKeepNarrativeRoles() {
        shot(41,VideoBatchPreparation.FL,1);shot(42,VideoBatchPreparation.REF,2);shot(43,VideoBatchPreparation.FL,3);shot(44,VideoBatchPreparation.FL,4);
        var b=create(request());assertEquals(List.of(42L,41L,43L,44L),b.getItems().stream().map(Item::getShotId).toList());
        for(int n=0;n<4;n++)service.runNext();
        var order=mockingDetails(api).getInvocations().stream().filter(i->i.getMethod().getName().equals("generateAndArchiveVideo")).map(i->(Long)i.getArgument(0)).toList();assertEquals(List.of(42L,41L,43L,44L),order);
        var calls=mockingDetails(api).getInvocations().stream().filter(i->i.getMethod().getName().equals("generateAndArchiveVideo")).toList();
        DramaShot first=calls.get(1).getArgument(2);assertNull(first.getPreviewImageUrl());assertEquals("http://minio/frame/42/FIRST.jpg",first.getEndFrameImageUrl());
        DramaShot third=calls.get(2).getArgument(2);assertEquals("http://minio/frame/42/LAST.jpg",third.getPreviewImageUrl());assertNull(third.getEndFrameImageUrl());
        assertEquals("SUCCESS",service.detail(b.getId()).getStatus());assertEquals(100,service.detail(b.getId()).getProgress());
    }
    @Test void inputSnapshotsAndSwitchesSurviveLaterEditing() {
        var s=shot(41,VideoBatchPreparation.FL,1);s.setPreviewImageUrl("http://manual/first.png");s.setEndFrameImageUrl("http://manual/last.png");var req=request();req.getModeConfigs().get(VideoBatchPreparation.FL).setAutoUsePreviousVideoTailAsFirstFrame(false);req.getModeConfigs().get(VideoBatchPreparation.FL).setAutoUseNextVideoHeadAsLastFrame(false);create(req);
        s.setVideoPrompt("Changed while queued");s.setSeed(999L);s.setPreviewImageUrl("http://later/image.png");service.runNext();
        var invocation=mockingDetails(api).getInvocations().stream().filter(i->i.getMethod().getName().equals("generateAndArchiveVideo")).findFirst().orElseThrow();
        DramaShot snapshot=invocation.getArgument(2);DramaShotRenderRequestDTO dto=invocation.getArgument(3);assertEquals("http://manual/first.png",snapshot.getPreviewImageUrl());assertEquals("http://manual/last.png",snapshot.getEndFrameImageUrl());assertEquals("A saved motion prompt",dto.getPrompt());assertEquals(123L,dto.getSeed());
        verifyNoInteractions(frames);
    }
    @Test void duplicateRequestReturnsSameBatchAndStalePreviewRollsBack() {
        var s=shot(41,VideoBatchPreparation.REF,1);var req=request();Batch b=create(req);assertEquals(b.getId(),service.create(req).getId());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM video_render_batch",Integer.class));
        req.setAspectRatio("9:16");assertThrows(com.astra.freyja.common.BizException.class,()->service.create(req));
        shot(42,VideoBatchPreparation.FL,2);var next=request();next.setShotIds(List.of(42L));next.setExpectedPreviewHash(service.preview(next).getPreviewHash());shotData.get(42L).setVideoPrompt("edited");assertThrows(com.astra.freyja.common.BizException.class,()->service.create(next));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM video_render_batch",Integer.class));assertFalse(store.occupied(42L));
    }
    @Test void cancellingPreservesSuccessAndNeverDispatchesQueuedItems() {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.FL,2);shot(43,VideoBatchPreparation.FL,3);var b=create(request());service.runNext();
        service.cancel(b.getId());service.runNext();Batch cancelled=service.detail(b.getId());assertEquals("CANCELLED",cancelled.getStatus());assertEquals(1,cancelled.getSucceeded());assertEquals(2,cancelled.getCancelled());verify(api,times(1)).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
    }
    @Test void runningCancellationReleasesQueueEvenWhenRemoteStopIsUnconfirmed() throws Exception {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.FL,2);var b=create(request());CountDownLatch entered=new CountDownLatch(1);
        when(api.generateAndArchiveVideo(eq(41L),anyLong(),any(),any())).thenAnswer(i->{entered.countDown();new CountDownLatch(1).await();return null;});
        when(api.cancelRemoteTask(anyLong(),anyString(),anyLong())).thenReturn(RemoteCancelResultVO.builder().success(false).detailStatus("NOT_FOUND").build());
        try(var executor=Executors.newSingleThreadExecutor()){Future<?> running=executor.submit(service::runNext);assertTrue(entered.await(3,TimeUnit.SECONDS));var result=service.cancel(b.getId());running.get(3,TimeUnit.SECONDS);assertEquals("UNCONFIRMED",result.getUpstreamStatus());}
        assertEquals(2,service.detail(b.getId()).getCancelled());assertNull(store.currentTask());assertFalse(store.occupied(41L));assertFalse(store.occupied(42L));
        shot(43,VideoBatchPreparation.REF,3);var next=request();next.setShotIds(List.of(43L));Batch other=create(next);
        assertTrue(service.runNext());assertEquals("SUCCESS",service.detail(other.getId()).getStatus());
        verify(api,never()).generateAndArchiveVideo(eq(42L),anyLong(),any(),any());
    }
    @Test void uncertainRemoteFailureIsFailedAndLaterShotsContinueWithoutReview() {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.FL,2);var b=create(request());
        when(api.generateAndArchiveVideo(eq(41L),anyLong(),any(),any())).thenThrow(new RuntimeException("connection lost"));
        service.runNext();assertNull(store.currentTask());assertFalse(store.occupied(41L));
        assertEquals("FAILED",store.items(b.getId()).getFirst().getStatus());assertEquals("UNCONFIRMED",store.items(b.getId()).getFirst().getUpstreamStatus());
        service.runNext();Batch result=service.detail(b.getId());assertEquals("PARTIAL_FAILED",result.getStatus());assertEquals(100,result.getProgress());
        verify(api,times(2)).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
    }

    @ParameterizedTest @ValueSource(strings={"NEEDS_REVIEW","RENDERING","CANCELLED"})
    void oldInterruptedSlotsAreReleasedAutomatically(String status) {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.REF,2);Batch batch=create(request());
        Item old=batch.getItems().getFirst();store.claim(old.getTaskId());store.status(old.getTaskId(),status,"old worker stopped",null,"UNCONFIRMED");
        assertTrue(service.runNext());assertNull(store.currentTask());assertFalse(store.occupied(41L));
        Batch result=service.detail(batch.getId());assertEquals("CANCELLED".equals(status)?"CANCELLED":"FAILED",result.getItems().getFirst().getStatus());
        assertEquals("SUCCESS",result.getItems().get(1).getStatus());assertEquals(100,result.getProgress());
        verify(api,never()).generateAndArchiveVideo(eq(41L),anyLong(),any(),any());
    }

    @Test void archiveRecoveryNeverCallsVideoGenerationAgain() {
        shot(41,VideoBatchPreparation.REF,1);var b=create(request());
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(i->{VideoExecutionState.completed("http://gateway/result.mp4");throw new com.astra.freyja.common.BizException("MinIO unavailable");});
        service.runNext();Batch failed=service.detail(b.getId());Item i=failed.getItems().getFirst();
        assertEquals("FAILED",i.getStatus());assertTrue(i.isArchiveRecoverable());assertNull(store.currentTask());
        when(api.archiveExistingVideo(41L,1L,"http://gateway/result.mp4")).thenReturn(VideoGenerationResultVO.builder().videoUrl("http://minio/recovered.mp4").build());
        service.retryArchive(b.getId(),i.getTaskId());service.runNext();assertEquals("SUCCESS",service.detail(b.getId()).getStatus());
        verify(api,times(1)).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());verify(api).archiveExistingVideo(41L,1L,"http://gateway/result.mp4");
    }

    @Test void transferStagesAreDurableAndPublishedAfterCommit() {
        shot(41,VideoBatchPreparation.REF,1);Batch batch=create(request());String task=batch.getItems().getFirst().getTaskId();
        List<String> pushed=new ArrayList<>();
        doAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            pushed.add(service.summary(batch.getId()).getCurrentStage());return null;
        }).when(events).publishEvent(any(com.astra.freyja.event.TaskStatusChangedEvent.class));
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(call->{
            int before=service.detail(batch.getId()).getRevision();
            VideoExecutionState.completed("http://gateway/original.mp4");
            VideoExecutionState.progress("DOWNLOADING","渲染已完成，正在下载视频");
            Item downloading=store.task(task);assertEquals("DOWNLOADING",downloading.getExecutionStage());assertNotNull(downloading.getStageUpdatedAt());
            assertEquals("http://gateway/original.mp4",downloading.getRecoveryUrl());
            assertTrue(service.summary(batch.getId()).getCurrentStage().contains("正在下载视频"));
            assertTrue(service.detail(batch.getId()).getRevision()>before);
            VideoExecutionState.progress("ARCHIVING","正在归档视频至 MinIO");
            assertEquals("ARCHIVING",store.task(task).getExecutionStage());
            assertTrue(service.summary(batch.getId()).getCurrentStage().contains("归档"));
            return VideoGenerationResultVO.builder().videoUrl("http://minio/result.mp4").build();
        });
        service.runNext();assertEquals("SUCCESS",service.detail(batch.getId()).getStatus());
        assertTrue(pushed.stream().anyMatch(stage->stage.contains("正在下载视频")));
        assertTrue(pushed.stream().anyMatch(stage->stage.contains("正在归档视频")));
    }

    @Test void interruptedDownloadRecoversOriginalUrlWithoutGeneratingAgain() {
        shot(41,VideoBatchPreparation.REF,1);Batch batch=create(request());String task=batch.getItems().getFirst().getTaskId();
        store.claim(task);store.status(task,"RENDERING",null,null,null);store.recovery(task,"ARCHIVE","http://gateway/original.mp4");
        store.progress(task,"DOWNLOADING","正在下载视频");
        when(api.archiveExistingVideo(41L,1L,"http://gateway/original.mp4")).thenReturn(VideoGenerationResultVO.builder().videoUrl("http://minio/recovered.mp4").build());
        service.runNext();assertEquals("SUCCESS",store.task(task).getStatus());assertNull(store.currentTask());
        verify(api,never()).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        verify(api).archiveExistingVideo(41L,1L,"http://gateway/original.mp4");
    }

    @Test void interruptedRenderQueriesOriginalTaskBeforeResuming() {
        shot(41,VideoBatchPreparation.REF,1);Batch batch=create(request());String task=batch.getItems().getFirst().getTaskId();
        store.claim(task);store.status(task,"RENDERING",null,null,null);
        when(api.queryVideoTask(100L,task)).thenReturn(new com.astra.freyja.dto.render.RemoteVideoTaskVO("SUCCESS","http://gateway/original.mp4","original-prompt","default"));
        when(api.archiveExistingVideo(41L,1L,"http://gateway/original.mp4")).thenReturn(VideoGenerationResultVO.builder().videoUrl("http://minio/recovered.mp4").build());
        service.runNext();assertEquals("SUCCESS",store.task(task).getStatus());
        verify(api,never()).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        verify(api,times(1)).queryVideoTask(100L,task);
    }

    @Test void recoveringCompletedUpstreamResultIsIdempotentAndOnlyArchives() {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.FL,2);Batch batch=create(request());
        when(api.generateAndArchiveVideo(eq(41L),anyLong(),any(),any())).thenThrow(new RuntimeException("connection lost"));
        service.runNext();String task=store.items(batch.getId()).getFirst().getTaskId();
        when(api.queryVideoTask(anyLong(),eq(task))).thenReturn(new com.astra.freyja.dto.render.RemoteVideoTaskVO("SUCCESS","http://gateway/task/output","prompt-1","node-1"));
        when(api.archiveExistingVideo(41L,1L,"http://gateway/task/output")).thenReturn(VideoGenerationResultVO.builder().videoUrl("http://minio/recovered.mp4").build());
        assertEquals("QUEUED",service.recoverResult(batch.getId(),task).status());
        assertTrue(store.occupied(41L));assertNull(store.currentTask());
        assertEquals("QUEUED",service.recoverResult(batch.getId(),task).status());
        service.runNext();
        assertEquals("SUCCESS",store.task(task).getStatus());assertFalse(store.occupied(41L));
        assertEquals("SUCCESS",service.recoverResult(batch.getId(),task).status());
        service.runNext();assertEquals("SUCCESS",service.detail(batch.getId()).getStatus());
        verify(api,times(1)).queryVideoTask(anyLong(),eq(task));
        verify(api,times(1)).generateAndArchiveVideo(eq(41L),anyLong(),any(),any());
        verify(api,times(1)).archiveExistingVideo(41L,1L,"http://gateway/task/output");
    }

    @ParameterizedTest @ValueSource(strings={"UNKNOWN","RUNNING","QUEUED"})
    void queryingUnfinishedRemoteResultDoesNotBlockQueue(String state) {
        shot(41,VideoBatchPreparation.REF,1);Batch batch=create(request());
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenThrow(new RuntimeException("connection lost"));
        service.runNext();String task=store.items(batch.getId()).getFirst().getTaskId();
        when(api.queryVideoTask(anyLong(),eq(task))).thenReturn(new com.astra.freyja.dto.render.RemoteVideoTaskVO(state,null,"prompt-1","node-1"));
        assertEquals(state,service.recoverResult(batch.getId(),task).status());
        assertNull(store.currentTask());assertFalse(store.occupied(41L));assertEquals("FAILED",store.task(task).getStatus());
        verify(api,never()).archiveExistingVideo(anyLong(),anyLong(),anyString());
    }

    @Test void cancellationDuringUpstreamQueryPreventsRecoveryAndResumption() {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.REF,2);Batch batch=create(request());
        when(api.generateAndArchiveVideo(eq(41L),anyLong(),any(),any())).thenThrow(new RuntimeException("connection lost"));
        service.runNext();String task=store.items(batch.getId()).getFirst().getTaskId();
        when(api.queryVideoTask(anyLong(),eq(task))).thenAnswer(i->{
            service.cancel(batch.getId());
            return new com.astra.freyja.dto.render.RemoteVideoTaskVO("SUCCESS","http://gateway/output","prompt-1","node-1");
        });
        assertThrows(com.astra.freyja.common.BizException.class,()->service.recoverResult(batch.getId(),task));
        assertEquals("CANCELLED",store.task(task).getStatus());
        assertEquals("CANCELLED",service.detail(batch.getId()).getStatus());
        verify(api,never()).archiveExistingVideo(anyLong(),anyLong(),anyString());
    }

    @Test void confirmedUpstreamFailureReleasesQueueWithoutRegeneratingFailedShot() {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.FL,2);Batch batch=create(request());
        when(api.generateAndArchiveVideo(eq(41L),anyLong(),any(),any())).thenThrow(new RuntimeException("connection lost"));
        service.runNext();String task=store.items(batch.getId()).getFirst().getTaskId();
        when(api.queryVideoTask(anyLong(),eq(task))).thenReturn(new com.astra.freyja.dto.render.RemoteVideoTaskVO("FAILED",null,"prompt-1","node-1"));
        assertEquals("FAILED",service.recoverResult(batch.getId(),task).status());
        assertEquals("CONFIRMED",store.task(task).getUpstreamStatus());
        assertNull(store.currentTask());assertFalse(store.occupied(41L));service.runNext();
        assertEquals("PARTIAL_FAILED",service.detail(batch.getId()).getStatus());
        verify(api,times(1)).generateAndArchiveVideo(eq(41L),anyLong(),any(),any());
    }

    @Test void recoveringOldResultCannotReplaceANewerShotTask() {
        DramaShot shot=shot(41,VideoBatchPreparation.REF,1);Batch batch=create(request());
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenThrow(new RuntimeException("connection lost"));
        service.runNext();String oldTask=store.items(batch.getId()).getFirst().getTaskId();
        when(api.queryVideoTask(anyLong(),eq(oldTask))).thenReturn(new com.astra.freyja.dto.render.RemoteVideoTaskVO("SUCCESS","http://gateway/old.mp4","prompt-1","node-1"));
        Batch newer=create(request());String newTask=newer.getItems().getFirst().getTaskId();shot.setLatestTaskId(newTask);
        assertThrows(com.astra.freyja.common.BizException.class,()->service.recoverResult(batch.getId(),oldTask));
        assertEquals("FAILED",store.task(oldTask).getStatus());assertTrue(store.occupied(41L));
        store.status(newTask,"SUCCESS",null,"http://minio/new.mp4","CONFIRMED");store.releaseShot(newTask);
        assertThrows(com.astra.freyja.common.BizException.class,()->service.recoverResult(batch.getId(),oldTask));
        verify(api,never()).archiveExistingVideo(anyLong(),anyLong(),anyString());
    }

    @Test void invalidKeyframeBindingIsBlockedWithoutCreatingTasks() {
        shot(41,VideoBatchPreparation.FL,1).setRefImagesJson("[{\"sourceType\":\"KEYFRAME\",\"usageRole\":\"END_FRAME\"}]");
        Preview p=service.preview(request());assertEquals(0,p.getAccepted());assertEquals("BLOCKED",p.getItems().getFirst().getStatus());
        verifyNoInteractions(rendersService);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM video_render_batch_item",Integer.class));
    }

    @Test void publicOptionsSerializeSnowflakeIdsAsStringsWithoutRawNumericJson() {
        var req=new VideoRenderBatchRequest();req.setDramaId(2032095628944588801L);var cfg=new VideoRenderBatchRequest.ModeConfig();cfg.setModelId(2032095628944588802L);req.setModeConfigs(Map.of(VideoBatchPreparation.REF,cfg));
        var b=new Batch();b.setId(2032095628944588803L);b.setOptions(req);b.setOptionsJson("{\"numericId\":2032095628944588801}");
        var builder=tools.jackson.databind.json.JsonMapper.builder();new com.astra.freyja.config.JacksonConfig().longToStringCustomizer().customize(builder);String encoded=builder.build().writeValueAsString(b);
        assertTrue(encoded.contains("\"modelId\":\"2032095628944588802\""));assertTrue(encoded.contains("\"dramaId\":\"2032095628944588801\""));assertFalse(encoded.contains("optionsJson"));
    }

    @Test void sixShotSimulationReportsEveryStageAndKeepsModeModelsAndOneRatio() {
        shot(41,VideoBatchPreparation.FL,1);shot(42,VideoBatchPreparation.REF,2);
        shot(43,VideoBatchPreparation.FL,3);shot(44,VideoBatchPreparation.FL,4);
        shot(45,VideoBatchPreparation.REF,5);shot(46,VideoBatchPreparation.FL,6);
        Batch batch=create(request());
        assertEquals(List.of(42L,45L,41L,43L,44L,46L),batch.getItems().stream().map(Item::getShotId).toList());
        List<Integer> progress=new ArrayList<>(List.of(service.summary(batch.getId()).getProgress()));
        int lastRevision=batch.getRevision();
        for(int step=1;step<=6;step++) {
            service.runNext();Batch current=service.detail(batch.getId());
            assertEquals(step,current.getProcessed());assertEquals(Math.min(step,2),current.getRefProcessed());
            assertEquals(Math.max(0,step-2),current.getFlProcessed());
            assertTrue(current.getRevision()>lastRevision);lastRevision=current.getRevision();
            var summary=service.summary(batch.getId());
            assertEquals("VIDEO_RENDER_BATCH",summary.getSourceType());
            assertEquals(current.getProgress(),summary.getProgress());progress.add(summary.getProgress());
            if(step==2)assertEquals("FL2VA",current.getCurrentPhase());
        }
        assertEquals(List.of(0,16,33,50,66,83,100),progress);
        var calls=mockingDetails(api).getInvocations().stream().filter(i->i.getMethod().getName().equals("generateAndArchiveVideo")).toList();
        for(int index=0;index<calls.size();index++) {
            DramaShotRenderRequestDTO dto=calls.get(index).getArgument(3);
            assertEquals(index<2?"ref":"fl",dto.getWorkflowTemplateId());assertEquals("960x544",dto.getSize());
        }
        assertEquals("SUCCESS",service.summary(batch.getId()).getStatus());
    }

    @ParameterizedTest
    @CsvSource({"false,false","false,true","true,false","true,true"})
    void twoFrameSwitchesAreIndependentWithoutConfiguredImages(boolean previous,boolean next) {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.FL,2);shot(43,VideoBatchPreparation.REF,3);
        var req=request();var cfg=req.getModeConfigs().get(VideoBatchPreparation.FL);
        cfg.setAutoUsePreviousVideoTailAsFirstFrame(previous);cfg.setAutoUseNextVideoHeadAsLastFrame(next);
        create(req);for(int n=0;n<3;n++)service.runNext();
        var call=mockingDetails(api).getInvocations().stream().filter(i->i.getMethod().getName().equals("generateAndArchiveVideo")&&Long.valueOf(42).equals(i.getArgument(0))).findFirst().orElseThrow();
        DramaShot current=call.getArgument(2);
        assertEquals(previous?"http://minio/frame/41/LAST.jpg":null,current.getPreviewImageUrl());
        assertEquals(next?"http://minio/frame/43/FIRST.jpg":null,current.getEndFrameImageUrl());
        verify(frames,times((previous?1:0)+(next?1:0))).extract(anyLong(),anyString(),any(),anyString());
    }

    @Test void overlappingConsumerAttemptsAndDifferentBatchesNeverSubmitTwoVideos() throws Exception {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.REF,2);
        var first=request();first.setShotIds(List.of(41L));Batch a=create(first);
        var second=request();second.setShotIds(List.of(42L));Batch b=create(second);
        AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(i->{
            int concurrent=active.incrementAndGet();maximum.accumulateAndGet(concurrent,Math::max);
            try {if(Long.valueOf(41).equals(i.getArgument(0))){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}
                return VideoGenerationResultVO.builder().videoUrl("http://minio/video/"+i.getArgument(0)+".mp4").build();
            }finally{active.decrementAndGet();}
        });
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<?> running=executor.submit(service::runNext);
            try {assertTrue(entered.await(3,TimeUnit.SECONDS));service.runNext();
                verify(api,times(1)).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
                assertEquals("QUEUED",service.detail(b.getId()).getItems().getFirst().getStatus());
            }finally{release.countDown();}
            running.get(3,TimeUnit.SECONDS);
        }
        service.runNext();assertEquals(1,maximum.get());assertEquals(0,active.get());
        assertEquals("SUCCESS",service.detail(a.getId()).getStatus());assertEquals("SUCCESS",service.detail(b.getId()).getStatus());
    }

    @Test void confirmedWholeBatchCancellationPreservesFinishedVideoInterruptsCurrentAndAllowsNextBatch() throws Exception {
        shot(41,VideoBatchPreparation.REF,1);shot(42,VideoBatchPreparation.REF,2);
        shot(43,VideoBatchPreparation.FL,3);shot(44,VideoBatchPreparation.REF,4);
        var req=request();req.setShotIds(List.of(41L,42L,43L));Batch a=create(req);
        var other=request();other.setShotIds(List.of(44L));Batch b=create(other);
        service.runNext();String savedVideo=service.detail(a.getId()).getItems().getFirst().getOutputUrl();
        CountDownLatch entered=new CountDownLatch(1);
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(i->{
            if(Long.valueOf(42).equals(i.getArgument(0))){entered.countDown();new CountDownLatch(1).await();}
            return VideoGenerationResultVO.builder().videoUrl("http://minio/video/"+i.getArgument(0)+".mp4").build();
        });
        when(api.cancelRemoteTask(anyLong(),anyString(),anyLong())).thenReturn(RemoteCancelResultVO.builder().success(true).detailStatus("CANCELLED").build());
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<?> running=executor.submit(service::runNext);assertTrue(entered.await(3,TimeUnit.SECONDS));
            // A cancellation from the child task must cancel its entire parent batch.
            String currentTask=store.currentTask();assertEquals("CONFIRMED",service.cancelByTask(currentTask).getUpstreamStatus());
            running.get(3,TimeUnit.SECONDS);
        }
        Batch cancelled=service.detail(a.getId());assertEquals("CANCELLED",cancelled.getStatus());
        assertEquals(List.of("SUCCESS","CANCELLED","CANCELLED"),cancelled.getItems().stream().map(Item::getStatus).toList());
        assertEquals(savedVideo,cancelled.getItems().getFirst().getOutputUrl());assertEquals(100,cancelled.getProgress());assertNull(store.currentTask());
        service.runNext();assertEquals("SUCCESS",service.detail(b.getId()).getStatus());
        verify(api,never()).generateAndArchiveVideo(eq(43L),anyLong(),any(),any());
        verify(api,times(1)).generateAndArchiveVideo(eq(44L),anyLong(),any(),any());
    }

    @Test void hierarchicalSelectionUnionAndExclusionDoNotDuplicateShots() {
        shot(41,VideoBatchPreparation.FL,1);shot(42,VideoBatchPreparation.REF,2);shot(43,VideoBatchPreparation.FL,3);
        var req=request();req.setShotIds(null);var selection=new VideoRenderBatchRequest.Selection();
        List<VideoRenderBatchRequest.Scope> scopes=new ArrayList<>();
        for(var entry:Map.of("EPISODE",10L,"SCENE",20L,"SHOT_GROUP",30L,"SHOT",41L).entrySet()) {
            var scope=new VideoRenderBatchRequest.Scope();scope.setType(entry.getKey());scope.setId(entry.getValue());scopes.add(scope);
        }
        selection.setInclude(scopes);selection.setExcludeShotIds(List.of(42L));req.setSelection(selection);
        Preview selected=service.preview(req);assertEquals(List.of(41L,43L),selected.getShotIds());assertEquals(2,selected.getAccepted());
        var all=new VideoRenderBatchRequest.Scope();all.setType("ALL");selection.setInclude(List.of(all));selection.setExcludeShotIds(List.of());
        assertEquals(3,service.preview(req).getAccepted());
        assertEquals(1,service.tree(1L).size());assertEquals(3,service.tree(1L).getFirst().getChildren().getFirst().getChildren().getFirst().getChildren().size());
    }

    @Test void sceneImageIsRequiredAndFrozenAtSubmissionForQueuedVideo() throws Exception {
        var shot = shot(41,VideoBatchPreparation.REF,1);
        var scene = new ResScene(); scene.setId(500L); scene.setDramaId(1L); scene.setStatus(1);
        when(sceneAssets.selectById(500L)).thenReturn(scene);
        shot.setRefImagesJson(json.writeValueAsString(List.of(ShotRefImageDTO.builder()
                .id("scene-slot").name("办公室").sourceType("SCENE").sourceId(500L).usageRole("SCENE")
                .bindingMode("ASSET_LINK").sourceSlot("SCENE_REFERENCE").build())));
        var req=request();
        var blocked=service.preview(req);
        assertEquals(1,blocked.getBlocked());
        assertTrue(blocked.getItems().getFirst().getReason().contains("Picture 1"));
        scene.setReferenceImageUrl("http://minio/office-v1.png");
        var preview=service.preview(req); assertEquals(1,preview.getAccepted());
        req.setExpectedPreviewHash(preview.getPreviewHash());
        scene.setReferenceImageUrl("http://minio/office-v2.png");
        assertThrows(com.astra.freyja.common.BizException.class,()->service.create(req));
        var batch=create(req);
        scene.setReferenceImageUrl("http://minio/office-v3.png");
        service.runNext();
        var call=mockingDetails(api).getInvocations().stream()
                .filter(i->i.getMethod().getName().equals("generateAndArchiveVideo")).findFirst().orElseThrow();
        DramaShot frozen=call.getArgument(2);
        assertTrue(frozen.getRefImagesJson().contains("office-v2.png"));
        assertTrue(frozen.getRefImagesJson().contains("SNAPSHOT"));
        assertFalse(frozen.getRefImagesJson().contains("office-v3.png"));
        assertTrue(shot.getRefImagesJson().contains("ASSET_LINK"));
        assertEquals("SUCCESS",service.detail(batch.getId()).getStatus());
    }
}
