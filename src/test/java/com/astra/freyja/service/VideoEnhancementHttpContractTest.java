package com.astra.freyja.service;

import com.astra.freyja.config.JacksonConfig;
import com.astra.freyja.controller.VideoEnhancementController;
import com.astra.freyja.service.video.DynamicWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VideoEnhancementHttpContractTest {
    @Test void capabilityCatalogKeepsItsJsonShapeAcrossTheHttpBoundary() throws Exception {
        var service = mock(DynamicWorkflowService.class);
        when(service.capabilities(2096886255638646785L)).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"schemaVersion":1,"models":[
                  {"modelCode":"minimax-h3-ref2va","nodeId":"default","supported":true,"stages":["BASE","UPSCALE","FACE"]},
                  {"modelCode":"minimax-h3-fl2va","nodeId":"default","supported":false,"stages":[]}
                ]}
                """));
        var builder = JsonMapper.builder();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        var mvc = MockMvcBuilders.standaloneSetup(new VideoEnhancementController(service, new com.fasterxml.jackson.databind.ObjectMapper(),mock(com.astra.freyja.service.video.ContinuationSegmentEnhancementService.class),mock(com.astra.freyja.service.video.VideoRenderBatchService.class)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build())).build();
        mvc.perform(get("/production/video-enhancements/capabilities").param("providerId", "2096886255638646785"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.models[0].modelCode").value("minimax-h3-ref2va"))
                .andExpect(jsonPath("$.data.models[0].supported").value(true))
                .andExpect(jsonPath("$.data.models[0].stages[2]").value("FACE"))
                .andExpect(jsonPath("$.data.models[1].supported").value(false));
    }
}
