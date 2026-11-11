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
    @Test void installedModelsAreFetchedWithCredentialsForTheExactSelectedNode() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/h3/upscale-models", exchange -> {
            calls.incrementAndGet();
            assertEquals("node_id=node-a", exchange.getRequestURI().getRawQuery());
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"modelCode\":\"h3\",\"nodeId\":\"node-a\",\"models\":[\"installed.safetensors\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var providers = mock(AiProviderMapper.class); var crypto = mock(CryptoUtil.class);
            var provider = new AiProvider(); provider.setStatus(1); provider.setApiKey("encrypted");
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            when(providers.selectById(1L)).thenReturn(provider); when(crypto.decrypt("encrypted")).thenReturn("test-key");
            var target = spy(new DynamicWorkflowService(mock(AiModelMapper.class), providers, crypto, json, mock(JdbcTemplate.class)));
            doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"nodeId\":\"node-a\",\"supported\":true}]}" )).when(target).capabilities(1L);
            assertEquals("installed.safetensors", target.upscaleModels(1L, "h3", "node-a").path("models").get(0).asText());
            assertThrows(com.astra.freyja.common.BizException.class, () -> target.upscaleModels(1L, "h3", "node-b"));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
    DynamicWorkflowService service;AiModel baseModel;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup()throws Exception{
        var models=mock(AiModelMapper.class);
        var model=baseModel=new AiModel();model.setId(2L);model.setProviderId(1L);model.setStatus(1);model.setModelType("TXT2VIDEO_REF");model.setModelCode("h3");when(models.selectById(2L)).thenReturn(model);
        service=spy(new DynamicWorkflowService(models,mock(AiProviderMapper.class),mock(CryptoUtil.class),json,mock(JdbcTemplate.class)));
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"nodeId\":\"node\",\"supported\":true,\"revision\":\"r1\",\"stages\":[\"BASE\",\"UPSCALE\",\"FACE\"],\"maxTargetPixels\":4194304,\"freyjaUpscale\":{\"supported\":true,\"maxSteps\":8}}]}" )).when(service).capabilities(1L);
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
    @Test void newPlansEnableSecondPassContinuationOnlyWhenGatewayAdvertisesIt()throws Exception{
        var selection=new DynamicWorkflowOptions.Selection();var options=new DynamicWorkflowOptions();options.setUpscale(true);options.setUpscaleEngine("FREYJA");selection.setOptions(options);
        assertFalse(service.resolve(selection,1L,2L,"960x544","node").isSecondPassContinuity());
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"nodeId\":\"node\",\"supported\":true,\"revision\":\"r1\",\"maxTargetPixels\":4194304,\"freyjaUpscale\":{\"supported\":true,\"secondPassContinuity\":true,\"maxSteps\":8}}]}" )).when(service).capabilities(1L);
        assertTrue(service.resolve(selection,1L,2L,"960x544","node").isSecondPassContinuity());
        assertFalse(json.readValue("{\"nativeLatentInput\":true}",DynamicWorkflowOptions.Plan.class).isSecondPassContinuity());
    }
    @Test void selfLiftRequiresAdvertisedSamplerAndPreservesTheAuthoredChoice()throws Exception{
        var selection=new DynamicWorkflowOptions.Selection();var options=new DynamicWorkflowOptions();options.setUpscale(true);options.setUpscaleEngine("FREYJA");options.setUpscaleSampler("SELFLIFT");selection.setOptions(options);
        assertThrows(com.astra.freyja.common.BizException.class,()->service.resolve(selection,1L,2L,"960x544","node"));
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"nodeId\":\"node\",\"supported\":true,\"revision\":\"r1\",\"freyjaUpscale\":{\"supported\":true,\"samplers\":[\"STANDARD\",\"SELFLIFT\"],\"maxSteps\":8}}]}" )).when(service).capabilities(1L);
        assertEquals("SELFLIFT",service.resolve(selection,1L,2L,"960x544","node").getOptions().getUpscaleSampler());
        options.setUpscaleSampler("INVALID");assertThrows(com.astra.freyja.common.BizException.class,options::validate);
    }
    @Test void refusesWrongNodeModelAndPixelBudget(){
        var selection=new DynamicWorkflowOptions.Selection();var options=new DynamicWorkflowOptions();options.setUpscale(true);selection.setOptions(options);
        assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"960x544","other-node"));
        assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"1920x1088",null));
        assertThrows(RuntimeException.class,()->service.resolve(selection,4L,2L,"960x544",null));
    }
    @Test void fl2vaSupportsEnhancementsAdvertisedByGateway(){
        baseModel.setModelType("TXT2VIDEO_FIRST_LAST");
        for(boolean upscale:new boolean[]{false,true})for(boolean face:new boolean[]{false,true}){
            var selection=new DynamicWorkflowOptions.Selection();var options=new DynamicWorkflowOptions();options.setUpscale(upscale);options.setFaceRepair(face);selection.setOptions(options);
            if(!upscale&&!face){assertNull(service.resolve(selection,1L,2L,"960x544",null));continue;}
            var plan=service.resolve(selection,1L,2L,"960x544",null);
            assertNotNull(plan);assertEquals(upscale,plan.getOptions().isUpscale());assertEquals(face,plan.getOptions().isFaceRepair());
        }
    }
    @Test void rejectsFaceOnBaseModelsWithoutThatStage()throws Exception{
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"supported\":true,\"nodeId\":\"node\",\"stages\":[\"BASE\",\"UPSCALE\"]}]}" )).when(service).capabilities(1L);
        var selection=new DynamicWorkflowOptions.Selection();var options=new DynamicWorkflowOptions();options.setFaceRepair(true);selection.setOptions(options);
        assertThrows(RuntimeException.class,()->service.resolve(selection,1L,2L,"960x544",null));
    }
    @Test void tilingRequiresExplicitGatewaySupport()throws Exception{
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"supported\":true,\"nodeId\":\"node\"}]}" )).when(service).capabilities(1L);
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
    @Test void defaultUpscaleCheckpointIsFrozenWithoutReadingInstalledModels()throws Exception{
        doReturn(json.readTree("{\"models\":[{\"modelCode\":\"h3\",\"supported\":true,\"nodeId\":\"node\",\"revision\":\"r2\",\"freyjaUpscale\":{\"supported\":true,\"maxSteps\":8,\"defaultLatentModel\":\"configured.safetensors\"}}]}" )).when(service).capabilities(1L);
        var options=new DynamicWorkflowOptions();options.setUpscale(true);options.setUpscaleEngine("FREYJA");
        var selection=new DynamicWorkflowOptions.Selection();selection.setOptions(options);
        var plan=service.resolve(selection,1L,2L,"960x544","node");
        assertEquals("H3_LEARNED",plan.getOptions().getUpscaleMethod());
        assertEquals("configured.safetensors",plan.getOptions().getUpscaleLatentModel());
        assertNull(options.getUpscaleLatentModel());
        verify(service,never()).upscaleModels(any(),any(),any());
        options.setUpscaleLatentModel("historical.safetensors");
        assertEquals("historical.safetensors",service.resolve(selection,1L,2L,"960x544","node").getOptions().getUpscaleLatentModel());
        options.setUpscaleMethod("LATENT_BICUBIC");
        assertThrows(com.astra.freyja.common.BizException.class,()->service.resolve(selection,1L,2L,"960x544","node"));
    }
}
