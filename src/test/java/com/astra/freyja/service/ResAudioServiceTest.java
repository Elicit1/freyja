package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefAudioDTO;
import com.astra.freyja.util.ShotAudioReferenceValidator;
import java.util.List;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.dto.video.VideoProbeInfoVO;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.impl.ResAudioServiceImpl;
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
class ResAudioServiceTest {
    @Mock ResAudioMapper mapper;
    @Mock DramaMapper dramas;
    @Mock DramaShotMapper shots;
    @Mock MediaAssetService media;
    @Mock VideoFrameExtractService probe;
    @Mock AssetMediaCleanupService cleanup;
    @BeforeAll static void tableInfo() { TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ResAudio.class); }
    ResAudioServiceImpl service() { return new ResAudioServiceImpl(mapper, dramas, shots, media, probe, cleanup); }

    @Test void uploadedReferenceIsMeasuredAndRegisteredWithImmutableUrl() throws Exception {
        var info = new VideoProbeInfoVO(); info.setContainerFormat("wav"); info.setDuration(new BigDecimal("4.25")); info.setHasAudio(true);
        when(probe.probeVideoFile(any(Path.class), eq(false))).thenReturn(info);
        when(media.upload(any(), eq("audio-material"))).thenReturn(AssetUploadResultVO.builder().url("http://minio/audio-assets/audio-material/unique.wav").build());
        when(mapper.insert(any(ResAudio.class))).thenAnswer(call -> { ResAudio row = call.getArgument(0); row.setId(2032095628944588801L); when(mapper.selectById(row.getId())).thenReturn(row); return 1; });
        var result = service().upload(new MockMultipartFile("file", "camera.wav", "audio/wav", new byte[]{1,2}), null, 0L, null, "SHOT_UPLOAD", "MUSIC", null, null);
        assertEquals(2032095628944588801L, result.getId());
        assertEquals("SHOT_UPLOAD", result.getSourceType());
        assertEquals(new BigDecimal("4.25"), result.getDuration());
        assertEquals("MUSIC", result.getAudioKind());
        assertEquals("camera.wav", result.getName());
    }

    @Test void rejectsUnsupportedAudioBeforeStoringAnything() {
        assertThrows(BizException.class, () -> service().upload(new MockMultipartFile("file", "bad.exe", "audio/wav", new byte[]{1}), null, 0L, null, "MANUAL_UPLOAD", "MUSIC", null, null));
        verifyNoInteractions(media, mapper, probe);
    }

    @Test void rejectsInvalidDecodedMetadataBeforeStoringAnything() throws Exception {
        when(probe.probeVideoFile(any(Path.class), eq(false))).thenReturn(new VideoProbeInfoVO());
        assertThrows(BizException.class, () -> service().upload(new MockMultipartFile("file", "bad.wav", "audio/wav", new byte[]{1}), null, 0L, null, "MANUAL_UPLOAD", "MUSIC", null, null));
        verifyNoInteractions(media, mapper);
    }

    @Test void rejectsShotFromAnotherDrama() {
        var shot = new DramaShot(); shot.setDramaId(2L); when(shots.selectById(3L)).thenReturn(shot);
        assertThrows(BizException.class, () -> service().upload(new MockMultipartFile("file", "a.wav", "audio/wav", new byte[]{1}), null, 1L, 3L, "SHOT_UPLOAD", "MUSIC", null, null));
        verifyNoInteractions(media, mapper, probe);
    }

    @Test void editingMetadataPreservesAudioAndClearsShotAssociation() {
        var row = new ResAudio(); row.setId(1L); row.setDramaId(0L); row.setAudioUrl("http://minio/original.wav"); row.setShotId(3L);
        when(mapper.selectById(1L)).thenReturn(row);
        var dto = new ResAudioDTO(); dto.setId(1L); dto.setName("New name"); dto.setAudioKind("MUSIC"); dto.setDescription("Camera motion");
        service().update(dto);
        assertEquals("http://minio/original.wav", row.getAudioUrl()); assertNull(row.getShotId()); assertEquals("New name", row.getName());
        verify(mapper).updateById(row);
    }

    @Test void deleteSchedulesPhysicalCleanup() {
        var row = new ResAudio(); row.setId(1L); row.setAudioUrl("http://minio/a.wav"); when(mapper.selectById(1L)).thenReturn(row);
        service().delete(1L); verify(mapper).deleteById(1L); verify(cleanup).deleteAfterCommit("http://minio/a.wav");
    }

    private ResAudio asset(long id, long dramaId, String url, String duration) {
        var row = new ResAudio(); row.setId(id); row.setDramaId(dramaId); row.setAudioUrl(url);
        row.setStatus(1); row.setAudioKind("MUSIC"); row.setDuration(new BigDecimal(duration));
        return row;
    }
    private ShotRefAudioDTO reference(long id, String url) {
        return ShotRefAudioDTO.builder().resAudioId(id).audioUrl(url).referenceType("reference").build();
    }
    @Test void libraryReferenceNeedsNoCharacterAndResolvesMeasuredDuration() {
        long id = 2032095628944588801L;
        when(mapper.selectById(id)).thenReturn(asset(id, 0L, "audio.wav", "4.25"));
        var ref = reference(id, "audio.wav"); ref.setDuration(BigDecimal.TWO);
        service().validateReferences(42L, List.of(ref));
        assertNull(ref.getCharacterId()); assertNull(ref.getAudioId());
        assertEquals(new BigDecimal("4.25"), ref.getDuration()); assertEquals("MUSIC", ref.getAudioKind());
    }
    @Test void rejectsUnavailableOrForeignLibraryAssetsAndForgedUrls() {
        var row = asset(1L, 42L, "immutable.wav", "4"); when(mapper.selectById(1L)).thenReturn(row);
        assertThrows(BizException.class, () -> service().validateReferences(43L, List.of(reference(1L, "immutable.wav"))));
        assertThrows(BizException.class, () -> service().validateReferences(42L, List.of(reference(1L, "forged.wav"))));
        row.setStatus(0);
        assertThrows(BizException.class, () -> service().validateReferences(42L, List.of(reference(1L, "immutable.wav"))));
    }
    @Test void actualDurationCannotBeBypassedWithShortClientMetadata() {
        when(mapper.selectById(1L)).thenReturn(asset(1L, 0L, "a.wav", "16"));
        var ref = reference(1L, "a.wav"); ref.setDuration(BigDecimal.TWO);
        assertThrows(BizException.class, () -> service().validateReferences(42L, List.of(ref)));
    }
    @Test void mixedLibraryAndDubbingSelectionSharesTotalDurationLimit() {
        when(mapper.selectById(1L)).thenReturn(asset(1L, 0L, "music.wav", "9"));
        var voice = ShotRefAudioDTO.builder().audioId(2L).characterId(3L).audioUrl("voice.wav")
                .duration(new BigDecimal("7")).referenceType("reference").build();
        assertThrows(BizException.class, () -> service().validateReferences(42L, List.of(reference(1L, "music.wav"), voice)));
    }
    @Test void relationshipIsNeverInferredFromMaterialCategory() {
        for (String relationship : ShotAudioReferenceValidator.REFERENCE_TYPES) {
            when(mapper.selectById(1L)).thenReturn(asset(1L, 0L, "music.wav", "4"));
            var ref = reference(1L, "music.wav"); ref.setReferenceType(relationship);
            service().validateReferences(42L, List.of(ref));
            assertEquals(relationship, ref.getReferenceType());
        }
    }
    @Test void rejectsAmbiguousDubbingAndLibraryIdentity() {
        var ref = reference(1L, "music.wav"); ref.setAudioId(2L); ref.setCharacterId(3L);
        assertThrows(BizException.class, () -> service().validateReferences(42L, List.of(ref)));
        verifyNoInteractions(mapper);
    }
    @Test void rejectsMissingLibraryAsset() {
        assertThrows(BizException.class, () -> service().validateReferences(42L, List.of(reference(1L, "missing.wav"))));
    }
    @Test void validFileExtensionCannotHideAnInvalidAudioStream() throws Exception {
        var info = new VideoProbeInfoVO(); info.setContainerFormat("wav"); info.setDuration(new BigDecimal("4"));
        info.setHasAudio(false); when(probe.probeVideoFile(any(Path.class), eq(false))).thenReturn(info);
        assertThrows(BizException.class, () -> service().upload(new MockMultipartFile("file", "empty.wav", "audio/wav", new byte[]{1}), null, 0L, null, "MANUAL_UPLOAD", "MUSIC", null, null));
        verifyNoInteractions(media, mapper);
    }
}
