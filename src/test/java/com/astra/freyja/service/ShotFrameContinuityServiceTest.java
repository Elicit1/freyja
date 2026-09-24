package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.drama.PreviousVideoTailRequest;
import com.astra.freyja.dto.drama.PreviousVideoTailVO;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.impl.ShotFrameContinuityServiceImpl;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("镜头视听连续性服务测试 (按需提取上一镜视频尾帧)")
class ShotFrameContinuityServiceTest {

    @Mock
    private DramaShotMapper shotMapper;

    @Mock
    private VideoFrameExtractService videoFrameExtractService;

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioProperties minioProperties;

    @InjectMocks
    private ShotFrameContinuityServiceImpl continuityService;

    private static final String BUCKET_NAME = "video-assets";
    private static final String MINIO_ENDPOINT = "http://127.0.0.1:9000";

    @BeforeEach
    void setUp() {
        when(minioProperties.getBucketName()).thenReturn(BUCKET_NAME);
        when(minioProperties.getEndpoint()).thenReturn(MINIO_ENDPOINT);
        when(minioProperties.getExternalEndpoint()).thenReturn(MINIO_ENDPOINT);
    }

    private DramaShot buildShot(Long id, Long groupId, int shotNo, int sortOrder, String videoUrl) {
        DramaShot shot = new DramaShot();
        shot.setId(id);
        shot.setDramaId(1L);
        shot.setEpisodeId(1L);
        shot.setSceneId(1L);
        shot.setShotGroupId(groupId);
        shot.setShotNo(shotNo);
        shot.setShotName("S01-" + shotNo);
        shot.setSortOrder(sortOrder);
        shot.setVideoUrl(videoUrl);
        return shot;
    }

