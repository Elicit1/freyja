package com.astra.freyja.service;

import com.astra.freyja.dto.drama.*;
import com.astra.freyja.service.video.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VideoExecutionStateTest {
    final List<String> messages=new ArrayList<>();
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void start(){VideoExecutionState.begin("test",(stage,message)->messages.add(message));}
    @AfterEach void end(){Thread.interrupted();VideoExecutionState.clear("test");}
    void configure(){
        var req=new DramaShotRenderRequestDTO();req.setContinuationSegments(List.of(new VideoBatchModels.Snapshot(),new VideoBatchModels.Snapshot()));
        var plan=new DynamicWorkflowOptions.Plan();var options=new DynamicWorkflowOptions();options.setUpscale(true);options.setFaceRepair(true);plan.setOptions(options);req.setDynamicWorkflowPlan(plan);
        var snap=new VideoBatchModels.Snapshot();snap.setRequest(req);VideoExecutionState.configure(snap);
    }
    @Test void measuredStepsAndEstimatedOverallProgressStayMonotonicAcrossSegmentsAndStages(){
        configure();VideoExecutionState.segment(0);
        var sample=json.createObjectNode().put("value",4).put("max",8).put("nodeType","SamplerCustomAdvanced").put("updatedAt",System.currentTimeMillis()/1000.0);
        VideoExecutionState.sampling(sample);int base=VideoExecutionState.current().percentage;
        assertTrue(messages.getLast().contains("片段 1/2"));assertTrue(messages.getLast().contains("4/8"));assertTrue(messages.getLast().contains("总体估算"));
        VideoExecutionState.stage("UPSCALE");VideoExecutionState.sampling(sample);int upscale=VideoExecutionState.current().percentage;assertTrue(upscale>base);
        VideoExecutionState.stage("FACE");VideoExecutionState.sampling(sample);assertTrue(VideoExecutionState.current().percentage>upscale);
        VideoExecutionState.segment(1);VideoExecutionState.sampling(null);assertTrue(messages.getLast().contains("片段 2/2"));
        assertTrue(VideoExecutionState.current().percentage>=47);assertTrue(VideoExecutionState.current().percentage<100);
        VideoExecutionState.progress("ASSEMBLING","正在拼接");assertEquals(96,VideoExecutionState.current().percentage);
        VideoExecutionState.progress("COMMITTING","正在保存");assertEquals(99,VideoExecutionState.current().percentage);
    }
    @Test void staleTelemetryDoesNotPretendToBeCurrentStepsOrMarkSuccess(){
        var stale=json.createObjectNode().put("value",8).put("max",8).put("updatedAt",1);
        VideoExecutionState.sampling(stale);assertFalse(messages.getLast().contains("8/8"));assertTrue(messages.getLast().contains("等待实际完成"));
        assertTrue(VideoExecutionState.current().percentage<100);
        Thread.currentThread().interrupt();assertThrows(java.util.concurrent.CancellationException.class,()->VideoExecutionState.sampling(null));
    }
}
