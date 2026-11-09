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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShotContinuationRendererTest {
    JdbcTemplate jdbc;AiImageApiService api;ShotContinuationMedia media;ShotContinuationRenderer renderer;
    @BeforeEach void setup()throws Exception{
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL");jdbc=new JdbcTemplate(ds);
        // Keep this database alive across the renderer's independent persistence operations.
        ds.setURL(ds.getURL()+";DB_CLOSE_DELAY=-1");
        jdbc.execute("CREATE TABLE render_task_step(task_id VARCHAR(100),step_key VARCHAR(100),input_hash CHAR(64),execution_id VARCHAR(180),status VARCHAR(24),output_url VARCHAR(1000),error_message VARCHAR(1000),node_id VARCHAR(128),prompt_id VARCHAR(128),source_task_id VARCHAR(128),result_manifest_json VARCHAR(4000),updated_at TIMESTAMP,PRIMARY KEY(task_id,step_key))");
        jdbc.execute("CREATE TABLE render_task(task_id VARCHAR(100),shot_id BIGINT)");
        api=mock(AiImageApiService.class);media=mock(ShotContinuationMedia.class);var refs=mock(ReferenceVideoMediaService.class);
        when(media.tail(anyString(),anyInt(),anyInt())).thenReturn(new ReferenceVideoMediaService.ContinuationArtifact("reference-video/context","https://archive/context","a".repeat(64)));
        when(refs.transportUrl(anyString())).thenReturn("https://archive/context");when(media.assemble(anyList(),any())).thenReturn("https://archive/full");
        when(api.archiveExistingVideo(anyLong(),anyLong(),anyString())).thenAnswer(call->VideoGenerationResultVO.builder().videoUrl(call.getArgument(2)).build());
        artifacts=mock(ContinuationArtifactService.class);
        renderer=new ShotContinuationRenderer(jdbc,new ObjectMapper(),api,media,refs,mock(AiTaskEventStore.class),artifacts);
    }
    private ContinuationArtifactService artifacts;
    VideoBatchModels.Snapshot snapshot(String task){
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(30);draft.setSegmentFrames(362);draft.setOverlapFrames(39);var plan=new ShotContinuationPlanner().plan(draft,true);
        var shot=new DramaShot();shot.setId(1L);shot.setDramaId(2L);shot.setRefImagesJson("[]");shot.setRefVideosJson("[]");
        var request=new DramaShotRenderRequestDTO();request.setTaskId(task);request.setProviderId(3L);request.setWorkflowTemplateId("h3");request.setSize("960x544");request.setContinuationPlan(plan);request.setContinuationSegments(new ArrayList<>());
        for(var range:plan.getRanges()){
            var child=new VideoBatchModels.Snapshot();child.setShot(new ObjectMapper().convertValue(shot,DramaShot.class));child.getShot().setDuration(BigDecimal.valueOf((range.getEndFrame()-range.getStartFrame())/24.0));
            var req=new DramaShotRenderRequestDTO();req.setProviderId(3L);req.setPrompt("continue "+range.getSegmentId());req.setSeed(1L);req.setH3Options(new LinkedHashMap<>(Map.of("length",range.getEndFrame()-range.getStartFrame(),"continuation_enabled",true)));child.setRequest(req);request.getContinuationSegments().add(child);
        }
        var parent=new VideoBatchModels.Snapshot();parent.setShot(shot);parent.setRequest(request);return parent;
    }
    @Test void resumesAcceptedRemoteResultWithoutRegeneratingCompletedOrPendingSegments()throws Exception{
        var snap=snapshot("parent");var calls=new AtomicInteger();
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(call->{int n=calls.getAndIncrement();VideoExecutionState.accepted("prompt-"+n,"node");if(n==1){VideoExecutionState.phase("SENT");throw new IllegalStateException("response lost");}return VideoGenerationResultVO.builder().videoUrl("https://archive/"+n).build();});
        when(api.queryVideoTask(eq(3L),anyString(),eq("prompt-1"),eq("node"))).thenReturn(new RemoteVideoTaskVO("SUCCESS","https://archive/1","prompt-1","node"));
        VideoExecutionState.begin("parent");try{
            assertThrows(IllegalStateException.class,()->renderer.render(snap));assertNull(VideoExecutionState.current().recoveryUrl);
            var result=renderer.render(snap);assertEquals("https://archive/full",result.getVideoUrl());assertEquals(3,calls.get());
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM render_task_step WHERE status='SUCCESS'",Integer.class));
            verify(api).archiveExistingVideo(1L,2L,"https://archive/1");
        }finally{VideoExecutionState.clear("parent");}
    }
    @Test void unknownRemoteStateNeverAuthorizesAnotherGpuSubmission()throws Exception{
        var snap=snapshot("unknown");when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(call->{VideoExecutionState.accepted("prompt","node");VideoExecutionState.phase("SENT");throw new IllegalStateException("response lost");});
        when(api.queryVideoTask(eq(3L),anyString(),eq("prompt"),eq("node"))).thenReturn(new RemoteVideoTaskVO("UNKNOWN",null,"prompt","node"));
        VideoExecutionState.begin("unknown");try{assertThrows(Exception.class,()->renderer.render(snap));assertThrows(Exception.class,()->renderer.render(snap));verify(api,times(1)).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());}finally{VideoExecutionState.clear("unknown");}
    }
    @Test void corruptedSuccessfulStepStopsInsteadOfSubmittingAnotherGpuJob()throws Exception{
        var snap=snapshot("corrupt");when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenReturn(VideoGenerationResultVO.builder().videoUrl("https://archive/segment").build());
        renderer.render(snap);clearInvocations(api);
        jdbc.update("UPDATE render_task_step SET output_url=NULL WHERE task_id='corrupt' AND step_key='segment-0'");
        assertThrows(com.astra.freyja.common.BizException.class,()->renderer.render(snap));
        verifyNoInteractions(api);
    }
    @Test void failedLatentArchiveResumesWithoutRegeneratingAndNativeHandoffNeverReadsMp4()throws Exception{
        var snap=snapshot("latent");
        for(var range:snap.getRequest().getContinuationPlan().getRanges()){
            range.setSaveLatent(true);range.getEffectiveContinuity().setMode(ShotContinuityOptions.Mode.LATENT_REDRAW);
        }
        var calls=new AtomicInteger();
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(call->{int n=calls.incrementAndGet();VideoExecutionState.accepted("prompt-"+n,"node");return VideoGenerationResultVO.builder().videoUrl("https://archive/"+n).build();});
        var saved=Map.<String,Object>of("objectPath","projects/2/shots/1/continuation/latent/a.safetensors","sha256","a".repeat(64));
        when(artifacts.archive(anyLong(),anyString(),anyString(),anyString(),anyLong(),anyLong())).thenThrow(new java.io.IOException("upload interrupted")).thenReturn(saved);
        when(artifacts.read(anyString())).thenReturn(saved);
        when(artifacts.transport(anyMap())).thenReturn(Map.of("url","https://minio/latent","content_sha256","a".repeat(64)));
        VideoExecutionState.begin("latent");try{
            assertThrows(java.io.IOException.class,()->renderer.render(snap));
            assertEquals("ARCHIVING",jdbc.queryForObject("SELECT status FROM render_task_step WHERE task_id='latent' AND step_key='segment-0'",String.class));
            assertEquals("https://archive/full",renderer.render(snap).getVideoUrl());
            assertEquals(snap.getRequest().getContinuationSegments().size(),calls.get());
            verify(media,never()).tail(anyString(),anyInt(),anyInt());
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM render_task_step WHERE result_manifest_json IS NOT NULL AND status='SUCCESS'",Integer.class));
            clearInvocations(api);renderer.render(snap);verify(api,never()).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        }finally{VideoExecutionState.clear("latent");}
    }
    @Test void v2AudioEditReusesOnlyTheUnchangedPrefix()throws Exception{
        var snap=snapshot("audio-old");var calls=new AtomicInteger();
        for(var child:snap.getRequest().getContinuationSegments()){child.getRequest().getH3Options().put("schema_version",2);child.getShot().setRefAudiosJson("[]");}
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(call->VideoGenerationResultVO.builder().videoUrl("https://archive/"+calls.incrementAndGet()).build());
        renderer.render(snap);assertEquals(3,calls.get());
        jdbc.update("INSERT INTO render_task(task_id,shot_id) VALUES ('audio-old',1)");
        snap.getRequest().setTaskId("audio-new");snap.getRequest().getContinuationPlan().getDraft().setReuseFromTaskId("audio-old");
        snap.getRequest().getContinuationSegments().get(1).getShot().setRefAudiosJson("[{\"audioUrl\":\"https://archive/new-voice.wav\"}]");
        renderer.render(snap);assertEquals(5,calls.get());
        assertEquals("audio-old",jdbc.queryForObject("SELECT source_task_id FROM render_task_step WHERE task_id='audio-new' AND step_key='segment-0'",String.class));
        assertNull(jdbc.queryForObject("SELECT source_task_id FROM render_task_step WHERE task_id='audio-new' AND step_key='segment-1'",String.class));
    }
}