    @Test
    @DisplayName("1. 当前分镜不存在抛出 404")
    void testCurrentShotNotFound() {
        when(shotMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () ->
                continuityService.inheritPreviousVideoTail(999L, new PreviousVideoTailRequest())
        );
        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("当前分镜不存在"));
    }

    @Test
    @DisplayName("2. 当前分镜未归属连续镜头组抛出 400")
    void testCurrentShotNoGroupId() {
        DramaShot shot = buildShot(100L, null, 1, 1, null);
        when(shotMapper.selectById(100L)).thenReturn(shot);

        BizException ex = assertThrows(BizException.class, () ->
                continuityService.inheritPreviousVideoTail(100L, new PreviousVideoTailRequest())
        );
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("未归属连续镜头组"));
    }

    @Test
    @DisplayName("3. 当前分镜是组内第一镜抛出 400")
    void testCurrentShotIsFirstInGroup() {
        DramaShot shot1 = buildShot(101L, 10L, 1, 1, null);
        DramaShot shot2 = buildShot(102L, 10L, 2, 2, null);

        when(shotMapper.selectById(101L)).thenReturn(shot1);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        BizException ex = assertThrows(BizException.class, () ->
                continuityService.inheritPreviousVideoTail(101L, new PreviousVideoTailRequest())
        );
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("没有可引用的上一镜"));
    }

    @Test
    @DisplayName("4. 直接上一镜没有生成视频抛出 400")
    void testPreviousShotHasNoVideo() {
        DramaShot shot1 = buildShot(101L, 10L, 1, 1, null); // no video
        DramaShot shot2 = buildShot(102L, 10L, 2, 2, null);

        when(shotMapper.selectById(102L)).thenReturn(shot2);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        BizException ex = assertThrows(BizException.class, () ->
                continuityService.inheritPreviousVideoTail(102L, new PreviousVideoTailRequest())
        );
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("请先生成上一镜视频"));
    }

    @Test
    @DisplayName("5. 镜头序号不连续时按稳定排序正确定位直接前驱")
    void testDiscontinuousShotNoOrdering() {
        // shotNo 不连续: 1, 5, 8; 当前是 8，直接前驱应该是 5
        DramaShot shot1 = buildShot(101L, 10L, 1, 1, "http://127.0.0.1:9000/video-assets/video1.mp4");
        DramaShot shot5 = buildShot(105L, 10L, 5, 5, "http://127.0.0.1:9000/video-assets/video5.mp4");
        shot5.setLastFrameUrl("http://127.0.0.1:9000/video-assets/tail5.jpg");
        shot5.setLastFrameSourceVideoUrl("http://127.0.0.1:9000/video-assets/video5.mp4");

        DramaShot shot8 = buildShot(108L, 10L, 8, 8, null);

        when(shotMapper.selectById(108L)).thenReturn(shot8);
        when(shotMapper.selectById(105L)).thenReturn(shot5);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot8, shot1, shot5));

        PreviousVideoTailVO vo = continuityService.inheritPreviousVideoTail(108L, new PreviousVideoTailRequest());

        assertNotNull(vo);
        assertEquals(105L, vo.getSourceShotId());
        assertEquals(5, vo.getSourceShotNo());
        assertTrue(vo.getReused());
        assertEquals("http://127.0.0.1:9000/video-assets/tail5.jpg", vo.getTailFrameUrl());
    }

    @Test
    @DisplayName("6. 视频 URL 指向非本 MinIO bucket 时拦截并抛出 400")
    void testVideoUrlNotFromProjectMinio() {
        DramaShot shot1 = buildShot(101L, 10L, 1, 1, "https://external-domain.com/other-bucket/video.mp4");
        DramaShot shot2 = buildShot(102L, 10L, 2, 2, null);

        when(shotMapper.selectById(102L)).thenReturn(shot2);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        BizException ex = assertThrows(BizException.class, () ->
                continuityService.inheritPreviousVideoTail(102L, new PreviousVideoTailRequest())
        );
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不支持服务端尾帧提取"));
    }

    @Test
    @DisplayName("7. 存在有效尾帧缓存且视频版本一致时直接复用不调用 ffmpeg")
    void testReuseCachedLastFrame() {
        String videoUrl = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/takes/video_1.mp4";
        String cachedTail = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/frames/video_tail_1.jpg";

        DramaShot shot1 = buildShot(101L, 10L, 1, 1, videoUrl);
        shot1.setLastFrameUrl(cachedTail);
        shot1.setLastFrameSourceVideoUrl(videoUrl);

        DramaShot shot2 = buildShot(102L, 10L, 2, 2, null);

        when(shotMapper.selectById(102L)).thenReturn(shot2);
        when(shotMapper.selectById(101L)).thenReturn(shot1);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        PreviousVideoTailVO vo = continuityService.inheritPreviousVideoTail(102L, new PreviousVideoTailRequest(false, 300));

        assertNotNull(vo);
        assertTrue(vo.getReused());
        assertEquals(cachedTail, vo.getTailFrameUrl());
        assertEquals("PREVIOUS_VIDEO_TAIL", shot2.getFirstFrameSourceType());
        assertEquals(101L, shot2.getFirstFrameSourceShotId());
        assertEquals(videoUrl, shot2.getFirstFrameSourceVideoUrl());

        // 验证不调用 extractLastFrame
        verify(videoFrameExtractService, never()).extractLastFrame(any(), any(), any());
    }

    @Test
    @DisplayName("8. 缓存版本与视频 URL 不匹配时失效重新提取")
    void testVersionMismatchReExtract() throws Exception {
        String oldVideoUrl = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/takes/video_old.mp4";
        String newVideoUrl = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/takes/video_new.mp4";
        String oldTail = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/frames/video_tail_old.jpg";

        DramaShot shot1 = buildShot(101L, 10L, 1, 1, newVideoUrl);
        shot1.setLastFrameUrl(oldTail);
        shot1.setLastFrameSourceVideoUrl(oldVideoUrl); // 版本不匹配

        DramaShot shot2 = buildShot(102L, 10L, 2, 2, null);

        when(shotMapper.selectById(102L)).thenReturn(shot2);
        when(shotMapper.selectById(101L)).thenReturn(shot1);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        // Mock MinIO getObject
        byte[] fakeVideoBytes = "fake-video-bytes".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse getResponse = new GetObjectResponse(
                Headers.of(), BUCKET_NAME, null, "projects/1/shots/101/takes/video_new.mp4",
                new ByteArrayInputStream(fakeVideoBytes)
        );
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getResponse);

        // Mock extractLastFrame
        byte[] fakeFrameBytes = "fake-jpg-bytes".getBytes(StandardCharsets.UTF_8);
        when(videoFrameExtractService.extractLastFrame(any(), eq("mp4"), any())).thenReturn(fakeFrameBytes);

        PreviousVideoTailVO vo = continuityService.inheritPreviousVideoTail(102L, new PreviousVideoTailRequest(false, 300));

        assertNotNull(vo);
        assertFalse(vo.getReused());
        assertTrue(vo.getTailFrameUrl().contains("video_tail_"));
        assertEquals(newVideoUrl, shot1.getLastFrameSourceVideoUrl());
        assertEquals("PREVIOUS_VIDEO_TAIL", shot2.getFirstFrameSourceType());

        verify(videoFrameExtractService).extractLastFrame(any(), eq("mp4"), any());
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("9. forceExtract=true 时即使缓存有效也强制重新提取")
    void testForceExtractBypassesCache() throws Exception {
        String videoUrl = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/takes/video_1.mp4";
        String cachedTail = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/frames/video_tail_1.jpg";

        DramaShot shot1 = buildShot(101L, 10L, 1, 1, videoUrl);
        shot1.setLastFrameUrl(cachedTail);
        shot1.setLastFrameSourceVideoUrl(videoUrl);

        DramaShot shot2 = buildShot(102L, 10L, 2, 2, null);

        when(shotMapper.selectById(102L)).thenReturn(shot2);
        when(shotMapper.selectById(101L)).thenReturn(shot1);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        byte[] fakeVideoBytes = "fake-video-bytes".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse getResponse = new GetObjectResponse(
                Headers.of(), BUCKET_NAME, null, "projects/1/shots/101/takes/video_1.mp4",
                new ByteArrayInputStream(fakeVideoBytes)
        );
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getResponse);

        byte[] newFrameBytes = "new-extracted-jpg-bytes".getBytes(StandardCharsets.UTF_8);
        when(videoFrameExtractService.extractLastFrame(any(), eq("mp4"), eq(300))).thenReturn(newFrameBytes);

        PreviousVideoTailVO vo = continuityService.inheritPreviousVideoTail(102L, new PreviousVideoTailRequest(true, 300));

        assertNotNull(vo);
        assertFalse(vo.getReused());
        verify(videoFrameExtractService).extractLastFrame(any(), eq("mp4"), eq(300));
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("10. ffmpeg 提取失败时抛出 500 且不篡改当前镜首帧")
    void testExtractFailureThrows500AndPreservesFirstFrame() throws Exception {
        String videoUrl = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/takes/video_1.mp4";

        DramaShot shot1 = buildShot(101L, 10L, 1, 1, videoUrl);
        DramaShot shot2 = buildShot(102L, 10L, 2, 2, null);
        shot2.setPreviewImageUrl("http://127.0.0.1:9000/original_first.jpg");

        when(shotMapper.selectById(102L)).thenReturn(shot2);
        when(shotMapper.selectById(101L)).thenReturn(shot1);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        byte[] fakeVideoBytes = "fake-video-bytes".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse getResponse = new GetObjectResponse(
                Headers.of(), BUCKET_NAME, null, "projects/1/shots/101/takes/video_1.mp4",
                new ByteArrayInputStream(fakeVideoBytes)
        );
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getResponse);

        // ffmpeg 返回 null 模拟失败
        when(videoFrameExtractService.extractLastFrame(any(), any(), any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () ->
                continuityService.inheritPreviousVideoTail(102L, new PreviousVideoTailRequest(true, 300))
        );
        assertEquals(500, ex.getCode());
        assertTrue(ex.getMessage().contains("尾帧提取失败"));

        // 原首帧保持不变
        assertEquals("http://127.0.0.1:9000/original_first.jpg", shot2.getPreviewImageUrl());
    }

    @Test
    @DisplayName("11. 抽帧期间分镜发生重排冲突时抛出 409")
    void testConcurrentReorderConflictThrows409() throws Exception {
        String videoUrl = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/takes/video_1.mp4";

        DramaShot shot1 = buildShot(101L, 10L, 1, 1, videoUrl);
        DramaShot shot2 = buildShot(102L, 10L, 2, 2, null);

        when(shotMapper.selectById(102L)).thenReturn(shot2);
        when(shotMapper.selectById(101L)).thenReturn(shot1);

        // 第一次查询列表：shot1 -> shot2
        // 第二次查询列表（抽帧期间插播或重排，shot3 插到了 shot1 与 shot2 之间）
        DramaShot shotIntervening = buildShot(103L, 10L, 1, 1, "video3.mp4");
        when(shotMapper.selectList(any()))
                .thenReturn(List.of(shot1, shot2))
                .thenReturn(List.of(shot1, shotIntervening, shot2));

        byte[] fakeVideoBytes = "fake-video-bytes".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse getResponse = new GetObjectResponse(
                Headers.of(), BUCKET_NAME, null, "projects/1/shots/101/takes/video_1.mp4",
                new ByteArrayInputStream(fakeVideoBytes)
        );
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getResponse);

        byte[] frameBytes = "jpg-bytes".getBytes(StandardCharsets.UTF_8);
        when(videoFrameExtractService.extractLastFrame(any(), any(), any())).thenReturn(frameBytes);

        BizException ex = assertThrows(BizException.class, () ->
                continuityService.inheritPreviousVideoTail(102L, new PreviousVideoTailRequest(true, 300))
        );
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("镜头顺序已变化"));
    }

    @Test
    @DisplayName("12. 并发多次引用同一来源分镜时加锁互斥只抽帧一次")
    void testConcurrentRequestsOnlyExtractOnce() throws Exception {
        String videoUrl = "http://127.0.0.1:9000/video-assets/projects/1/shots/101/takes/video_1.mp4";

        DramaShot shot1 = buildShot(101L, 10L, 1, 1, videoUrl);
        DramaShot shot2 = buildShot(102L, 10L, 2, 2, null);

        when(shotMapper.selectById(102L)).thenReturn(shot2);
        when(shotMapper.selectById(101L)).thenReturn(shot1);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        byte[] fakeVideoBytes = "fake-video-bytes".getBytes(StandardCharsets.UTF_8);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(inv ->
                new GetObjectResponse(Headers.of(), BUCKET_NAME, null, "path", new ByteArrayInputStream(fakeVideoBytes))
        );

        byte[] frameBytes = "jpg-bytes".getBytes(StandardCharsets.UTF_8);
        AtomicInteger extractCount = new AtomicInteger(0);
        when(videoFrameExtractService.extractLastFrame(any(), any(), any())).thenAnswer(inv -> {
            extractCount.incrementAndGet();
            Thread.sleep(50); // 模拟耗时
            return frameBytes;
        });

        int threads = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<PreviousVideoTailVO>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return continuityService.inheritPreviousVideoTail(102L, new PreviousVideoTailRequest(false, 300));
            }));
        }

        latch.countDown();
        for (Future<PreviousVideoTailVO> f : futures) {
            PreviousVideoTailVO vo = f.get();
            assertNotNull(vo);
            assertNotNull(vo.getTailFrameUrl());
        }
        executor.shutdown();

        // 验证实际抽帧只执行了一次，后续并发请求复用了缓存
        assertEquals(1, extractCount.get());
    }
}
