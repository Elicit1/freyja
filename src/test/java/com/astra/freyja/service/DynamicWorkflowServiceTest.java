package com.astra.freyja.service;

import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.DynamicWorkflowOptions;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.video.DynamicWorkflowService;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DynamicWorkflowServiceTest {
    DynamicWorkflowService service;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup()throws Exception{
        var models=mock(AiModelMapper.class);
        var model=new AiModel();model.setId(2L);model.setProviderId(1L);model.setStatus(1);model.setModelType("TXT2VIDEO_REF");model.setModelCode("h3");when(models.selectById(2L)).thenReturn(model);
        service=spy(new DynamicWorkflowService(models,mock(AiProviderMapper.class),mock(CryptoUtil.class),json,mock(JdbcTemplate.class)));
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"nodeId\":\"node\",\"supported\":true,\"revision\":\"r1\",\"stages\":[\"BASE\",\"UPSCALE\",\"FACE\"],\"maxTargetPixels\":4194304}]}" )).when(service).capabilities(1L);
    }
    @Test void bothDisabledNeedNoEnhancementCapabilityAndEnabledChoicesAreFrozen(){
        for(boolean upscale:new boolean[]{false,true})for(boolean face:new boolean[]{false,true}){
            var selection=new DynamicWorkflowOptions.Selection();var options=new DynamicWorkflowOptions();
            options.setUpscale(upscale);options.setFaceRepair(face);selection.setOptions(options);
            var plan=service.resolve(selection,1L,2L,"960x544","node");
            if(!upscale&&!face){assertNull(plan);continue;}
            assertEquals(upscale,plan.getOptions().isUpscale());assertEquals(face,plan.getOptions().isFaceRepair());
            options.setUpscale(!upscale);assertEquals(upscale,plan.getOptions().isUpscale());
        }
        clearInvocations(service);var off=new DynamicWorkflowOptions.Selection();off.setOptions(new DynamicWorkflowOptions());
        assertNull(service.resolve(off,1L,2L,"960x544",null));verify(service,never()).capabilities(any());
    }
    @Test void refusesWrongNodeModelAndPixelBudget(){
        var selection=new DynamicWorkflowOptions.Selection();var options=new DynamicWorkflowOptions();options.setUpscale(true);selection.setOptions(options);
        assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"960x544","other-node"));
        assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"1920x1088",null));
        assertThrows(RuntimeException.class,()->service.resolve(selection,4L,2L,"960x544",null));
    }
    @Test void rejectsFaceOnBaseModelsWithoutThatStage()throws Exception{
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"supported\":true,\"nodeId\":\"node\",\"stages\":[\"BASE\",\"UPSCALE\"]}]}" )).when(service).capabilities(1L);
        var selection=new DynamicWorkflowOptions.Selection();var options=new DynamicWorkflowOptions();options.setFaceRepair(true);selection.setOptions(options);
        assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"960x544",null));
    }
    @Test void tilingRequiresExplicitGatewaySupport()throws Exception{
        var selection=new DynamicWorkflowOptions.Selection();var options=new DynamicWorkflowOptions();options.setUpscale(true);options.getUpscaleTiling().setEnabled(true);selection.setOptions(options);
        options.setUpscaleEngine("FREYJA");options.setUpscaleOutput("FULL_HD");
        assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"960x544",null));
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"supported\":true,\"nodeId\":\"node\",\"stages\":[\"BASE\",\"UPSCALE\"],\"freyjaUpscale\":{\"supported\":true,\"maxSteps\":8}}]}" )).when(service).capabilities(1L);
        var plan=service.resolve(selection,1L,2L,"960x544",null);
        assertEquals("1920x1080",plan.getOutputSize());assertEquals("1920x1088",plan.getProcessingSize());
        var portrait=service.resolve(selection,1L,2L,"544x960",null);assertEquals("1080x1920",portrait.getOutputSize());assertEquals("1088x1920",portrait.getProcessingSize());
        assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"640x640",null));
        options.setUpscaleSteps(9);assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"960x544",null));
    }
}
