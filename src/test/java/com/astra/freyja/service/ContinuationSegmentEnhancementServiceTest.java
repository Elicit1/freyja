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
    ContinuationSegmentEnhancementService service;Snapshot snapshot;DynamicWorkflowOptions.Plan plan;Item item;
    @BeforeAll static void metadata(){var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(configuration,"replacement-test"),AiModel.class);}
    @BeforeEach void setup()throws Exception{
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE render_task_step(task_id VARCHAR(100),step_key VARCHAR(100),input_hash CHAR(64),execution_id VARCHAR(180),status VARCHAR(24),output_url VARCHAR(1000),prompt_id VARCHAR(128),node_id VARCHAR(128),source_task_id VARCHAR(128),result_manifest_json VARCHAR(2000),updated_at TIMESTAMP,PRIMARY KEY(task_id,step_key))");
        jdbc.execute("CREATE TABLE dynamic_workflow_step(task_id VARCHAR(100),step_key VARCHAR(100),input_hash CHAR(64),execution_id VARCHAR(180),stage VARCHAR(20),status VARCHAR(24),output_url VARCHAR(1000),prompt_id VARCHAR(128),node_id VARCHAR(128),source_task_id VARCHAR(128),updated_at TIMESTAMP,PRIMARY KEY(task_id,step_key))");
        store=mock(VideoBatchStore.class);workflows=mock(DynamicWorkflowService.class);var models=mock(AiModelMapper.class);var model=new AiModel();model.setId(99L);when(models.selectOne(any())).thenReturn(model);
        service=new ContinuationSegmentEnhancementService(store,jdbc,json,workflows,models);
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
        assertEquals(1,result.getSegmentReplacement().getSegmentIndex());assertEquals("base-1",result.getSegmentReplacement().getOutputs().get(1).getBaseVideoUrl());
        assertEquals(List.of("enhanced-0","enhanced-1","enhanced-2"),result.getSegmentReplacement().getOutputs().stream().map(SegmentOutput::getVideoUrl).toList());
        service.copySteps("source","replacement",1);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM render_task_step WHERE task_id='replacement'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_workflow_step WHERE task_id='replacement'",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_workflow_step WHERE task_id='source'",Integer.class));
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
}
