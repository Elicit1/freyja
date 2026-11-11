package com.astra.freyja.service;

import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.render.RemoteVideoTaskVO;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.video.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DynamicWorkflowExecutorTest {
    JdbcTemplate jdbc;AiImageApiService api;DynamicWorkflowExecutor executor;List<String> calls;
    ContinuationArtifactService artifacts;
    @BeforeEach void setup()throws Exception{
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dynamic_workflow_step(task_id VARCHAR(100),step_key VARCHAR(100),input_hash CHAR(64),execution_id VARCHAR(180),stage VARCHAR(20),status VARCHAR(24),output_url VARCHAR(2000),prompt_id VARCHAR(128),node_id VARCHAR(128),source_task_id VARCHAR(100),result_manifest_json VARCHAR(4000),error_message VARCHAR(2000),updated_at TIMESTAMP,PRIMARY KEY(task_id,step_key))");
        jdbc.execute("CREATE TABLE render_task(task_id VARCHAR(100),shot_id BIGINT)");
        api=mock(AiImageApiService.class);var media=mock(ReferenceVideoMediaService.class);calls=new ArrayList<>();
        when(media.archiveDynamicSource(any())).thenReturn(new ReferenceVideoMediaService.ContinuationArtifact("reference-video/dynamic/a.mp4","http://minio/a.mp4","a".repeat(64)));
        when(media.transportUrl(any())).thenReturn("http://minio/signed");
        when(api.archiveExistingVideo(anyLong(),anyLong(),anyString(),anyLong())).thenAnswer(call->VideoGenerationResultVO.builder().videoUrl(call.getArgument(2)).build());
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(call->{
            DramaShotRenderRequestDTO req=call.getArgument(3);String stage=req.getDynamicWorkflowStage().get("stage").toString();calls.add(stage);
            VideoExecutionState.accepted("prompt-"+stage,"node");return VideoGenerationResultVO.builder().videoUrl("http://archive/"+stage).build();
        });
        executor=new DynamicWorkflowExecutor(jdbc,new ObjectMapper(),api,media,mock(AiTaskEventStore.class),artifacts=mock(ContinuationArtifactService.class));
    }
    VideoBatchModels.Snapshot snapshot(boolean upscale,boolean face){
        var shot=new DramaShot();shot.setId(1L);shot.setDramaId(2L);shot.setDuration(BigDecimal.valueOf(124/24.0));
        var options=new DynamicWorkflowOptions();options.setUpscale(upscale);options.setFaceRepair(face);
        var plan=new DynamicWorkflowOptions.Plan();plan.setOptions(options);plan.setBaseSize("960x544");plan.setOutputSize(upscale?"1920x1088":"960x544");plan.setNodeId("node");plan.setCapabilityRevision("revision");
        var req=new DramaShotRenderRequestDTO();req.setTaskId("task");req.setProviderId(3L);req.setPrompt("dancer");req.setSize("960x544");req.setDynamicWorkflowPlan(plan);req.setDynamicWorkflowStage(Map.of("stage","BASE","revision","revision"));
        var snap=new VideoBatchModels.Snapshot();snap.setShot(shot);snap.setRequest(req);return snap;
    }
    @AfterEach void cleanup(){VideoExecutionState.clear("task");}
    Map<String,Object> secondPass(String digest){return Map.of("stage","SECOND_PASS","format","FREYJA_H3_AV_V1","sha256",digest.repeat(64),"objectPath","projects/"+digest+".safetensors");}
    VideoBatchModels.Snapshot chainedSnapshot(){
        var snap=snapshot(true,true);var plan=snap.getRequest().getDynamicWorkflowPlan();
        plan.setSecondPassContinuity(true);plan.getOptions().setUpscaleEngine("FREYJA");return snap;
    }
    @Test void enhancementRetainsControlOnlySourceIdsAfterRemovingControlNet()throws Exception{
        var snap=snapshot(true,true);
        snap.getRequest().setH3Options(Map.of("control_net",Map.of("enabled",true,"controls",List.of(Map.of("reference_id","2032095628944588801","enabled",true)))));
        var captured=org.mockito.ArgumentCaptor.forClass(DramaShotRenderRequestDTO.class);
        executor.enhance(snap,"http://archive/base","task","segment-000");
        verify(api,times(2)).generateAndArchiveVideo(anyLong(),anyLong(),any(),captured.capture());
        for(var request:captured.getAllValues()){
            assertNull(request.getH3Options());
            assertEquals(List.of("2032095628944588801"),request.getDynamicWorkflowStage().get("control_source_ids"));
        }
    }
    @Test void selfLiftChoiceIsTransportedAndStandardKeepsHistoricalStageShape()throws Exception{
        for(String sampler:List.of("STANDARD","SELFLIFT")){
            jdbc.update("DELETE FROM dynamic_workflow_step");
            var snap=snapshot(true,false);var options=snap.getRequest().getDynamicWorkflowPlan().getOptions();
            options.setUpscaleEngine("FREYJA");options.setUpscaleSampler(sampler);
            var captured=org.mockito.ArgumentCaptor.forClass(DramaShotRenderRequestDTO.class);
            clearInvocations(api);executor.enhance(snap,"http://archive/base","task","segment-000");
            verify(api).generateAndArchiveVideo(anyLong(),anyLong(),any(),captured.capture());
            var component=(Map<?,?>)captured.getValue().getDynamicWorkflowStage().get("component");
            assertEquals("SELFLIFT".equals(sampler),component.containsKey("sampler"));
            if("SELFLIFT".equals(sampler))assertEquals(sampler,component.get("sampler"));
        }
    }
    @Test void secondPassHandoffPersistsBeforeFaceAndResumesWithoutRepeatedGpuOrArchive()throws Exception{
        var snap=chainedSnapshot();var predecessor=secondPass("b");var saved=secondPass("c");
        when(artifacts.archive(eq(3L),anyString(),eq("prompt-UPSCALE"),eq("node"),eq(2L),eq(1L),eq("SECOND_PASS"))).thenReturn(saved);
        when(artifacts.transport(predecessor)).thenReturn(Map.of("url","http://minio/signed-1","content_sha256","b".repeat(64)));
        VideoExecutionState.begin("task");
        var result=executor.enhanceContinuation(snap,"http://archive/base","task","segment-001",null,predecessor,22);
        assertEquals("http://archive/FACE",result.videoUrl());assertEquals(saved,result.latent());
        assertEquals("SECOND_PASS",new ObjectMapper().readTree(jdbc.queryForObject("SELECT result_manifest_json FROM dynamic_workflow_step WHERE stage='UPSCALE'",String.class)).path("stage").asText());
        var capture=org.mockito.ArgumentCaptor.forClass(DramaShotRenderRequestDTO.class);
        verify(api,times(2)).generateAndArchiveVideo(anyLong(),anyLong(),any(),capture.capture());
        var stage=capture.getAllValues().getFirst().getDynamicWorkflowStage();
        assertEquals(true,stage.get("save_latent"));assertEquals(22,((Map<?,?>)stage.get("latent_context")).get("frames"));
        assertEquals("http://minio/signed-1",((Map<?,?>)stage.get("latent_context")).get("url"));
        assertFalse(capture.getAllValues().getLast().getDynamicWorkflowStage().containsKey("latent_context"));
        when(artifacts.transport(predecessor)).thenReturn(Map.of("url","http://minio/signed-2","content_sha256","b".repeat(64)));
        assertEquals(saved,executor.enhanceContinuation(snap,"http://archive/base","task","segment-001",null,predecessor,22).latent());
        assertEquals(List.of("UPSCALE","FACE"),calls);
        verify(artifacts,times(1)).archive(eq(3L),anyString(),anyString(),anyString(),eq(2L),eq(1L),eq("SECOND_PASS"));
        assertThrows(RuntimeException.class,()->executor.enhanceContinuation(snap,"http://archive/base","task","segment-001",null,secondPass("d"),22));
    }
    @Test void failedSecondPassLatentArchiveIsRecoverableWithoutRepeatingSampling()throws Exception{
        var snap=chainedSnapshot();snap.getRequest().getDynamicWorkflowPlan().getOptions().setFaceRepair(false);
        var saved=secondPass("c");
        when(artifacts.archive(eq(3L),anyString(),anyString(),anyString(),eq(2L),eq(1L),eq("SECOND_PASS")))
                .thenThrow(new java.io.IOException("storage interrupted")).thenReturn(saved);
        VideoExecutionState.begin("task");
        assertThrows(java.io.IOException.class,()->executor.enhanceContinuation(snap,"http://archive/base","task","segment-000",null,null,0));
        assertEquals("ARCHIVING",jdbc.queryForObject("SELECT status FROM dynamic_workflow_step",String.class));
        assertNotNull(executor.activeExecution("task"));
        assertEquals(saved,executor.enhanceContinuation(snap,"http://archive/base","task","segment-000",null,null,0).latent());
        assertEquals(List.of("UPSCALE"),calls);verify(api,never()).queryVideoTask(anyLong(),anyString(),anyString(),anyString());
    }
    @Test void missingOrFirstPassPredecessorNeverSubmitsSecondPass()throws Exception{
        var snap=chainedSnapshot();
        assertThrows(RuntimeException.class,()->executor.enhanceContinuation(snap,"base","task","segment-001",null,null,22));
        assertThrows(RuntimeException.class,()->executor.enhanceContinuation(snap,"base","task","segment-001",null,Map.of("stage","FIRST_PASS"),22));
        verifyNoInteractions(api);
    }
    @Test void secondPassReconcilesRemoteSuccessAndReusesTheDurableManifestAcrossTasks()throws Exception{
        var snap=chainedSnapshot();snap.getRequest().getDynamicWorkflowPlan().getOptions().setFaceRepair(false);
        VideoExecutionState.begin("task");
        doAnswer(call->{VideoExecutionState.accepted("p","n");VideoExecutionState.phase("SENT");throw new IllegalStateException("response lost");})
                .when(api).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        assertThrows(IllegalStateException.class,()->executor.enhanceContinuation(snap,"base","task","segment-000",null,null,0));
        when(api.queryVideoTask(eq(3L),anyString(),eq("p"),eq("n"))).thenReturn(new RemoteVideoTaskVO("SUCCESS","remote","p","n"));
        var saved=secondPass("c");when(artifacts.archive(eq(3L),anyString(),eq("p"),eq("n"),eq(2L),eq(1L),eq("SECOND_PASS"))).thenReturn(saved);
        assertEquals(saved,executor.enhanceContinuation(snap,"base","task","segment-000",null,null,0).latent());
        jdbc.update("INSERT INTO render_task(task_id,shot_id) VALUES ('task',1)");
        snap.getRequest().getDynamicWorkflowPlan().setReuseFromTaskId("task");
        assertEquals(saved,executor.enhanceContinuation(snap,"base","new-task","segment-000",null,null,0).latent());
        verify(api,times(1)).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        verify(artifacts,times(1)).archive(eq(3L),anyString(),anyString(),anyString(),eq(2L),eq(1L),eq("SECOND_PASS"));
        jdbc.update("UPDATE dynamic_workflow_step SET result_manifest_json=NULL WHERE task_id='new-task'");
        assertThrows(RuntimeException.class,()->executor.enhanceContinuation(snap,"base","new-task","segment-000",null,null,0));
    }
    @Test void nativeSourceIsFingerprintStableAcrossExpiringTransportUrls()throws Exception{
        var snap=snapshot(true,false);snap.getRequest().getDynamicWorkflowPlan().setNativeLatentInput(true);
        snap.getRequest().getDynamicWorkflowPlan().getOptions().setUpscaleEngine("FREYJA");
        var latent=Map.<String,Object>of("sha256","b".repeat(64),"objectPath","projects/base.safetensors");
        when(artifacts.transport(latent)).thenReturn(Map.of("url","http://minio/signed-1","content_sha256","b".repeat(64)));
        executor.enhance(snap,"http://archive/base","task","segment-000",latent);
        var captured=org.mockito.ArgumentCaptor.forClass(DramaShotRenderRequestDTO.class);
        verify(api).generateAndArchiveVideo(eq(1L),eq(2L),any(),captured.capture());
        assertEquals("960x544",captured.getValue().getDynamicWorkflowStage().get("source_size"));
        assertEquals("http://minio/signed-1",((Map<?,?>)captured.getValue().getDynamicWorkflowStage().get("source_latent")).get("url"));
        when(artifacts.transport(latent)).thenReturn(Map.of("url","http://minio/signed-2","content_sha256","b".repeat(64)));
        executor.enhance(snap,"http://archive/base","task","segment-000",latent);
        assertEquals(List.of("UPSCALE"),calls);
    }
    @Test void allFourCombinationsKeepOrderAndResumeWithoutRepeatingStages()throws Exception{
        for(boolean upscale:new boolean[]{false,true})for(boolean face:new boolean[]{false,true}){
            jdbc.update("DELETE FROM dynamic_workflow_step");calls.clear();var snap=snapshot(upscale,face);VideoExecutionState.begin("task");
            var expected=new ArrayList<>(List.of("BASE"));if(upscale)expected.add("UPSCALE");if(face)expected.add("FACE");
            assertEquals("http://archive/"+expected.getLast(),executor.render(snap).getVideoUrl());
            assertEquals(expected,calls);executor.render(snap);assertEquals(expected,calls);
            assertEquals(expected.size(),jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_workflow_step WHERE status='SUCCESS'",Integer.class));
            assertEquals("960x544",snap.getRequest().getSize());assertEquals("BASE",snap.getRequest().getDynamicWorkflowStage().get("stage"));
        }
    }
    @Test void failedFaceResumesOnlyFaceAndKeepsBaseAndUpscale()throws Exception{
        var snap=snapshot(true,true);var failed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{DramaShotRenderRequestDTO req=call.getArgument(3);String stage=req.getDynamicWorkflowStage().get("stage").toString();calls.add(stage);VideoExecutionState.accepted("prompt-"+stage,"node");
            if("FACE".equals(stage)&&!failed.getAndSet(true)){VideoExecutionState.phase("REJECTED");throw new IllegalStateException("face failed");}
            assertEquals("BASE".equals(stage)?"960x544":"1920x1088",req.getSize());
            return VideoGenerationResultVO.builder().videoUrl("http://archive/"+stage).build();}).when(api).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        VideoExecutionState.begin("task");assertThrows(IllegalStateException.class,()->executor.render(snap));assertNull(VideoExecutionState.current().recoveryUrl);
        assertEquals("http://archive/FACE",executor.render(snap).getVideoUrl());assertEquals(List.of("BASE","UPSCALE","FACE","FACE"),calls);
    }
    @Test void uncertainSubmissionIsReconciledBeforeAnyNewSampling()throws Exception{
        var snap=snapshot(false,false);VideoExecutionState.begin("task");
        doAnswer(call->{VideoExecutionState.accepted("prompt","node");VideoExecutionState.phase("SENT");throw new IllegalStateException("response lost");}).when(api).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        assertThrows(IllegalStateException.class,()->executor.render(snap));assertTrue(VideoExecutionState.current().uncertain());
        when(api.queryVideoTask(eq(3L),anyString(),eq("prompt"),eq("node"))).thenReturn(new RemoteVideoTaskVO("RUNNING",null,"prompt","node"));
        assertThrows(RuntimeException.class,()->executor.render(snap));verify(api,times(1)).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        when(api.queryVideoTask(eq(3L),anyString(),eq("prompt"),eq("node"))).thenReturn(new RemoteVideoTaskVO("SUCCESS","http://remote/result","prompt","node"));
        assertEquals("http://remote/result",executor.render(snap).getVideoUrl());verify(api,times(1)).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
    }
    @Test void persistenceFailureNeverSubmitsGpuWork(){
        jdbc.execute("DROP TABLE dynamic_workflow_step");VideoExecutionState.begin("task");assertThrows(RuntimeException.class,()->executor.render(snapshot(true,true)));verifyNoInteractions(api);
    }
    @Test void invalidStrengthAndScaleAreRejected(){
        var options=new DynamicWorkflowOptions();options.setFaceDenoise(Double.NaN);assertThrows(RuntimeException.class,options::validate);
        options.setFaceDenoise(.4);options.setScale(3);assertThrows(RuntimeException.class,options::validate);
    }
    @Test void changingFaceSettingsReusesOnlyMatchingBaseAndUpscaleAcrossTasks()throws Exception{
        var snap=snapshot(true,true);VideoExecutionState.begin("task");executor.render(snap);
        jdbc.update("INSERT INTO render_task(task_id,shot_id) VALUES ('task',1)");
        snap.getRequest().setTaskId("new-task");snap.getRequest().getDynamicWorkflowPlan().setReuseFromTaskId("task");snap.getRequest().getDynamicWorkflowPlan().getOptions().setFaceDenoise(.3);
        executor.render(snap);assertEquals(List.of("BASE","UPSCALE","FACE","FACE"),calls);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_workflow_step WHERE task_id='new-task' AND source_task_id='task'",Integer.class));
    }
    @Test void tilingIsUpscaleOnlyAndChangingTilesKeepsBaseReusable()throws Exception{
        var snap=snapshot(true,true);var tile=snap.getRequest().getDynamicWorkflowPlan().getOptions().getUpscaleTiling();
        snap.getRequest().getDynamicWorkflowPlan().getOptions().setUpscaleEngine("FREYJA");snap.getRequest().getDynamicWorkflowPlan().getOptions().setUpscaleMemory("SPATIAL");
        tile.setEnabled(true);tile.setTemporal(true);
        VideoExecutionState.begin("task");executor.render(snap);
        var captor=org.mockito.ArgumentCaptor.forClass(DramaShotRenderRequestDTO.class);
        verify(api,times(3)).generateAndArchiveVideo(anyLong(),anyLong(),any(),captor.capture());
        var requests=captor.getAllValues();
        assertFalse(requests.get(0).getDynamicWorkflowStage().containsKey("tiling"));
        var component=(Map<?,?>)requests.get(1).getDynamicWorkflowStage().get("component");
        var transport=(Map<?,?>)component.get("tiles");
        assertEquals(768,transport.get("tile_width"));assertEquals("SPATIAL",component.get("memory"));
        assertFalse(requests.get(2).getDynamicWorkflowStage().containsKey("tiling"));
        jdbc.update("INSERT INTO render_task(task_id,shot_id) VALUES ('task',1)");
        snap.getRequest().setTaskId("new-task");snap.getRequest().getDynamicWorkflowPlan().setReuseFromTaskId("task");tile.setTileWidth(512);
        executor.render(snap);
        assertEquals(List.of("BASE","UPSCALE","FACE","UPSCALE"),calls);
        assertEquals("task",jdbc.queryForObject("SELECT source_task_id FROM dynamic_workflow_step WHERE task_id='new-task' AND stage='BASE'",String.class));
    }
    @Test void oldOptionsDefaultToFullCanvasAndInvalidTileGridsAreRejected()throws Exception{
        var options=new ObjectMapper().readValue("{\"upscale\":true}",DynamicWorkflowOptions.class);
        options.validate();assertFalse(options.getUpscaleTiling().isEnabled());
        var tile=options.getUpscaleTiling();tile.setTileWidth(513);assertThrows(RuntimeException.class,options::validate);
        tile.setTileWidth(512);tile.setOverlap(512);assertThrows(RuntimeException.class,options::validate);
        tile.setOverlap(192);tile.setWindowFrames(55);assertThrows(RuntimeException.class,options::validate);
        tile.setWindowFrames(56);tile.setOverlapFrames(56);assertThrows(RuntimeException.class,options::validate);
        tile.setOverlapFrames(22);options.validate();
    }
    @Test void cropsOnlyTheFinalEnhancementAndPreservesSamplingSize()throws Exception{
        var snap=snapshot(true,true);var plan=snap.getRequest().getDynamicWorkflowPlan();
        plan.setOutputSize("1920x1080");plan.setProcessingSize("1920x1088");plan.getOptions().setUpscaleEngine("FREYJA");
        VideoExecutionState.begin("task");executor.render(snap);
        var captor=org.mockito.ArgumentCaptor.forClass(DramaShotRenderRequestDTO.class);
        verify(api,times(3)).generateAndArchiveVideo(anyLong(),anyLong(),any(),captor.capture());
        var requests=captor.getAllValues();
        assertEquals("1920x1088",requests.get(1).getSize());assertFalse(requests.get(1).getDynamicWorkflowStage().containsKey("output_height"));
        assertEquals("1920x1088",requests.get(2).getSize());assertEquals(1080,requests.get(2).getDynamicWorkflowStage().get("output_height"));
        assertFalse(requests.get(2).getDynamicWorkflowStage().containsKey("component"));
    }
}
