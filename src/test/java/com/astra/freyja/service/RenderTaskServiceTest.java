package com.astra.freyja.service;

import com.astra.freyja.dao.RenderTaskMapper;
import com.astra.freyja.dto.render.RenderTaskVO;
import com.astra.freyja.entity.RenderTask;
import com.astra.freyja.service.impl.RenderTaskServiceImpl;
import com.astra.freyja.websocket.RenderTaskWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RenderTaskServiceTest {

    @Mock
    private RenderTaskMapper renderTaskMapper;

    @Mock
    private com.astra.freyja.dao.DramaShotMapper dramaShotMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @Mock
    private RenderTaskWebSocketHandler wsHandler;

    @Mock
    private DataSource dataSource;

    @Mock
    private com.astra.freyja.service.AiImageApiService aiImageApiService;

    private ObjectMapper objectMapper;

    private RenderTaskServiceImpl renderTaskService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        renderTaskService = new RenderTaskServiceImpl(
                renderTaskMapper,
                dramaShotMapper,
                redisTemplate,
                wsHandler,
                objectMapper,
                dataSource,
                aiImageApiService
        );
    }

    @Test
    void testCreateTask_WritesToRedisAndBroadcasts() {
        RenderTaskVO vo = RenderTaskVO.builder()
                .taskId("TEST_TASK_001")
                .taskType("SHOT_FRAME")
                .taskName("[S1 特写] 首帧生图")
                .dramaId(1L)
                .shotId(10L)
                .prompt("hero looking at camera")
                .build();

        doAnswer(invocation -> {
            RenderTask entity = invocation.getArgument(0);
            entity.setId(1001L);
            return 1;
        }).when(renderTaskMapper).insert(any(RenderTask.class));

        RenderTaskVO created = renderTaskService.createTask(vo);

        assertNotNull(created);
        assertEquals("TEST_TASK_001", created.getTaskId());
        assertEquals("QUEUED", created.getStatus());

        // 验证 MySQL 写入
        verify(renderTaskMapper, times(1)).insert(any(RenderTask.class));
        // 验证写入 Redis Hash 和 ZSet
        verify(hashOperations, times(1)).put(eq("freyja:render:active_tasks"), eq("TEST_TASK_001"), anyString());
        verify(zSetOperations, times(1)).add(eq("freyja:render:active_queue"), eq("TEST_TASK_001"), anyDouble());
        // 验证 WebSocket 广播
        verify(wsHandler, times(1)).broadcast(any());
    }

    @Test
    void testFinishTask_RemovesFromRedisAndArchivesMySQL() throws Exception {
        RenderTaskVO existingVo = RenderTaskVO.builder()
                .taskId("TEST_TASK_002")
                .taskType("SHOT_VIDEO")
                .taskName("[S2 追逐] 视频渲染")
                .submitTime(LocalDateTime.now().minusSeconds(10))
                .status("RENDERING")
                .progress(80)
                .build();
        String json = objectMapper.writeValueAsString(existingVo);

        when(hashOperations.get("freyja:render:active_tasks", "TEST_TASK_002")).thenReturn(json);

        RenderTask dbEntity = new RenderTask();
        dbEntity.setId(2002L);
        dbEntity.setTaskId("TEST_TASK_002");
        dbEntity.setSubmitTime(existingVo.getSubmitTime());
        when(renderTaskMapper.selectOne(any())).thenReturn(dbEntity);

        renderTaskService.finishTask("TEST_TASK_002", "http://minio/output.mp4", "http://minio/last.png");

        // 验证从 Redis 移除
        verify(hashOperations, times(1)).delete("freyja:render:active_tasks", "TEST_TASK_002");
        verify(zSetOperations, times(1)).remove("freyja:render:active_queue", "TEST_TASK_002");

        // 验证更新 MySQL 终态
        ArgumentCaptor<RenderTask> captor = ArgumentCaptor.forClass(RenderTask.class);
        verify(renderTaskMapper, times(1)).updateById(captor.capture());
        RenderTask updated = captor.getValue();
        assertEquals("SUCCESS", updated.getStatus());
        assertEquals("http://minio/output.mp4", updated.getOutputUrl());
        assertEquals(100, updated.getProgress());

        // 验证 WebSocket 广播 TASK_SUCCESS
        verify(wsHandler, times(1)).broadcast(argThat(msg ->
                "TASK_SUCCESS".equals(msg.getEvent())
        ));
    }

    @Test
    void testGetActiveTasks_ReadsFromRedisZSetAndHash() throws Exception {
        when(zSetOperations.range("freyja:render:active_queue", 0, -1))
                .thenReturn(Set.of("TASK_A", "TASK_B"));

        RenderTaskVO voA = RenderTaskVO.builder().taskId("TASK_A").taskName("Task A").status("RENDERING").build();
        RenderTaskVO voB = RenderTaskVO.builder().taskId("TASK_B").taskName("Task B").status("QUEUED").build();

        when(hashOperations.get("freyja:render:active_tasks", "TASK_A")).thenReturn(objectMapper.writeValueAsString(voA));
        when(hashOperations.get("freyja:render:active_tasks", "TASK_B")).thenReturn(objectMapper.writeValueAsString(voB));

        var list = renderTaskService.getActiveTasks();

        assertEquals(2, list.size());
        verify(renderTaskMapper, never()).selectList(any());
    }

    @Test
    void testCancelTask_ResetsDramaShotToInit() {
        String taskId = "TEST_TASK_CANCEL";
        RenderTask entity = new RenderTask();
        entity.setId(3003L);
        entity.setTaskId(taskId);
        entity.setShotId(88L);
        entity.setStatus("RENDERING");
        entity.setSubmitTime(LocalDateTime.now().minusSeconds(5));

        when(renderTaskMapper.selectOne(any())).thenReturn(entity);

        com.astra.freyja.entity.DramaShot shot = new com.astra.freyja.entity.DramaShot();
        shot.setId(88L);
        shot.setRenderStatus("RENDERING");
        shot.setLatestTaskId(taskId);
        when(dramaShotMapper.selectById(88L)).thenReturn(shot);
        when(aiImageApiService.cancelRemoteTask(any(), any(), any())).thenReturn(
                com.astra.freyja.dto.render.RemoteCancelResultVO.builder()
                        .success(true)
                        .message("Cancelled")
                        .detailStatus("CANCELLED")
                        .build()
        );

        boolean result = renderTaskService.cancelTask(taskId);

        assertTrue(result);
        assertEquals("INIT", shot.getRenderStatus());
        assertNull(shot.getLatestTaskId());
        verify(dramaShotMapper, times(1)).updateById(shot);
        verify(renderTaskMapper, times(1)).updateById(entity);
        assertEquals("CANCELLED", entity.getStatus());
        verify(hashOperations, times(1)).delete("freyja:render:active_tasks", taskId);
        verify(zSetOperations, times(1)).remove("freyja:render:active_queue", taskId);
    }

    @Test
    void testCancelTask_AlreadyFinished_ThrowsBizException() {
        String taskId = "TEST_TASK_ALREADY_DONE";
        RenderTask entity = new RenderTask();
        entity.setId(3004L);
        entity.setTaskId(taskId);
        entity.setStatus("RENDERING");
        when(renderTaskMapper.selectOne(any())).thenReturn(entity);
        when(aiImageApiService.cancelRemoteTask(any(), any(), any())).thenReturn(
                com.astra.freyja.dto.render.RemoteCancelResultVO.builder()
                        .success(false)
                        .message("Task already completed")
                        .detailStatus("ALREADY_FINISHED")
                        .build()
        );

        assertThrows(com.astra.freyja.common.BizException.class, () -> renderTaskService.cancelTask(taskId));
        assertNotEquals("CANCELLED", entity.getStatus());
    }

    @Test
    void testCancelTask_RemoteFailed_ThrowsBizException() {
        String taskId = "TEST_TASK_FAILED";
        RenderTask entity = new RenderTask();
        entity.setId(3005L);
        entity.setTaskId(taskId);
        entity.setStatus("RENDERING");
        when(renderTaskMapper.selectOne(any())).thenReturn(entity);
        when(aiImageApiService.cancelRemoteTask(any(), any(), any())).thenReturn(
                com.astra.freyja.dto.render.RemoteCancelResultVO.builder()
                        .success(false)
                        .message("Compute node unreachable")
                        .detailStatus("FAILED")
                        .build()
        );

        assertThrows(com.astra.freyja.common.BizException.class, () -> renderTaskService.cancelTask(taskId));
        assertNotEquals("CANCELLED", entity.getStatus());
    }
}
