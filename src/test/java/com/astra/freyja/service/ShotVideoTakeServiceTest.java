package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.DramaShotVideoTakeMapper;
import com.astra.freyja.dao.MediaProcessTaskMapper;
import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
import com.astra.freyja.dto.drama.ShotVideoTakeQuery;
import com.astra.freyja.dto.drama.ShotVideoTakeSelectVO;
import com.astra.freyja.dto.drama.ShotVideoTakeVO;
import com.astra.freyja.dto.drama.VideoGenerationResultVO;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.DramaShotVideoTake;
import com.astra.freyja.entity.MediaProcessTask;
import com.astra.freyja.entity.RenderTask;
import com.astra.freyja.service.impl.ShotVideoTakeServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShotVideoTakeServiceTest {

    @Mock
    private DramaShotMapper shotMapper;

    @Mock
    private DramaShotVideoTakeMapper videoTakeMapper;

    @Mock
    private MediaProcessTaskMapper mediaProcessTaskMapper;

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioProperties minioProperties;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ShotVideoTakeServiceImpl videoTakeService;

    private DramaShot mockShot;
    private RenderTask mockRenderTask;
    private DramaShotRenderRequestDTO mockRequestDTO;
    private VideoGenerationResultVO mockResultVO;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DramaShot.class);
        TableInfoHelper.initTableInfo(assistant, DramaShotVideoTake.class);
        TableInfoHelper.initTableInfo(assistant, MediaProcessTask.class);
        mockShot = new DramaShot();
        mockShot.setId(1001L);
        mockShot.setDramaId(10L);
        mockShot.setEpisodeId(20L);
        mockShot.setSceneId(30L);
        mockShot.setShotGroupId(40L);
        mockShot.setShotNo(1);
        mockShot.setDuration(new BigDecimal("5.00"));
        mockShot.setVideoPrompt("Camera pushes in slowly");
        mockShot.setPreviewImageUrl("http://127.0.0.1:9000/freyja/first.jpg");
        mockShot.setLatestTaskId("RENDER_001");
        mockShot.setCurrentVideoTakeId(null);

        mockRenderTask = new RenderTask();
        mockRenderTask.setTaskId("RENDER_001");
        mockRenderTask.setProviderId(501L);
        mockRenderTask.setProviderName("Local FastAPI");
        mockRenderTask.setModelCode("MiniMax-H3");

        mockRequestDTO = new DramaShotRenderRequestDTO();
        mockRequestDTO.setTaskId("RENDER_001");
        mockRequestDTO.setSize("720x1280");
        mockRequestDTO.setSeed(123456L);

        mockResultVO = VideoGenerationResultVO.builder()
                .videoUrl("http://127.0.0.1:9000/freyja/videos/shot_1001_take1.mp4")
                .lastFrameUrl("http://127.0.0.1:9000/freyja/frames/shot_1001_tail.jpg")
                .build();
    }

    @Test
    @DisplayName("首次生成视频：创建 Take #1 并自动设为当前视频")
    void testRecordGeneratedTake_FirstTake_AutoSelected() {
        when(videoTakeMapper.selectOne(any())).thenReturn(null);
        when(videoTakeMapper.selectMaxTakeNoByShotId(1001L)).thenReturn(0);
        when(videoTakeMapper.insert(any(DramaShotVideoTake.class))).thenAnswer(invocation -> {
            DramaShotVideoTake t = invocation.getArgument(0);
            t.setId(2001L);
            return 1;
        });
        when(shotMapper.promoteVideoTakeIfLatest(1001L, "RENDER_001", 2001L,
                mockResultVO.getVideoUrl())).thenReturn(1);

        DramaShotVideoTake created = videoTakeService.recordGeneratedTake(
                mockShot, mockRenderTask, mockRequestDTO, mockResultVO
        );

        assertNotNull(created);
        assertEquals(1, created.getTakeNo());
        assertEquals("AVAILABLE", created.getStatus());
        assertEquals(mockResultVO.getVideoUrl(), created.getVideoUrl());

        verify(shotMapper).promoteVideoTakeIfLatest(1001L, "RENDER_001", 2001L,
                mockResultVO.getVideoUrl());
    }

    @Test
    @DisplayName("乱序完成的旧任务：成功归档至历史 Take，但绝不覆盖更新的当前视频")
    void testRecordGeneratedTake_OlderTask_DoesNotOverwriteCurrent() {
        // 当前 shot 的最新任务已经是 RENDER_002
        mockShot.setLatestTaskId("RENDER_002");
        mockShot.setCurrentVideoTakeId(2002L);
        mockShot.setVideoUrl("http://127.0.0.1:9000/freyja/videos/shot_1001_take2.mp4");

        // 本次完成的是旧任务 RENDER_001
        when(videoTakeMapper.selectOne(any())).thenReturn(null);
        when(videoTakeMapper.selectMaxTakeNoByShotId(1001L)).thenReturn(2);
        when(videoTakeMapper.insert(any(DramaShotVideoTake.class))).thenAnswer(invocation -> {
            DramaShotVideoTake t = invocation.getArgument(0);
            t.setId(2003L);
            return 1;
        });
        when(shotMapper.promoteVideoTakeIfLatest(1001L, "RENDER_001", 2003L,
                mockResultVO.getVideoUrl())).thenReturn(0);

        DramaShotVideoTake created = videoTakeService.recordGeneratedTake(
                mockShot, mockRenderTask, mockRequestDTO, mockResultVO
        );

        assertNotNull(created);
        assertEquals(3, created.getTakeNo());
        // 验证 shot 的 currentVideoTakeId 仍然是 2002L，没有被旧任务覆盖
        assertEquals(2002L, mockShot.getCurrentVideoTakeId());
        assertEquals("http://127.0.0.1:9000/freyja/videos/shot_1001_take2.mp4", mockShot.getVideoUrl());
    }

    @Test
    @DisplayName("重复任务回调：幂等返回已有 Take，不重复插入")
    void testRecordGeneratedTake_IdempotentOnDuplicateTask() {
        DramaShotVideoTake existing = new DramaShotVideoTake();
        existing.setId(2001L);
        existing.setTaskId("RENDER_001");
        when(videoTakeMapper.selectOne(any())).thenReturn(existing);

        DramaShotVideoTake result = videoTakeService.recordGeneratedTake(
                mockShot, mockRenderTask, mockRequestDTO, mockResultVO
        );

        assertSame(existing, result);
        verify(videoTakeMapper, never()).insert(any(DramaShotVideoTake.class));
    }

    @Test
    @DisplayName("分页查询历史：正确标记当前版本与字段映射")
    void testPageByShotId_Success() {
        mockShot.setCurrentVideoTakeId(2002L);
        when(shotMapper.selectById(1001L)).thenReturn(mockShot);

        DramaShotVideoTake take1 = new DramaShotVideoTake();
        take1.setId(2001L);
        take1.setShotId(1001L);
        take1.setTakeNo(1);
        take1.setStatus("AVAILABLE");
        take1.setVideoUrl("http://minio/v1.mp4");
        take1.setCreateTime(LocalDateTime.now().minusMinutes(5));

        DramaShotVideoTake take2 = new DramaShotVideoTake();
        take2.setId(2002L);
        take2.setShotId(1001L);
        take2.setTakeNo(2);
        take2.setStatus("AVAILABLE");
        take2.setVideoUrl("http://minio/v2.mp4");
        take2.setCreateTime(LocalDateTime.now());

        Page<DramaShotVideoTake> entityPage = new Page<>(1, 10, 2);
        entityPage.setRecords(List.of(take2, take1));

        when(videoTakeMapper.selectPage(any(), any())).thenReturn(entityPage);

        ShotVideoTakeQuery query = new ShotVideoTakeQuery();
        Page<ShotVideoTakeVO> voPage = videoTakeService.pageByShotId(1001L, query);

        assertEquals(2, voPage.getTotal());
        assertEquals(2, voPage.getRecords().size());

        ShotVideoTakeVO vo2 = voPage.getRecords().get(0);
        assertEquals(2002L, vo2.getId());
        assertTrue(vo2.getCurrent());

        ShotVideoTakeVO vo1 = voPage.getRecords().get(1);
        assertEquals(2001L, vo1.getId());
        assertFalse(vo1.getCurrent());
    }

    @Test
    @DisplayName("手动重选历史版本：更新 currentVideoTakeId 和 videoUrl 并使尾帧缓存失效")
    void testSelectTake_Success() {
        mockShot.setCurrentVideoTakeId(2001L);
        mockShot.setVideoUrl("http://minio/v1.mp4");
        mockShot.setLastFrameUrl("http://minio/frame1.jpg");
        mockShot.setLastFrameSourceTakeId(2001L);

        DramaShotVideoTake targetTake = new DramaShotVideoTake();
        targetTake.setId(2002L);
        targetTake.setShotId(1001L);
        targetTake.setStatus("AVAILABLE");
        targetTake.setVideoUrl("http://127.0.0.1:9000/freyja/v2.mp4");

        when(shotMapper.selectById(1001L)).thenReturn(mockShot);
        when(videoTakeMapper.selectById(2002L)).thenReturn(targetTake);
        when(minioProperties.getBucketName()).thenReturn("freyja");

        ShotVideoTakeSelectVO selectVO = videoTakeService.selectTake(1001L, 2002L);

        assertNotNull(selectVO);
        assertTrue(selectVO.getChanged());
        assertEquals(2001L, selectVO.getPreviousTakeId());
        assertEquals(2002L, selectVO.getCurrentTakeId());
        assertEquals(targetTake.getVideoUrl(), selectVO.getVideoUrl());
        assertEquals("SUCCESS", mockShot.getRenderStatus());

        // 验证分镜已更新且尾帧缓存已清空
        verify(shotMapper).updateById((DramaShot) argThat(s -> {
            DramaShot shot = (DramaShot) s;
            return shot.getCurrentVideoTakeId().equals(2002L)
                    && shot.getVideoUrl().equals(targetTake.getVideoUrl())
                    && shot.getLastFrameUrl() == null
                    && shot.getLastFrameSourceTakeId() == null;
        }));
    }

    @Test
    @DisplayName("手动重选已是当前的版本：幂等返回 changed=false")
    void testSelectTake_AlreadyCurrent_Idempotent() {
        mockShot.setCurrentVideoTakeId(2002L);
        mockShot.setVideoUrl("http://127.0.0.1:9000/freyja/v2.mp4");
        mockShot.setRenderStatus("SUCCESS");

        DramaShotVideoTake targetTake = new DramaShotVideoTake();
        targetTake.setId(2002L);
        targetTake.setShotId(1001L);
        targetTake.setStatus("AVAILABLE");
        targetTake.setVideoUrl("http://127.0.0.1:9000/freyja/v2.mp4");

        when(shotMapper.selectById(1001L)).thenReturn(mockShot);
        when(videoTakeMapper.selectById(2002L)).thenReturn(targetTake);
        when(minioProperties.getBucketName()).thenReturn("freyja");

        ShotVideoTakeSelectVO selectVO = videoTakeService.selectTake(1001L, 2002L);

        assertNotNull(selectVO);
        assertFalse(selectVO.getChanged());
        assertEquals(2002L, selectVO.getCurrentTakeId());
        verify(shotMapper, never()).updateById(any(DramaShot.class));
    }

    @Test
    @DisplayName("防御校验：跨分镜选择 Take 被拒绝")
    void testSelectTake_CrossShot_Rejected() {
        DramaShotVideoTake otherShotTake = new DramaShotVideoTake();
        otherShotTake.setId(9999L);
        otherShotTake.setShotId(2002L); // 属于其他分镜
        otherShotTake.setStatus("AVAILABLE");
        otherShotTake.setVideoUrl("http://minio/other.mp4");

        when(shotMapper.selectById(1001L)).thenReturn(mockShot);
        when(videoTakeMapper.selectById(9999L)).thenReturn(otherShotTake);

        BizException ex = assertThrows(BizException.class, () ->
                videoTakeService.selectTake(1001L, 9999L)
        );
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不属于当前分镜"));
    }

    @Test
    @DisplayName("防御校验：UNAVAILABLE 状态版本禁止选择")
    void testSelectTake_Unavailable_Rejected() {
        DramaShotVideoTake unavailTake = new DramaShotVideoTake();
        unavailTake.setId(2003L);
        unavailTake.setShotId(1001L);
        unavailTake.setStatus("UNAVAILABLE");
        unavailTake.setVideoUrl("http://minio/broken.mp4");

        when(shotMapper.selectById(1001L)).thenReturn(mockShot);
        when(videoTakeMapper.selectById(2003L)).thenReturn(unavailTake);

        BizException ex = assertThrows(BizException.class, () ->
                videoTakeService.selectTake(1001L, 2003L)
        );
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不可用"));
    }

    @Test
    @DisplayName("删除当前 Take：同步删除 MinIO 视频并清空分镜当前视频")
    void testDeleteCurrentTake() throws Exception {
        DramaShotVideoTake take = new DramaShotVideoTake();
        take.setId(2002L);
        take.setShotId(1001L);
        take.setVideoUrl("http://127.0.0.1:9000/freyja/videos/take.mp4");
        mockShot.setCurrentVideoTakeId(2002L);
        mockShot.setVideoUrl(take.getVideoUrl());
        when(shotMapper.selectById(1001L)).thenReturn(mockShot);
        when(videoTakeMapper.selectById(2002L)).thenReturn(take);
        when(minioProperties.getBucketName()).thenReturn("freyja");
        when(minioProperties.getEndpoint()).thenReturn("http://127.0.0.1:9000");
        when(minioProperties.getExternalEndpoint()).thenReturn("http://127.0.0.1:9000");

        videoTakeService.deleteTake(1001L, 2002L);

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        verify(shotMapper).update(isNull(), any());
        verify(videoTakeMapper).deleteById(2002L);
        verify(mediaProcessTaskMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("共享视频不可物理删除")
    void testDeleteSharedVideoRejected() throws Exception {
        DramaShotVideoTake take = new DramaShotVideoTake();
        take.setId(2002L);
        take.setShotId(1001L);
        take.setVideoUrl("http://127.0.0.1:9000/freyja/videos/shared.mp4");
        when(shotMapper.selectById(1001L)).thenReturn(mockShot);
        when(videoTakeMapper.selectById(2002L)).thenReturn(take);
        when(minioProperties.getBucketName()).thenReturn("freyja");
        when(minioProperties.getEndpoint()).thenReturn("http://127.0.0.1:9000");
        when(minioProperties.getExternalEndpoint()).thenReturn("http://127.0.0.1:9000");
        when(videoTakeMapper.selectCount(any())).thenReturn(1L);

        assertEquals(409, assertThrows(BizException.class, () -> videoTakeService.deleteTake(1001L, 2002L)).getCode());
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        verify(videoTakeMapper, never()).deleteById(any());
    }
}
