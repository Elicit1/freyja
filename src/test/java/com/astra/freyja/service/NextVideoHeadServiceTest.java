package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.drama.NextVideoHeadRequest;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.impl.ShotFrameContinuityServiceImpl;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import io.minio.*;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NextVideoHeadServiceTest {
    @Mock private DramaShotMapper shotMapper;
    @Mock private VideoFrameExtractService videoFrameExtractService;
    @Mock private MinioClient minioClient;
    @Mock private MinioProperties minioProperties;
    @InjectMocks private ShotFrameContinuityServiceImpl service;

    private DramaShot current;
    private DramaShot next;
    private static final byte[] VIDEO = {1, 2, 3};
    private static final byte[] JPEG = {4, 5, 6};
    private static final String BUCKET = "video-assets";
    private static final String BASE = "http://127.0.0.1:9000";

    @BeforeEach
    void setUp() throws Exception {
        current = shot(101L, 9, 10);
        current.setPreviewImageUrl("current-first.jpg");
        next = shot(102L, 2, 20); // sort_order takes precedence over shot_no.
        next.setVideoUrl(BASE + "/" + BUCKET + "/videos/next.mp4");
        next.setCurrentVideoTakeId(201L);
        next.setPreviewImageUrl("configured-next-preview.jpg");
        when(shotMapper.selectById(101L)).thenReturn(current);
        when(shotMapper.selectById(102L)).thenReturn(next);
        when(shotMapper.selectList(any())).thenReturn(List.of(next, current));
        when(shotMapper.update(isNull(), any())).thenReturn(1);
        when(minioProperties.getBucketName()).thenReturn(BUCKET);
        when(minioProperties.getEndpoint()).thenReturn(BASE);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(invocation ->
                new GetObjectResponse(Headers.of(), BUCKET, null, "videos/next.mp4", new ByteArrayInputStream(VIDEO)));
        when(videoFrameExtractService.extractFirstFrame(eq(VIDEO), eq("mp4"))).thenReturn(JPEG);
    }

    private DramaShot shot(Long id, int no, int sortOrder) {
        DramaShot shot = new DramaShot();
        shot.setId(id);
        shot.setDramaId(1L);
        shot.setSceneId(1L);
        shot.setShotGroupId(10L);
        shot.setShotNo(no);
        shot.setSortOrder(sortOrder);
        shot.setGenerationMode("FIRST_LAST_FRAME");
        return shot;
    }

    @Test
    void appliesActualNextVideoHeadOnlyToCurrentEndFrame() throws Exception {
        var result = service.inheritNextVideoHead(101L, null);

        assertEquals(102L, result.getSourceShotId());
        assertEquals(201L, result.getSourceVideoTakeId());
        assertEquals(next.getVideoUrl(), result.getSourceVideoUrl());
        assertTrue(result.getHeadFrameUrl().contains("/shots/102/frames/video_first_decoded_v1_"));
        assertFalse(result.getReused());
        assertTrue(result.getApplied());
        assertNotEquals(next.getPreviewImageUrl(), result.getHeadFrameUrl());
        assertEquals("current-first.jpg", current.getPreviewImageUrl());
        ArgumentCaptor<UpdateWrapper<DramaShot>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(shotMapper).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSet().startsWith("end_frame_image_url="));
        assertFalse(update.getValue().getSqlSet().contains(","));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(result.getHeadFrameUrl()));
        verify(shotMapper, never()).updateById(any(DramaShot.class));
        verify(videoFrameExtractService).extractFirstFrame(VIDEO, "mp4");
        verify(videoFrameExtractService, never()).extractLastFrame(any(), any());
        ArgumentCaptor<GetObjectArgs> downloaded = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(downloaded.capture());
        assertEquals("videos/next.mp4", downloaded.getValue().object());
    }

    @Test
    void reusesFirstFrameObjectFromMatchingVideoVersion() throws Exception {
        var stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(200L);
        when(minioClient.statObject(any())).thenReturn(stat);
        var result = service.inheritNextVideoHead(101L, null);
        assertTrue(result.getReused());
        verify(minioClient, never()).getObject(any());
        verify(videoFrameExtractService, never()).extractFirstFrame(any(), any());
    }

    @Test
    void forceExtractionBypassesVersionCache() throws Exception {
        var request = new NextVideoHeadRequest();
        request.setForceExtract(true);
        assertFalse(service.inheritNextVideoHead(101L, request).getReused());
        verify(minioClient, never()).statObject(any());
        verify(videoFrameExtractService).extractFirstFrame(VIDEO, "mp4");
    }

    @Test
    void cacheKeyChangesWhenVideoTakeOrUrlChanges() throws Exception {
        service.inheritNextVideoHead(101L, null);
        next.setCurrentVideoTakeId(202L);
        service.inheritNextVideoHead(101L, null);
        next.setVideoUrl(BASE + "/" + BUCKET + "/videos/new.mp4");
        service.inheritNextVideoHead(101L, null);
        ArgumentCaptor<PutObjectArgs> uploads = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(3)).putObject(uploads.capture());
        assertEquals(3, uploads.getAllValues().stream().map(PutObjectArgs::object).distinct().count());
    }

    @Test
    void refusesLastShotWithoutCrossingGroupBoundary() {
        when(shotMapper.selectList(any())).thenReturn(List.of(current));
        assertEquals(400, assertThrows(BizException.class, () -> service.inheritNextVideoHead(101L, null)).getCode());
        verify(shotMapper, never()).update(isNull(), any());
    }

    @Test
    void doesNotSkipNextShotWithoutVideo() {
        next.setVideoUrl(null);
        DramaShot later = shot(103L, 3, 30);
        later.setVideoUrl(BASE + "/" + BUCKET + "/videos/later.mp4");
        when(shotMapper.selectList(any())).thenReturn(List.of(current, next, later));
        BizException error = assertThrows(BizException.class, () -> service.inheritNextVideoHead(101L, null));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("先生成下一镜视频"));
        verify(shotMapper, never()).update(isNull(), any());
    }

    @Test
    void rejectsReferenceMode() {
        current.setGenerationMode("REFERENCE_MODE");
        assertEquals(400, assertThrows(BizException.class, () -> service.inheritNextVideoHead(101L, null)).getCode());
        verify(shotMapper, never()).update(isNull(), any());
    }

    @Test
    void detectsSourceVideoChangedDuringExtraction() {
        DramaShot changed = shot(102L, 2, 20);
        changed.setVideoUrl(next.getVideoUrl());
        changed.setCurrentVideoTakeId(202L);
        when(shotMapper.selectById(102L)).thenReturn(next, changed);
        assertEquals(409, assertThrows(BizException.class, () -> service.inheritNextVideoHead(101L, null)).getCode());
        verify(shotMapper, never()).update(isNull(), any());
    }

    @Test
    void detectsInsertionDuringExtraction() {
        DramaShot inserted = shot(103L, 3, 15);
        inserted.setVideoUrl(next.getVideoUrl());
        when(shotMapper.selectList(any())).thenReturn(List.of(current, next), List.of(current, inserted, next));
        assertEquals(409, assertThrows(BizException.class, () -> service.inheritNextVideoHead(101L, null)).getCode());
        verify(shotMapper, never()).update(isNull(), any());
    }

    @Test
    void refusesNonProjectVideoPath() {
        next.setVideoUrl("https://other.example/videos/next.mp4");
        assertEquals(400, assertThrows(BizException.class, () -> service.inheritNextVideoHead(101L, null)).getCode());
        verify(shotMapper, never()).update(isNull(), any());
    }

    @Test
    void failedExtractionPreservesCurrentEndFrame() {
        current.setEndFrameImageUrl("original-end.jpg");
        when(videoFrameExtractService.extractFirstFrame(any(), any())).thenReturn(null);
        assertEquals(500, assertThrows(BizException.class, () -> service.inheritNextVideoHead(101L, null)).getCode());
        assertEquals("original-end.jpg", current.getEndFrameImageUrl());
        verify(shotMapper, never()).update(isNull(), any());
    }

    @Test
    void missingOrUngroupedCurrentShotIsRejected() {
        assertEquals(400, assertThrows(BizException.class, () -> service.inheritNextVideoHead(null, null)).getCode());
        assertEquals(404, assertThrows(BizException.class, () -> service.inheritNextVideoHead(999L, null)).getCode());
        current.setShotGroupId(null);
        assertEquals(400, assertThrows(BizException.class, () -> service.inheritNextVideoHead(101L, null)).getCode());
    }
}
