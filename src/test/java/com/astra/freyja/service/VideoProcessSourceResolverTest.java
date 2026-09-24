package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.video.ResolvedVideoProcessSource;
import com.astra.freyja.dto.video.VideoProcessSubmitDTO;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.impl.VideoProcessSourceResolverImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class VideoProcessSourceResolverTest {

    @Mock
    private DramaShotMapper shotMapper;

    @Mock
    private DramaShotVideoTakeMapper videoTakeMapper;

    @Mock
    private DramaMapper dramaMapper;

    @Mock
    private DramaEpisodeMapper episodeMapper;

    @Mock
    private DramaSceneMapper sceneMapper;

    @Mock
    private MinioProperties minioProperties;

    @InjectMocks
    private VideoProcessSourceResolverImpl resolver;

    @BeforeEach
    void setUp() {
        when(minioProperties.getBucketName()).thenReturn("video-assets");
        when(minioProperties.getEndpoint()).thenReturn("http://127.0.0.1:9000");
    }

    @Test
    @DisplayName("DIRECT_URL 缺少 URL 时报错")
    void testDirectUrl_MissingUrl_Throws() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("DIRECT_URL");
        dto.setSourceVideoUrl("");

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(dto));
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("DIRECT_URL 携带 shotId 时拒绝歧义参数")
    void testDirectUrl_WithShotId_Throws() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("DIRECT_URL");
        dto.setSourceVideoUrl("http://127.0.0.1:9000/video-assets/input.mp4");
        dto.setSourceShotId(2032095628944588801L);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(dto));
        assertTrue(ex.getMessage().contains("不允许携带 sourceShotId"));
    }

    @Test
    @DisplayName("SHOT_CURRENT 缺少 shotId 时报错")
    void testShotCurrent_MissingShotId_Throws() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("SHOT_CURRENT");

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(dto));
        assertTrue(ex.getMessage().contains("sourceShotId 不能为空"));
    }

    @Test
    @DisplayName("SHOT_CURRENT 分镜不存在时抛出 404")
    void testShotCurrent_ShotNotFound_Throws() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("SHOT_CURRENT");
        dto.setSourceShotId(9999L);

        when(shotMapper.selectById(9999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(dto));
        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("SHOT_CURRENT 分镜未生成视频时报错")
    void testShotCurrent_NoVideo_Throws() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("SHOT_CURRENT");
        dto.setSourceShotId(100L);

        DramaShot shot = new DramaShot();
        shot.setId(100L);
        shot.setVideoUrl(null);
        when(shotMapper.selectById(100L)).thenReturn(shot);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(dto));
        assertTrue(ex.getMessage().contains("尚未生成视频"));
    }

    @Test
    @DisplayName("SHOT_CURRENT 正确解析分镜当前视频及关联业务上下文")
    void testShotCurrent_SuccessWithoutTake() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("SHOT_CURRENT");
        dto.setSourceShotId(100L);

        DramaShot shot = new DramaShot();
        shot.setId(100L);
        shot.setDramaId(1L);
        shot.setEpisodeId(2L);
        shot.setSceneId(3L);
        shot.setShotNo(3);
        shot.setShotName("S03-01");
        shot.setVideoUrl("http://127.0.0.1:9000/video-assets/shot100.mp4");

        when(shotMapper.selectById(100L)).thenReturn(shot);

        Drama drama = new Drama();
        drama.setId(1L);
        drama.setTitle("霸道总裁短剧");
        when(dramaMapper.selectById(1L)).thenReturn(drama);

        DramaEpisode episode = new DramaEpisode();
        episode.setId(2L);
        episode.setTitle("第一集 龙王归来");
        when(episodeMapper.selectById(2L)).thenReturn(episode);

        DramaScene scene = new DramaScene();
        scene.setId(3L);
        scene.setName("大厅夜景");
        when(sceneMapper.selectById(3L)).thenReturn(scene);

        ResolvedVideoProcessSource resolved = resolver.resolve(dto);

        assertNotNull(resolved);
        assertEquals("SHOT_CURRENT", resolved.getSourceType());
        assertEquals("http://127.0.0.1:9000/video-assets/shot100.mp4", resolved.getVideoUrl());
        assertEquals(100L, resolved.getShotId());
        assertEquals(3, resolved.getShotNo());
        assertEquals("S03-01", resolved.getShotName());
        assertEquals("霸道总裁短剧", resolved.getDramaTitle());
        assertEquals("第一集 龙王归来", resolved.getEpisodeTitle());
        assertEquals("大厅夜景", resolved.getSceneName());
    }

    @Test
    @DisplayName("SHOT_CURRENT 当前 Take 与 videoUrl 一致时解析成功并记录 Take ID")
    void testShotCurrent_WithTakeConsistent_Success() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("SHOT_CURRENT");
        dto.setSourceShotId(100L);

        DramaShot shot = new DramaShot();
        shot.setId(100L);
        shot.setVideoUrl("http://127.0.0.1:9000/video-assets/shot100_take5.mp4");
        shot.setCurrentVideoTakeId(500L);
        when(shotMapper.selectById(100L)).thenReturn(shot);

        DramaShotVideoTake take = new DramaShotVideoTake();
        take.setId(500L);
        take.setShotId(100L);
        take.setStatus("AVAILABLE");
        take.setVideoUrl("http://127.0.0.1:9000/video-assets/shot100_take5.mp4");
        when(videoTakeMapper.selectById(500L)).thenReturn(take);

        ResolvedVideoProcessSource resolved = resolver.resolve(dto);

        assertNotNull(resolved);
        assertEquals(500L, resolved.getVideoTakeId());
        assertEquals("http://127.0.0.1:9000/video-assets/shot100_take5.mp4", resolved.getVideoUrl());
    }

    @Test
    @DisplayName("SHOT_CURRENT 当前 Take 与 videoUrl 不一致时拒绝")
    void testShotCurrent_WithTakeInconsistent_Throws() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("SHOT_CURRENT");
        dto.setSourceShotId(100L);

        DramaShot shot = new DramaShot();
        shot.setId(100L);
        shot.setVideoUrl("http://127.0.0.1:9000/video-assets/old_video.mp4");
        shot.setCurrentVideoTakeId(500L);
        when(shotMapper.selectById(100L)).thenReturn(shot);

        DramaShotVideoTake take = new DramaShotVideoTake();
        take.setId(500L);
        take.setShotId(100L);
        take.setStatus("AVAILABLE");
        take.setVideoUrl("http://127.0.0.1:9000/video-assets/new_video.mp4");
        when(videoTakeMapper.selectById(500L)).thenReturn(take);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(dto));
        assertTrue(ex.getMessage().contains("不一致"));
    }

    @Test
    @DisplayName("SHOT_VIDEO_TAKE 缺少 takeId 时报错")
    void testShotVideoTake_MissingTakeId_Throws() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("SHOT_VIDEO_TAKE");
        dto.setSourceShotId(100L);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(dto));
        assertTrue(ex.getMessage().contains("sourceVideoTakeId 不能为空"));
    }

    @Test
    @DisplayName("SHOT_VIDEO_TAKE 不属于指定分镜时拒绝")
    void testShotVideoTake_MismatchShot_Throws() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("SHOT_VIDEO_TAKE");
        dto.setSourceShotId(100L);
        dto.setSourceVideoTakeId(500L);

        DramaShot shot = new DramaShot();
        shot.setId(100L);
        when(shotMapper.selectById(100L)).thenReturn(shot);

        DramaShotVideoTake take = new DramaShotVideoTake();
        take.setId(500L);
        take.setShotId(200L); // belongs to another shot!
        when(videoTakeMapper.selectById(500L)).thenReturn(take);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(dto));
        assertTrue(ex.getMessage().contains("不属于当前分镜"));
    }

    @Test
    @DisplayName("SHOT_VIDEO_TAKE 状态不可用时拒绝")
    void testShotVideoTake_Unavailable_Throws() {
        VideoProcessSubmitDTO dto = new VideoProcessSubmitDTO();
        dto.setSourceType("SHOT_VIDEO_TAKE");
        dto.setSourceShotId(100L);
        dto.setSourceVideoTakeId(500L);

        DramaShot shot = new DramaShot();
        shot.setId(100L);
        when(shotMapper.selectById(100L)).thenReturn(shot);

        DramaShotVideoTake take = new DramaShotVideoTake();
        take.setId(500L);
        take.setShotId(100L);
        take.setStatus("ARCHIVED");
        when(videoTakeMapper.selectById(500L)).thenReturn(take);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(dto));
        assertTrue(ex.getMessage().contains("不可用"));
    }

    @Test
    @DisplayName("非法协议与受限本地主机被拒绝")
    void testUrlSafety_RejectsInvalidProtocols() {
        assertThrows(BizException.class, () -> resolver.validateUrlSafety("file:///etc/passwd"));
        assertThrows(BizException.class, () -> resolver.validateUrlSafety("ftp://127.0.0.1/video.mp4"));
    }
}
