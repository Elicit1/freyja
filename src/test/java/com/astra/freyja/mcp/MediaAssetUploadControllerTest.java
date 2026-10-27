package com.astra.freyja.mcp;

import com.astra.freyja.common.GlobalExceptionHandler;
import com.astra.freyja.controller.ResAudioController;
import com.astra.freyja.controller.ResVideoController;
import com.astra.freyja.dao.ResAudioMapper;
import com.astra.freyja.dao.ResVideoMapper;
import com.astra.freyja.dto.video.VideoProbeInfoVO;
import com.astra.freyja.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Real multipart binding and transactional services: one confirmation persists all form fields. */
@SpringJUnitConfig(McpBusinessDatabaseTest.Config.class)
@TestPropertySource(properties = "spring.ai.mcp.server.enabled=true")
class MediaAssetUploadControllerTest {
    @Autowired AutowireCapableBeanFactory factory;
    @Autowired ResAudioService audios;
    @Autowired ResVideoService videos;
    @Autowired ResAudioMapper audioMapper;
    @Autowired ResVideoMapper videoMapper;
    @Autowired VideoFrameExtractService probe;
    @Autowired AssetMediaCleanupService cleanup;
    MockMvc mvc;
    McpBusinessDatabaseTest fixture;

    @BeforeEach void setup() {
        fixture = new McpBusinessDatabaseTest(); factory.autowireBean(fixture); fixture.setup(); reset(probe, cleanup);
        mvc = MockMvcBuilders.standaloneSetup(new ResAudioController(audios), new ResVideoController(videos))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    void metadata(String format, boolean video) throws Exception {
        var info = new VideoProbeInfoVO(); info.setContainerFormat(format); info.setHasAudio(true); info.setDuration(new BigDecimal("4.25"));
        if (video) { info.setWidth(640); info.setHeight(360); info.setFps(24.0); }
        when(probe.probeVideoFile(any(Path.class), eq(false))).thenReturn(info);
        when(fixture.storage.bucketExists(any())).thenReturn(true);
    }
    @Test void oneVideoUploadSavesDescriptionSortingStatusAndRemark() throws Exception {
        metadata("mov,mp4,m4a", true);
        var result = mvc.perform(multipart("/res/video/upload").file(new MockMultipartFile("file", "camera.mp4", "video/mp4", new byte[]{1, 2}))
                .param("name", "雨夜运镜").param("dramaId", McpBusinessDatabaseTest.ROOT).param("description", "低机位缓慢跟拍")
                .param("sortOrder", "7").param("status", "0").param("remark", "保留原始声轨")).andReturn();
        assertEquals(200, fixture.json.mapper().readTree(result.getResponse().getContentAsString()).get("code").intValue());
        var row = videoMapper.selectList(null).getFirst();
        assertEquals("雨夜运镜", row.getName()); assertEquals("低机位缓慢跟拍", row.getDescription());
        assertEquals(7, row.getSortOrder()); assertEquals(0, row.getStatus()); assertEquals("保留原始声轨", row.getRemark());
        assertEquals(McpBusinessDatabaseTest.PROJECT, row.getDramaId());
    }
    @Test void oneAudioUploadSavesCategoryTextAndOptionalMetadata() throws Exception {
        metadata("wav", false);
        var result = mvc.perform(multipart("/res/audio/upload").file(new MockMultipartFile("file", "voice.wav", "audio/wav", new byte[]{1, 2}))
                .param("audioKind", "DIALOGUE").param("name", "对白参考").param("text", "你好").param("language", "zh")
                .param("description", "轻声对白").param("remark", "室内录音").param("sortOrder", "3")).andReturn();
        assertEquals(200, fixture.json.mapper().readTree(result.getResponse().getContentAsString()).get("code").intValue());
        var row = audioMapper.selectList(null).getFirst(); assertEquals(0L, row.getDramaId());
        assertEquals("DIALOGUE", row.getAudioKind()); assertEquals("你好", row.getText()); assertEquals("zh", row.getLanguage());
        assertEquals("轻声对白", row.getDescription()); assertEquals("室内录音", row.getRemark()); assertEquals(3, row.getSortOrder());
    }
    @Test void invalidMetadataRollsBackRegistrationAndSchedulesOrphanCleanup() throws Exception {
        metadata("mov,mp4,m4a", true);
        var result = mvc.perform(multipart("/res/video/upload").file(new MockMultipartFile("file", "camera.mp4", "video/mp4", new byte[]{1, 2}))
                .param("name", "测试").param("status", "9")).andReturn();
        assertEquals(400, fixture.json.mapper().readTree(result.getResponse().getContentAsString()).get("code").intValue());
        assertEquals(0, fixture.count("res_video")); verify(cleanup).deleteUnreferencedNow(anyString());
    }
}
