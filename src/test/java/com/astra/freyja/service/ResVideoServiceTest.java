package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.ReferenceVideoProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.dto.video.VideoProbeInfoVO;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.impl.ResVideoServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import java.math.BigDecimal;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResVideoServiceTest {
    @Mock ResVideoMapper mapper;
    @Mock DramaMapper dramas;
    @Mock DramaShotMapper shots;
    @Mock MediaAssetService media;
    @Mock VideoFrameExtractService probe;
    @Mock AssetMediaCleanupService cleanup;
    ReferenceVideoProperties limits = new ReferenceVideoProperties();
    @BeforeAll static void tableInfo() { TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ResVideo.class); }
    ResVideoServiceImpl service() { return new ResVideoServiceImpl(mapper, dramas, shots, media, probe, limits, cleanup); }

    @Test void uploadedReferenceIsMeasuredAndRegisteredWithImmutableUrl() throws Exception {
        var info = new VideoProbeInfoVO(); info.setWidth(640); info.setHeight(360); info.setFps(24.0); info.setContainerFormat("mov,mp4,m4a"); info.setDuration(new BigDecimal("4.25")); info.setHasAudio(true);
        when(probe.probeVideoFile(any(Path.class), eq(false))).thenReturn(info);
        when(media.upload(any(), eq("video-material"))).thenReturn(AssetUploadResultVO.builder().url("http://minio/video-assets/video-material/unique.mp4").build());
        when(mapper.insert(any(ResVideo.class))).thenAnswer(call -> { ResVideo row = call.getArgument(0); row.setId(2032095628944588801L); when(mapper.selectById(row.getId())).thenReturn(row); return 1; });
        var result = service().upload(new MockMultipartFile("file", "camera.mp4", "video/mp4", new byte[]{1,2}), null, 0L, null, "SHOT_UPLOAD");
        assertEquals(2032095628944588801L, result.getId());
        assertEquals("SHOT_UPLOAD", result.getSourceType());
        assertEquals(new BigDecimal("4.25"), result.getDuration());
        assertEquals(640, result.getWidth()); assertTrue(result.getHasAudio());
        assertEquals("camera.mp4", result.getName());
    }

    @Test void rejectsNonVideoBeforeStoringAnything() {
        assertThrows(BizException.class, () -> service().upload(new MockMultipartFile("file", "bad.exe", "video/mp4", new byte[]{1}), null, 0L, null, "MANUAL_UPLOAD"));
        verifyNoInteractions(media, mapper, probe);
    }

    @Test void rejectsInvalidDecodedMetadataBeforeStoringAnything() throws Exception {
        when(probe.probeVideoFile(any(Path.class), eq(false))).thenReturn(new VideoProbeInfoVO());
        assertThrows(BizException.class, () -> service().upload(new MockMultipartFile("file", "bad.mp4", "video/mp4", new byte[]{1}), null, 0L, null, "MANUAL_UPLOAD"));
        verifyNoInteractions(media, mapper);
    }

    @Test void rejectsShotFromAnotherDrama() {
        var shot = new DramaShot(); shot.setDramaId(2L); when(shots.selectById(3L)).thenReturn(shot);
        assertThrows(BizException.class, () -> service().upload(new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[]{1}), null, 1L, 3L, "SHOT_UPLOAD"));
        verifyNoInteractions(media, mapper, probe);
    }

    @Test void editingMetadataPreservesVideoAndClearsShotAssociation() {
        var row = new ResVideo(); row.setId(1L); row.setDramaId(0L); row.setVideoUrl("http://minio/original.mp4"); row.setShotId(3L);
        when(mapper.selectById(1L)).thenReturn(row);
        var dto = new ResVideoDTO(); dto.setId(1L); dto.setName("New name"); dto.setDescription("Camera motion");
        service().update(dto);
        assertEquals("http://minio/original.mp4", row.getVideoUrl()); assertNull(row.getShotId()); assertEquals("New name", row.getName());
        verify(mapper).updateById(row);
    }

    @Test void deleteSchedulesPhysicalCleanup() {
        var row = new ResVideo(); row.setId(1L); row.setVideoUrl("http://minio/a.mp4"); when(mapper.selectById(1L)).thenReturn(row);
        service().delete(1L); verify(mapper).deleteById(1L); verify(cleanup).deleteAfterCommit("http://minio/a.mp4");
    }
}
