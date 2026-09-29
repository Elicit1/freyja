package com.astra.freyja.service;

import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.render.RenderTaskVO;
import com.astra.freyja.dto.script.AiTaskDTO;
import com.astra.freyja.dto.task.TaskCancelResultVO;
import com.astra.freyja.service.impl.TaskCenterServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskCenterCancellationTest {

    @Test
    void cancellingChapterCancelsWorkerAndRemoteRenderBeforeReturning() {
        AiTaskService aiTasks = mock(AiTaskService.class);
        RenderTaskService renders = mock(RenderTaskService.class);
        TaskCenterServiceImpl center = new TaskCenterServiceImpl(aiTasks, renders,
                mock(DramaMapper.class), mock(DramaEpisodeMapper.class),
                mock(DramaSceneMapper.class), mock(DramaShotMapper.class));
        AiTaskDTO root = AiTaskDTO.builder().id(1L).taskType("CHAPTER_DECOMPOSE")
                .status("RUNNING").build();
        AiTaskDTO worker = AiTaskDTO.builder().id(2L).parentTaskId(1L)
                .taskType("SHOT_DECOMPOSE").status("RUNNING").build();
        when(aiTasks.getTaskById(1L)).thenReturn(root);
        when(aiTasks.listTasks(isNull())).thenAnswer(ignored -> List.of(root, worker));
        when(aiTasks.markCancelled(eq(1L), eq(false))).thenReturn(true);
        when(aiTasks.markCancelled(eq(2L), eq(false))).thenAnswer(ignored -> {
            worker.setStatus("CANCEL_UNCONFIRMED");
            return true;
        });
        RenderTaskVO render = RenderTaskVO.builder().taskId("render-1").build();
        when(renders.getActiveTasksByParentAiTaskIds(anyList()))
                .thenReturn(List.of(render), List.of());
        when(renders.cancelTaskDetailed("render-1")).thenReturn(TaskCancelResultVO.builder()
                .cancelled(true).status("CANCELLED").upstreamStatus("CONFIRMED").build());

        TaskCancelResultVO result = center.cancelTask("AI_TASK", "1");

        assertTrue(result.isCancelled());
        assertEquals("CANCEL_UNCONFIRMED", result.getStatus());
        assertEquals("UNCONFIRMED", result.getUpstreamStatus());
        verify(aiTasks).markCancelled(1L, false);
        verify(aiTasks).markCancelled(2L, false);
        verify(renders).cancelTaskDetailed("render-1");
    }
}
