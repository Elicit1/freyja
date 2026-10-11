package com.astra.freyja.service;

import com.astra.freyja.dao.AiTaskMapper;
import com.astra.freyja.dto.script.ScriptDecomposeResultVO;
import com.astra.freyja.dto.script.AiTaskQueryDTO;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.service.impl.AiTaskServiceImpl;
import com.astra.freyja.service.impl.ScriptDecomposeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScriptDecomposeRedisTaskTest {

    @Test void terminalDraftOverridesPendingSqlCheckpointBeforeActiveFiltering() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AiTask.class);
        AiTaskMapper mysql = mock(AiTaskMapper.class);
        ScriptDecomposeDraftStore drafts = mock(ScriptDecomposeDraftStore.class);
        AiTask checkpoint = new AiTask(); checkpoint.setId(42L); checkpoint.setStatus("PENDING");
        AiTask draft = new AiTask(); draft.setId(42L); draft.setStatus("CANCELLED");
        when(mysql.selectList(any())).thenReturn(List.of(checkpoint));
        when(drafts.listTasks()).thenReturn(List.of(draft));
        AiTaskServiceImpl service = new AiTaskServiceImpl(mysql);
        ReflectionTestUtils.setField(service, "draftStore", drafts);
        assertTrue(service.listTasks(AiTaskQueryDTO.builder().statuses(Set.of("PENDING", "RUNNING")).build()).isEmpty());
        var history = service.listTasks(null);
        assertEquals(1, history.size()); assertEquals("CANCELLED", history.getFirst().getStatus());
    }

    @Test void rolledBackLaunchEndsRedisTaskWithoutRunningInferenceOrDeletingDraft() {
        var service = mock(ScriptDecomposeServiceImpl.class, CALLS_REAL_METHODS);
        var tasks = mock(AiTaskService.class); var events = mock(ShotPromptEventStore.class);
        ReflectionTestUtils.setField(service, "aiTaskService", tasks);
        ReflectionTestUtils.setField(service, "aiEvents", events);
        AiTask task = new AiTask(); task.setId(42L);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            ReflectionTestUtils.invokeMethod(service, "launchDecomposeTask", new ScriptDecomposeRequestDTO(), task);
            var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, callbacks.size());
            callbacks.getFirst().afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(tasks, timeout(3000)).markFailed(eq(42L), contains("创建事务未提交"));
            verify(events, timeout(3000)).appendScript("42", "done", "[DONE]");
            verify(tasks, never()).markRunning(anyLong());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void parentAndWorkerMetadataStayInRedisUntilCommit() {
        AiTaskMapper mysql = mock(AiTaskMapper.class);
        ScriptDecomposeDraftStore drafts = mock(ScriptDecomposeDraftStore.class);
        Map<Long, AiTask> stored = new ConcurrentHashMap<>();
        doAnswer(call -> {
            AiTask task = call.getArgument(0);
            stored.put(task.getId(), task);
            return null;
        }).when(drafts).saveTask(any(AiTask.class));
        when(drafts.getTask(anyLong())).thenAnswer(call -> stored.get(call.getArgument(0)));

        AiTaskServiceImpl service = new AiTaskServiceImpl(mysql);
        ReflectionTestUtils.setField(service, "draftStore", drafts);

        AiTask parent = service.createTask(null, null, AiTaskType.CHAPTER_DECOMPOSE,
                "CHAPTER", null, null, "model", 16384);
        assertNotNull(parent.getId());
        service.markRunning(parent.getId());
        AiTask worker = service.createTask(null, null, AiTaskType.SHOT_DECOMPOSE,
                "WORKER_SEG001", parent.getId(), "Segment: SEG001", "model", 8192);
        service.markSuccess(worker.getId(), null, 12);
        service.markSuccess(parent.getId(), null, 34);
        AiTask retry = service.createTask(null, null, AiTaskType.WORKER_RETRY,
                String.valueOf(parent.getId()), parent.getId(), "retry request", "model", 8192);
        service.markSuccess(retry.getId(), "retry result", 0);

        assertEquals("SUCCESS", stored.get(parent.getId()).getStatus());
        assertEquals("SUCCESS", stored.get(worker.getId()).getStatus());
        assertNull(stored.get(parent.getId()).getOutputPayload());
        assertEquals(parent.getId(), stored.get(worker.getId()).getParentTaskId());
        assertEquals(parent.getId(), stored.get(retry.getId()).getParentTaskId());
        assertEquals("retry result", stored.get(retry.getId()).getOutputPayload());
        verifyNoInteractions(mysql);
    }

    @Test
    void previewComesFromRedisWithoutMysqlLookup() {
        AiTaskMapper mysql = mock(AiTaskMapper.class);
        ScriptDecomposeDraftStore drafts = mock(ScriptDecomposeDraftStore.class);
        AiTask parent = new AiTask();
        parent.setId(42L);
        parent.setStatus("SUCCESS");
        ScriptDecomposeResultVO result = new ScriptDecomposeResultVO();
        when(drafts.getTask(42L)).thenReturn(parent);
        when(drafts.getResult(42L)).thenReturn(result);

        AiTaskServiceImpl service = new AiTaskServiceImpl(mysql);
        ReflectionTestUtils.setField(service, "draftStore", drafts);

        assertSame(result, service.getDecomposePreview(42L));
        assertEquals(42L, result.getTaskId());
        verifyNoInteractions(mysql);
    }
}
