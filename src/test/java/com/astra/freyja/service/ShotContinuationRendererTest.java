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
    JdbcTemplate jdbc;AiImageApiService api;ShotContinuationMedia media;ShotContinuationRenderer renderer;DynamicWorkflowExecutor dynamic;
    @BeforeEach void setup()throws Exception{
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL");jdbc=new JdbcTemplate(ds);
        // Keep this database alive across the renderer's independent persistence operations.
        ds.setURL(ds.getURL()+";DB_CLOSE_DELAY=-1");
        jdbc.execute("CREATE TABLE render_task_step(task_id VARCHAR(100),step_key VARCHAR(100),input_hash CHAR(64),execution_id VARCHAR(180),status VARCHAR(24),output_url VARCHAR(1000),error_message VARCHAR(1000),node_id VARCHAR(128),prompt_id VARCHAR(128),source_task_id VARCHAR(128),result_manifest_json VARCHAR(4000),updated_at TIMESTAMP,PRIMARY KEY(task_id,step_key))");
        jdbc.execute("CREATE TABLE render_task(task_id VARCHAR(100),shot_id BIGINT)");
        api=mock(AiImageApiService.class);media=mock(ShotContinuationMedia.class);var refs=mock(ReferenceVideoMediaService.class);
        when(media.tail(anyString(),anyInt(),anyInt())).thenReturn(new ReferenceVideoMediaService.ContinuationArtifact("reference-video/context","https://archive/context","a".repeat(64)));
        when(refs.transportUrl(anyString())).thenReturn("https://archive/context");when(media.assemble(anyList(),any())).thenReturn("https://archive/full");
        when(api.archiveExistingVideo(anyLong(),anyLong(),anyString(),anyLong())).thenAnswer(call->VideoGenerationResultVO.builder().videoUrl(call.getArgument(2)).build());
        artifacts=mock(ContinuationArtifactService.class);
        renderer=new ShotContinuationRenderer(dynamic=mock(DynamicWorkflowExecutor.class),jdbc,new ObjectMapper(),api,media,refs,mock(AiTaskEventStore.class),artifacts);
    }
    private ContinuationArtifactService artifacts;
    @Test void baseRetakeRendersOnlySelectedAndUsesRawPredecessorThenNewBaseForFace()throws Exception{
        var snap=replacementSnapshot(List.of(1));snap.getSegmentReplacement().setRerunMode("BASE_ONLY");
        var plan=new DynamicWorkflowOptions.Plan();var options=new DynamicWorkflowOptions();options.setFaceRepair(true);plan.setOptions(options);plan.setOutputSize("960x544");snap.getRequest().setDynamicWorkflowPlan(plan);
        snap.getRequest().getContinuationSegments().get(1).getRequest().setDynamicWorkflowPlan(plan);
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenReturn(VideoGenerationResultVO.builder().videoUrl("https://new/base").build());
        when(dynamic.enhance(any(),eq("https://new/base"),anyString(),eq("segment-001"))).thenReturn("https://new/face");
        when(media.assemble(anyList(),any(),eq("960x544"))).thenReturn("https://new/full");
        assertEquals("https://new/full",renderer.render(snap).getVideoUrl());
        renderer.render(snap);
        verify(api,times(1)).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        verify(media).tail(eq("https://base/0"),anyInt(),anyInt());
        verify(dynamic,times(2)).enhance(any(),eq("https://new/base"),anyString(),eq("segment-001"));
        verify(media,times(2)).assemble(eq(List.of("https://enhanced/0","https://new/face","https://enhanced/2")),any(),eq("960x544"));
    }
    @Test void firstAndSecondPassesUseSeparatePredecessorsAndRestoreTheirChainsOnResume()throws Exception{
        var snap=snapshot("dual-pass");var count=new AtomicInteger();
        for(var child:snap.getRequest().getContinuationSegments()){
            var plan=new DynamicWorkflowOptions.Plan();var options=new DynamicWorkflowOptions();options.setUpscale(true);
            plan.setOptions(options);plan.setSecondPassContinuity(true);child.getRequest().setDynamicWorkflowPlan(plan);
        }
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(call->VideoGenerationResultVO.builder().videoUrl("https://base/"+count.getAndIncrement()).build());
        var carried=new ArrayList<Map<String,Object>>();
        for(int i=0;i<3;i++)carried.add(Map.of("stage","SECOND_PASS","sha256",String.valueOf(i).repeat(64)));
        when(dynamic.enhanceContinuation(any(),anyString(),anyString(),anyString(),nullable(Map.class),nullable(Map.class),anyInt())).thenAnswer(call->{
            int index=Integer.parseInt(call.<String>getArgument(3).substring(8));
            assertEquals(index==0?null:carried.get(index-1),call.getArgument(5));
            assertEquals(snap.getRequest().getContinuationPlan().getRanges().get(index).getOverlapFrames(),call.<Integer>getArgument(6));
            return new DynamicWorkflowExecutor.Enhancement("https://enhanced/"+index,carried.get(index));
        });
        renderer.render(snap);renderer.render(snap);
        assertEquals(3,count.get());
        verify(media).tail(eq("https://base/0"),anyInt(),anyInt());
        verify(media).tail(eq("https://base/1"),anyInt(),anyInt());
        verify(media,times(2)).assemble(eq(List.of("https://enhanced/0","https://enhanced/1","https://enhanced/2")),any());
    }
    @Test void replacementUsesFrozenPreviousSecondPassAndNewlyRenderedSelectedSecondPass()throws Exception{
        var snap=replacementSnapshot(List.of(1,2));var outputs=snap.getSegmentReplacement().getOutputs();
        var old=Map.<String,Object>of("stage","SECOND_PASS","sha256","a".repeat(64));outputs.getFirst().setUpscaleLatent(old);
        var fresh=Map.<String,Object>of("stage","SECOND_PASS","sha256","b".repeat(64));
        for(var child:snap.getRequest().getContinuationSegments()){
            var plan=new DynamicWorkflowOptions.Plan();plan.setSecondPassContinuity(true);child.getRequest().setDynamicWorkflowPlan(plan);
        }
        when(dynamic.enhanceContinuation(any(),anyString(),anyString(),eq("segment-001"),anyMap(),eq(old),anyInt()))
                .thenReturn(new DynamicWorkflowExecutor.Enhancement("https://new/1",fresh));
        when(dynamic.enhanceContinuation(any(),anyString(),anyString(),eq("segment-002"),anyMap(),eq(fresh),anyInt()))
                .thenReturn(new DynamicWorkflowExecutor.Enhancement("https://new/2",fresh));
        renderer.render(snap);
        verify(media).assemble(eq(List.of("https://enhanced/0","https://new/1","https://new/2")),any());
        verify(api,never()).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
        assertNull(outputs.get(1).getUpscaleLatent());
    }
    @Test void replacesOnlySelectedEnhancementThenAssemblesOriginalTimelineWithoutBaseRendering()throws Exception{
        var snap=snapshot("replacement");var replacement=new VideoBatchModels.SegmentReplacement();replacement.setSourceTaskId("original");replacement.setSegmentIndex(1);
        var outputs=new ArrayList<VideoBatchModels.SegmentOutput>();
        for(int i=0;i<3;i++){var out=new VideoBatchModels.SegmentOutput();out.setBaseVideoUrl("https://base/"+i);out.setVideoUrl("https://enhanced/"+i);out.setLatent(Map.of("sha256","a".repeat(64)));outputs.add(out);}
        replacement.setOutputs(outputs);snap.setSegmentReplacement(replacement);
        when(dynamic.enhance(any(),eq("https://base/1"),eq("replacement"),eq("segment-001"),anyMap())).thenReturn("https://enhanced/replacement-1");
        VideoExecutionState.begin("replacement");
        try{
            renderer.render(snap);
            verify(dynamic).enhance(same(snap.getRequest().getContinuationSegments().get(1)),eq("https://base/1"),eq("replacement"),eq("segment-001"),eq(outputs.get(1).getLatent()));
            verify(media).assemble(eq(List.of("https://enhanced/0","https://enhanced/replacement-1","https://enhanced/2")),same(snap.getRequest().getContinuationPlan()));
            verify(api,never()).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());verify(media,never()).tail(anyString(),anyInt(),anyInt());
            assertEquals("https://enhanced/1",outputs.get(1).getVideoUrl());
        }finally{VideoExecutionState.clear("replacement");}
    }
    private VideoBatchModels.Snapshot replacementSnapshot(List<Integer> indices){
        var snap=snapshot("replacement");var replacement=new VideoBatchModels.SegmentReplacement();replacement.setSegmentIndices(indices);
        var outputs=new ArrayList<VideoBatchModels.SegmentOutput>();
        for(int i=0;i<3;i++){var out=new VideoBatchModels.SegmentOutput();out.setBaseVideoUrl("https://base/"+i);out.setVideoUrl("https://enhanced/"+i);out.setLatent(Map.of("sha256","a".repeat(64)));outputs.add(out);}
        replacement.setOutputs(outputs);snap.setSegmentReplacement(replacement);return snap;
    }
    @Test void multipleOrAllReplacementsRunInTimelineOrderAndAssembleExactlyOnce()throws Exception{
        for(var indices:List.of(List.of(0,2),List.of(0,1,2))){
            var snap=replacementSnapshot(indices);clearInvocations(dynamic,media,api);
            when(dynamic.enhance(any(),anyString(),anyString(),anyString(),anyMap())).thenAnswer(call->"https://rerun/"+call.getArgument(3));
            renderer.render(snap);
            var ordered=inOrder(dynamic);
            for(int index:indices)ordered.verify(dynamic).enhance(same(snap.getRequest().getContinuationSegments().get(index)),eq("https://base/"+index),eq("replacement"),eq(String.format("segment-%03d",index)),anyMap());
            ordered.verifyNoMoreInteractions();
            var expected=new ArrayList<String>();for(int i=0;i<3;i++)expected.add(indices.contains(i)?"https://rerun/"+String.format("segment-%03d",i):"https://enhanced/"+i);
            verify(media,times(1)).assemble(eq(expected),same(snap.getRequest().getContinuationPlan()));
            verify(api,never()).generateAndArchiveVideo(anyLong(),anyLong(),any(),any());
            assertEquals("https://enhanced/0",snap.getSegmentReplacement().getOutputs().getFirst().getVideoUrl());
        }
    }
    @Test void invalidReplacementSelectionsNeverReachTheGpuOrAssembler(){
        for(var indices:List.of(List.<Integer>of(),List.of(-1),List.of(3),List.of(0,0),Arrays.asList(0,null))){
            assertThrows(com.astra.freyja.common.BizException.class,()->renderer.render(replacementSnapshot(indices)));
        }
        verifyNoInteractions(dynamic,media,api);
    }
    @Test void historicalSingleSegmentSnapshotStillReadsWithoutRewritingStoredJson()throws Exception{
        var json=new ObjectMapper();
        var old=json.readValue("{\"segmentIndex\":1}",VideoBatchModels.SegmentReplacement.class);
        assertEquals(List.of(1),old.indices());
        assertNull(old.getSegmentIndices());
        var current=json.readValue("{\"segmentIndices\":[0,2]}",VideoBatchModels.SegmentReplacement.class);
        assertEquals(List.of(0,2),current.indices());
    }
    @Test void enhancedOutputsNeverBecomeNextSegmentsPixelHandoff()throws Exception{
        var snap=snapshot("dynamic-continuation");var calls=new AtomicInteger();
        for(var child:snap.getRequest().getContinuationSegments())child.getRequest().setDynamicWorkflowPlan(new DynamicWorkflowOptions.Plan());
        when(api.generateAndArchiveVideo(anyLong(),anyLong(),any(),any())).thenAnswer(call->VideoGenerationResultVO.builder().videoUrl("https://archive/base-"+calls.getAndIncrement()).build());
        when(dynamic.enhance(any(),anyString(),anyString(),anyString())).thenAnswer(call->"https://enhanced/"+call.getArgument(1).toString().substring("https://archive/".length()));
        renderer.render(snap);
        verify(media).tail(eq("https://archive/base-0"),anyInt(),anyInt());
        verify(media).tail(eq("https://archive/base-1"),anyInt(),anyInt());
        verify(media).assemble(eq(List.of("https://enhanced/base-0","https://enhanced/base-1","https://enhanced/base-2")),any());
    }
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
            verify(api).archiveExistingVideo(1L,2L,"https://archive/1",3L);
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
