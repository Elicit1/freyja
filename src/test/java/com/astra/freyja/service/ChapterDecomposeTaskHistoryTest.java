package com.astra.freyja.service;

import com.astra.freyja.dao.AiTaskMapper;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskStatus;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.service.impl.AiTaskServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChapterDecomposeTaskHistoryTest {

    @Mock
    private AiTaskMapper aiTaskMapper;

    private AiTaskService aiTaskService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiTaskService = new AiTaskServiceImpl(aiTaskMapper, objectMapper, null, null);
    }

    @Test
    void testListChapterHistoryWithDramaAndEpisodeIsolation() throws Exception {
        // 创建任务1 (短剧 100, 第 1 集)
        AiTask task1 = new AiTask();
        task1.setId(1L);
        task1.setDramaId(100L);
        task1.setEpisodeId(1L);
        task1.setTaskType(AiTaskType.CHAPTER_DECOMPOSE.name());
        task1.setStatus(AiTaskStatus.SUCCESS.name());

        ScriptDecomposeResultVO result1 = ScriptDecomposeResultVO.builder()
                .dramaTitle("战神归来")
                .segments(List.of(
                        StorySegment.builder().id("SEG001").sequence(1).title("前奏").build(),
                        StorySegment.builder().id("SEG002").sequence(2).title("交锋").build()
                ))
                .segmentResults(List.of(
                        SegmentShotResult.builder().segmentId("SEG001").status("SUCCESS").build(),
                        SegmentShotResult.builder().segmentId("SEG002").status("SUCCESS").build()
                ))
                .build();
        task1.setOutputPayload(objectMapper.writeValueAsString(result1));

        // 创建任务2 (短剧 100, 第 2 集, 部分失败)
        AiTask task2 = new AiTask();
        task2.setId(2L);
        task2.setDramaId(100L);
        task2.setEpisodeId(2L);
        task2.setTaskType(AiTaskType.CHAPTER_DECOMPOSE.name());
        task2.setStatus(AiTaskStatus.PARTIAL_SUCCESS.name());

        ScriptDecomposeResultVO result2 = ScriptDecomposeResultVO.builder()
                .dramaTitle("战神归来")
                .segments(List.of(
                        StorySegment.builder().id("SEG001").sequence(1).title("逼供").build(),
                        StorySegment.builder().id("SEG002").sequence(2).title("反杀").build()
                ))
                .segmentResults(List.of(
                        SegmentShotResult.builder().segmentId("SEG001").status("SUCCESS").build(),
                        SegmentShotResult.builder().segmentId("SEG002").status("FAILED").errorMessage("内容敏感触发安全过滤").build()
                ))
                .build();
        task2.setOutputPayload(objectMapper.writeValueAsString(result2));

        when(aiTaskMapper.selectList(any())).thenAnswer(inv -> {
            Wrapper<AiTask> wrapper = inv.getArgument(0);
            String sqlSegment = wrapper != null ? wrapper.getSqlSegment() : null;
            // 如果同时包含 episode_id 过滤
            if (sqlSegment != null && sqlSegment.contains("episode_id")) {
                return List.of(task1);
            }
            // 否则返回该短剧下的全部历史
            return List.of(task2, task1);
        });

        // 1. 按具体剧集隔离查询
        List<ChapterDecomposeHistoryVO> ep1History = aiTaskService.listChapterHistory(100L, 1L);
        assertEquals(1, ep1History.size());
        assertEquals(1L, ep1History.get(0).getTaskId());
        assertEquals("SUCCESS", ep1History.get(0).getStatus());
        assertEquals(2, ep1History.get(0).getTotalSegments());
        assertEquals(2, ep1History.get(0).getSuccessSegments());
        assertEquals(0, ep1History.get(0).getFailedSegments());

        // 2. 查询全剧历史 (不传 episodeId)
        List<ChapterDecomposeHistoryVO> allDramaHistory = aiTaskService.listChapterHistory(100L, null);
        assertEquals(2, allDramaHistory.size());
        assertEquals(2L, allDramaHistory.get(0).getTaskId());
        assertEquals("PARTIAL_SUCCESS", allDramaHistory.get(0).getStatus());
        assertEquals(1, allDramaHistory.get(0).getFailedSegments());
        assertTrue(allDramaHistory.get(0).getFailedSegmentIds().contains("SEG002"));
    }

    @Test
    void testGetDecomposePreviewAndTaskPersistence() throws Exception {
        AiTask task = new AiTask();
        task.setId(88L);
        task.setStatus(AiTaskStatus.SUCCESS.name());

        ScriptDecomposeResultVO result = ScriptDecomposeResultVO.builder()
                .dramaTitle("修罗殿")
                .scenes(List.of(DecomposedSceneVO.builder().id("SC001").sceneName("宴会大厅").build()))
                .characters(List.of(DecomposedCharacterVO.builder().name("林枫").build()))
                .build();
        task.setOutputPayload(objectMapper.writeValueAsString(result));

        when(aiTaskMapper.selectById(88L)).thenReturn(task);

        ScriptDecomposeResultVO preview = aiTaskService.getDecomposePreview(88L);
        assertNotNull(preview);
        assertEquals(88L, preview.getTaskId());
        assertEquals("SUCCESS", preview.getStatus());
        assertEquals("修罗殿", preview.getDramaTitle());
        assertEquals(1, preview.getScenes().size());
        assertEquals("宴会大厅", preview.getScenes().get(0).getSceneName());
    }

    @Test
    void testDeleteTaskCascades() {
        aiTaskService.deleteTask(99L);
        verify(aiTaskMapper).deleteById(99L);
        verify(aiTaskMapper).delete(any());
    }

    @Test
    void testListChapterHistoryGlobalWhenDramaIdNull() {
        AiTask task = new AiTask();
        task.setId(999L);
        task.setDramaId(0L);
        task.setStatus(AiTaskStatus.SUCCESS.name());

        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));

        List<ChapterDecomposeHistoryVO> globalHistory = aiTaskService.listChapterHistory(null, null);
        assertNotNull(globalHistory);
        assertEquals(1, globalHistory.size());
        assertEquals(999L, globalHistory.get(0).getTaskId());
    }

    @Test
    void testBindDramaUpdatesParentAndSubTasks() {
        AiTask parent = new AiTask();
        parent.setId(123L);
        parent.setDramaId(0L);

        when(aiTaskMapper.selectById(123L)).thenReturn(parent);

        aiTaskService.bindDrama(123L, 888L);

        assertEquals(888L, parent.getDramaId());
        verify(aiTaskMapper).updateById(parent);
        verify(aiTaskMapper).update(any(AiTask.class), any(Wrapper.class));
    }
}
