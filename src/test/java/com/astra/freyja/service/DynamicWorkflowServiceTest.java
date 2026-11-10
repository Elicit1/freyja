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
    DynamicWorkflowService service;DynamicWorkflowPresetMapper presets;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup()throws Exception{
        presets=mock(DynamicWorkflowPresetMapper.class);var models=mock(AiModelMapper.class);
        var model=new AiModel();model.setId(2L);model.setProviderId(1L);model.setStatus(1);model.setModelType("TXT2VIDEO_REF");model.setModelCode("h3");when(models.selectById(2L)).thenReturn(model);
        service=spy(new DynamicWorkflowService(presets,models,mock(AiProviderMapper.class),mock(CryptoUtil.class),json,mock(JdbcTemplate.class)));
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"nodeId\":\"node\",\"supported\":true,\"revision\":\"r1\",\"stages\":[\"BASE\",\"UPSCALE\",\"FACE\"],\"maxTargetPixels\":4194304}]}" )).when(service).capabilities(1L);
    }
    @Test void resolvesIndependentSwitchesAndFreezesPresetSettings()throws Exception{
        var preset=new DynamicWorkflowPreset();preset.setId(2032095628944588803L);preset.setProviderId(1L);preset.setModelId(2L);preset.setVersion(7);preset.setStatus(1);preset.setName("高清人物");preset.setOptionsJson("{\"upscale\":true,\"faceRepair\":true}");when(presets.selectById(preset.getId())).thenReturn(preset);
        var selection=new DynamicWorkflowOptions.Selection();selection.setPresetId(preset.getId());selection.setVersion(7);
        var plan=service.resolve(selection,1L,2L,"960x544","node");assertEquals("1920x1088",plan.getOutputSize());assertTrue(plan.getOptions().isFaceRepair());assertEquals(7,plan.getVersion());
        preset.setOptionsJson("{\"upscale\":false}");assertTrue(plan.getOptions().isUpscale());
        selection.setVersion(6);assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"960x544",null));
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
