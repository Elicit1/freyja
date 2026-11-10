package com.astra.freyja.service;

import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.video.*;
import com.astra.freyja.service.video.VideoBatchModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ContinuationSegmentEnhancementServiceTest {
    JdbcTemplate jdbc;ObjectMapper json=new ObjectMapper();VideoBatchStore store;DynamicWorkflowService workflows;
    ContinuationSegmentEnhancementService service;Snapshot snapshot;DynamicWorkflowOptions.Plan plan;Item item;AiModelMapper models;
    ShotVideoCapabilityService videoCapabilities;ShotVideoReferenceService videoReferences;
    @BeforeAll static void metadata(){var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(configuration,"replacement-test"),AiModel.class);}
    @BeforeEach void setup()throws Exception{
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE render_task_step(task_id VARCHAR(100),step_key VARCHAR(100),input_hash CHAR(64),execution_id VARCHAR(180),status VARCHAR(24),output_url VARCHAR(1000),prompt_id VARCHAR(128),node_id VARCHAR(128),source_task_id VARCHAR(128),result_manifest_json VARCHAR(2000),updated_at TIMESTAMP,PRIMARY KEY(task_id,step_key))");
        jdbc.execute("CREATE TABLE dynamic_workflow_step(task_id VARCHAR(100),step_key VARCHAR(100),input_hash CHAR(64),execution_id VARCHAR(180),stage VARCHAR(20),status VARCHAR(24),output_url VARCHAR(1000),prompt_id VARCHAR(128),node_id VARCHAR(128),source_task_id VARCHAR(128),updated_at TIMESTAMP,PRIMARY KEY(task_id,step_key))");
        store=mock(VideoBatchStore.class);workflows=mock(DynamicWorkflowService.class);models=mock(AiModelMapper.class);var model=new AiModel();model.setId(99L);when(models.selectOne(any())).thenReturn(model);
        videoCapabilities=mock(ShotVideoCapabilityService.class);videoReferences=mock(ShotVideoReferenceService.class);
        service=new ContinuationSegmentEnhancementService(store,jdbc,json,workflows,models,videoCapabilities,videoReferences);
        snapshot=new Snapshot();var shot=new DramaShot();shot.setId(1L);snapshot.setShot(shot);var req=new DramaShotRenderRequestDTO();snapshot.setRequest(req);req.setProviderId(2L);req.setWorkflowTemplateId("h3");req.setSize("544x960");
        var options=new DynamicWorkflowOptions();options.setUpscale(true);options.setUpscaleEngine("FREYJA");plan=new DynamicWorkflowOptions.Plan();plan.setOptions(options);plan.setOutputSize("1080x1920");plan.setNodeId("node");plan.setNativeLatentInput(true);req.setDynamicWorkflowPlan(plan);
        var timeline=new ShotContinuationPlan();timeline.setDraft(new ShotContinuation());req.setContinuationPlan(timeline);req.setContinuationSegments(new ArrayList<>());
        for(int i=0;i<3;i++){
            var range=new ShotContinuationPlan.Range();range.setSegmentId("seg-"+i);range.setKeepStartFrame(i*100);range.setKeepEndFrame((i+1)*100);timeline.getRanges().add(range);
            var segment=new ShotContinuation.Segment();segment.setId("seg-"+i);segment.setName("片段"+i);timeline.getDraft().getSegments().add(segment);
            var child=new Snapshot();child.setRequest(new DramaShotRenderRequestDTO());child.getRequest().setDynamicWorkflowPlan(plan);req.getContinuationSegments().add(child);
            var latent=Map.of("sha256","a".repeat(64),"objectPath","projects/latent-"+i+".safetensors","stage","FIRST_PASS","format","FREYJA_H3_AV_V1");
            jdbc.update("INSERT INTO render_task_step(task_id,step_key,input_hash,execution_id,status,output_url,result_manifest_json) VALUES ('source',?,'hash',?,'SUCCESS',?,?)","segment-"+i,"base-exec-"+i,"base-"+i,json.writeValueAsString(latent));
            jdbc.update("INSERT INTO dynamic_workflow_step(task_id,step_key,input_hash,execution_id,stage,status,output_url) VALUES ('source',?,'hash',?,'UPSCALE','SUCCESS',?)",String.format("segment-%03d:01:UPSCALE",i),"upscale-exec-"+i,"enhanced-"+i);
        }
        item=new Item();item.setStatus("SUCCESS");item.setSnapshotJson(json.writeValueAsString(snapshot));when(store.task("source")).thenReturn(item);
        when(workflows.resolve(any(),eq(2L),eq(99L),eq("544x960"),eq("node"))).thenReturn(plan);
    }
    @Test void freezesUnchangedClipsAndCopiesOnlyTheirEnhancementsWithoutTouchingOriginal(){
        var result=service.prepare("source","seg-1",plan.getOptions());
        assertEquals(List.of(1),result.getSegmentReplacement().indices());assertEquals("base-1",result.getSegmentReplacement().getOutputs().get(1).getBaseVideoUrl());
        assertEquals(List.of("enhanced-0","enhanced-1","enhanced-2"),result.getSegmentReplacement().getOutputs().stream().map(SegmentOutput::getVideoUrl).toList());
        service.copySteps("source","replacement",1);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM render_task_step WHERE task_id='replacement'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_workflow_step WHERE task_id='replacement'",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_workflow_step WHERE task_id='source'",Integer.class));
        verifyNoInteractions(videoCapabilities,videoReferences);
    }
    @Test void rejectsForeignSegmentMissingNativeSourceIncompleteVideoAndChangedCanvas(){
        assertThrows(RuntimeException.class,()->service.prepare("source","foreign",plan.getOptions()));
        jdbc.update("UPDATE render_task_step SET result_manifest_json=NULL WHERE step_key='segment-1'");
        assertFalse(service.summary("source").segments().get(1).allowed());assertThrows(RuntimeException.class,()->service.prepare("source","seg-1",plan.getOptions()));
        plan.setOutputSize("1920x1080");assertThrows(RuntimeException.class,()->service.prepare("source","seg-0",plan.getOptions()));
        jdbc.update("DELETE FROM dynamic_workflow_step WHERE step_key='segment-002:01:UPSCALE'");assertFalse(service.summary("source").supported());
    }
    @Test void completedReplacementsCanBeUsedAsTheNextSourceWithoutLosingEarlierReplacements()throws Exception{
        service.copySteps("source","replacement",1);
        jdbc.update("INSERT INTO dynamic_workflow_step(task_id,step_key,status,output_url) VALUES ('replacement','segment-001:01:UPSCALE','SUCCESS','replacement-1')");
        var replacement=service.prepare("source","seg-1",plan.getOptions());item.setSnapshotJson(json.writeValueAsString(replacement));when(store.task("replacement")).thenReturn(item);
        var next=service.prepare("replacement","seg-2",plan.getOptions());
        assertEquals("replacement-1",next.getSegmentReplacement().getOutputs().get(1).getVideoUrl());
    }
    @Test void selectsMultipleSegmentsInTimelineOrderAndCopiesOnlyUnselectedEnhancements(){
        var result=service.prepare("source",List.of("seg-2","seg-0"),plan.getOptions());
        assertEquals(List.of(0,2),result.getSegmentReplacement().indices());
        service.copySteps("source","replacement",result.getSegmentReplacement().indices());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM render_task_step WHERE task_id='replacement'",Integer.class));
        assertEquals(List.of("segment-001:01:UPSCALE"),jdbc.queryForList("SELECT step_key FROM dynamic_workflow_step WHERE task_id='replacement'",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_workflow_step WHERE task_id='source'",Integer.class));
    }
    @Test void selectingAllKeepsAllBaseArtifactsAndDropsAllOldEnhancements(){
        var result=service.prepare("source",List.of("seg-0","seg-1","seg-2"),plan.getOptions());
        service.copySteps("source","replacement",result.getSegmentReplacement().indices());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM render_task_step WHERE task_id='replacement'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_workflow_step WHERE task_id='replacement'",Integer.class));
    }
    @Test void rejectsEmptyDuplicateForeignAndPartlyUnavailableSelections(){
        for(var ids:Arrays.asList(null,List.<String>of(),List.of("seg-0","seg-0"),List.of("seg-0","foreign"),Arrays.asList("seg-0",null),List.of(" ")))
            assertThrows(RuntimeException.class,()->service.prepare("source",ids,plan.getOptions()));
        jdbc.update("UPDATE render_task_step SET result_manifest_json=NULL WHERE step_key='segment-1'");
        assertThrows(RuntimeException.class,()->service.prepare("source",List.of("seg-0","seg-1"),plan.getOptions()));
        verify(workflows,never()).resolve(any(),any(),any(),any(),any());
    }
    @Test void commonUpscaleSettingsPreserveEachSegmentsFaceRepair()throws Exception{
        var original=snapshot.getRequest().getContinuationSegments().get(1).getRequest();
        var facePlan=json.convertValue(plan,DynamicWorkflowOptions.Plan.class);
        facePlan.getOptions().setFaceRepair(true);facePlan.getOptions().setFaceDenoise(.35);original.setDynamicWorkflowPlan(facePlan);
        item.setSnapshotJson(json.writeValueAsString(snapshot));
        jdbc.update("INSERT INTO dynamic_workflow_step(task_id,step_key,status,output_url) VALUES ('source','segment-001:02:FACE','SUCCESS','face-1')");
        when(workflows.resolve(any(),any(),any(),any(),any())).thenAnswer(call->{
            var resolved=json.convertValue(plan,DynamicWorkflowOptions.Plan.class);
            resolved.setOptions(((DynamicWorkflowOptions.Selection)call.getArgument(0)).getOptions());return resolved;
        });
        var options=json.convertValue(plan.getOptions(),DynamicWorkflowOptions.class);options.setUpscaleDenoise(.1);
        var result=service.prepare("source",List.of("seg-0","seg-1"),options);
        var first=result.getRequest().getContinuationSegments().get(0).getRequest().getDynamicWorkflowPlan().getOptions();
        var second=result.getRequest().getContinuationSegments().get(1).getRequest().getDynamicWorkflowPlan().getOptions();
        assertEquals(.1,first.getUpscaleDenoise());assertEquals(.1,second.getUpscaleDenoise());
        assertFalse(first.isFaceRepair());assertTrue(second.isFaceRepair());assertEquals(.35,second.getFaceDenoise());
    }
    private ShotVideoModelOptionVO archivedVideoReferences()throws Exception{
        var cap=new ReferenceVideoCapability();cap.setMaxVideos(2);cap.setMaxMixedAssets(12);cap.setDecodeFps(24);
        cap.setMinClipSeconds(new java.math.BigDecimal("0.1"));cap.setMaxClipSeconds(java.math.BigDecimal.valueOf(15));cap.setMaxTotalClipSeconds(java.math.BigDecimal.valueOf(15));
        cap.setMaxDecodedFrames(360);cap.setMaxDecodeWidth(768);cap.setMaxDecodeHeight(768);
        var option=new ShotVideoModelOptionVO();option.setNodeId("node");option.setWorkflowRevision("reference-current");option.setRenderable(true);option.setReferenceVideo(cap);
        when(videoCapabilities.describe(any(AiModel.class))).thenReturn(option);
        doCallRealMethod().when(videoCapabilities).requireRenderable(any());
        var media=new ReferenceVideoMediaService(new com.astra.freyja.config.ReferenceVideoProperties(),new com.astra.freyja.config.MediaToolProperties(),
                mock(VideoFrameExtractService.class),mock(NativeProcessExecutor.class),mock(io.minio.MinioClient.class),new com.astra.freyja.config.MinioProperties());
        videoReferences=spy(new ShotVideoReferenceService(mock(com.astra.freyja.dao.DramaShotMapper.class),models,mock(VideoProcessSourceResolver.class),videoCapabilities,media,
                new com.astra.freyja.config.ReferenceVideoProperties(),jdbc,json,Runnable::run,mock(org.springframework.transaction.PlatformTransactionManager.class)));
        service=new ContinuationSegmentEnhancementService(store,jdbc,json,workflows,models,videoCapabilities,videoReferences);
        for(int i=0;i<3;i++){
            var ref=new ResolvedShotVideoReference();ref.setId("ref-"+i);ref.setOrdinal(1);ref.setObjectPath("reference-video/archived-"+i+"/clip.mp4");ref.setContentSha256("b".repeat(64));
            ref.setStartSeconds(java.math.BigDecimal.ZERO);ref.setEndSeconds(java.math.BigDecimal.valueOf(2));ref.setIncludeAudio(false);
            var metadata=new com.astra.freyja.dto.video.VideoProbeInfoVO();metadata.setWidth(640);metadata.setHeight(360);metadata.setFps(24.0);metadata.setFrameCount(48L);metadata.setDuration(java.math.BigDecimal.valueOf(2));ref.setMetadata(metadata);
            var child=snapshot.getRequest().getContinuationSegments().get(i);child.setShot(new DramaShot());child.getShot().setSeed(123L);
            child.getRequest().setResolvedRefVideos(List.of(ref));child.getRequest().setReferenceVideoNodeId("node");child.getRequest().setReferenceVideoRevision("reference-old");
        }
        item.setSnapshotJson(json.writeValueAsString(snapshot));return option;
    }
    @Test void revalidatesArchivedReferencesAndPinsCurrentVersionOnlyForSelectedSegments()throws Exception{
        archivedVideoReferences();String original=item.getSnapshotJson();
        var result=service.prepare("source",List.of("seg-0","seg-2"),plan.getOptions());
        for(int i=0;i<3;i++){
            var before=snapshot.getRequest().getContinuationSegments().get(i);var after=result.getRequest().getContinuationSegments().get(i);
            assertEquals(i==1?"reference-old":"reference-current",after.getRequest().getReferenceVideoRevision());
            assertEquals("node",after.getRequest().getReferenceVideoNodeId());
            assertEquals(before.getRequest().getResolvedRefVideos(),after.getRequest().getResolvedRefVideos());
            assertEquals(before.getShot().getSeed(),after.getShot().getSeed());
        }
        assertEquals(original,item.getSnapshotJson());
        verify(videoCapabilities,times(1)).describe(any(AiModel.class));
        verify(videoReferences,times(2)).validateSnapshot(anyList(),any(),any());
        verify(videoReferences,never()).prepareSegment(any(),any(),any());
    }
    @Test void singleSegmentRouteAlsoRefreshesItsReferenceContract()throws Exception{
        archivedVideoReferences();
        var result=service.prepare("source","seg-1",plan.getOptions());
        assertEquals("reference-current",result.getRequest().getContinuationSegments().get(1).getRequest().getReferenceVideoRevision());
        assertEquals("reference-old",result.getRequest().getContinuationSegments().get(0).getRequest().getReferenceVideoRevision());
    }
    @Test void currentMediaLimitsAndOfflineModelsStillBlockReplacement()throws Exception{
        var option=archivedVideoReferences();String original=item.getSnapshotJson();
        option.getReferenceVideo().setMaxDecodedFrames(10);
        assertThrows(com.astra.freyja.common.BizException.class,()->service.prepare("source",List.of("seg-0","seg-2"),plan.getOptions()));
        option.getReferenceVideo().setMaxDecodedFrames(360);option.getReferenceVideo().setDecodeFps(30);
        assertThrows(com.astra.freyja.common.BizException.class,()->service.prepare("source","seg-1",plan.getOptions()));
        option.getReferenceVideo().setDecodeFps(24);option.setRenderable(false);
        assertThrows(com.astra.freyja.common.BizException.class,()->service.prepare("source","seg-1",plan.getOptions()));
        assertEquals(original,item.getSnapshotJson());
    }
    @Test void referenceContractCannotSilentlyMoveReplacementToAnotherNode()throws Exception{
        var option=archivedVideoReferences();option.setNodeId("another-node");
        var error=assertThrows(com.astra.freyja.common.BizException.class,()->service.prepare("source","seg-1",plan.getOptions()));
        assertTrue(error.getMessage().contains("算力节点不一致"));
        assertEquals("node",json.readValue(item.getSnapshotJson(),Snapshot.class).getRequest().getContinuationSegments().get(1).getRequest().getReferenceVideoNodeId());
    }
}
