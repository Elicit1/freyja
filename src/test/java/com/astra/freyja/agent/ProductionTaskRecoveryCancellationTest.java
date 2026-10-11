package com.astra.freyja.agent;

import com.astra.freyja.agent.event.AgentEventPublisher;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.production.ProductionJobs;
import com.astra.freyja.agent.production.ProductionTaskRecovery;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.script.AiTaskDTO;
import com.astra.freyja.entity.AgentPreparationJob;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.service.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductionTaskRecoveryCancellationTest {
    @Test void committedCancellationStartsCleanupEvenWhileSpringStillBindsTransaction() {
        var jobs = mock(ProductionJobs.class);
        var events = new AgentEventPublisher(mock(org.springframework.context.ApplicationEventPublisher.class));
        var recovery = new ProductionTaskRecovery(jobs, mock(ScriptDecomposeService.class), mock(AiTaskService.class),
                mock(AiTaskMapper.class), mock(ShotProductionPlanMapper.class), mock(AgentRunMapper.class), events, new AgentJson());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(recovery, "listen");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            events.publishAfterCommit(new com.astra.freyja.agent.model.AgentProtocol.Event(1L, 100L, 1, "RUN_CANCELLED", Map.of()), false);
            var callback = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().getFirst();
            callback.afterCommit();
            verify(jobs, timeout(3000)).find(100L);
            callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
    @Test void cancellationStopsRedisDescendantsAndSqlCheckpointsWhileKeepingResultsAndOtherTasks() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AiTask.class);
        var jobs = mock(ProductionJobs.class); var tasks = mock(AiTaskService.class);
        var mysql = mock(AiTaskMapper.class); var plans = mock(ShotProductionPlanMapper.class);
        var recovery = new ProductionTaskRecovery(jobs, mock(ScriptDecomposeService.class), tasks, mysql, plans,
                mock(AgentRunMapper.class), mock(AgentEventPublisher.class), new AgentJson());
        var job = new AgentPreparationJob(); job.setRunId(100L); job.setDecompositionTaskId(1L);
        when(jobs.find(100L)).thenReturn(job);
        var root = AiTaskDTO.builder().id(1L).status("RUNNING").build();
        var worker = AiTaskDTO.builder().id(2L).parentTaskId(1L).status("RUNNING").build();
        var pending = AiTaskDTO.builder().id(3L).parentTaskId(2L).status("PENDING").build();
        var finished = AiTaskDTO.builder().id(4L).parentTaskId(1L).status("SUCCESS").outputPayload("saved-result").build();
        var unrelated = AiTaskDTO.builder().id(99L).parentTaskId(98L).status("RUNNING").build();
        var all = List.of(root, worker, pending, finished, unrelated);
        var byId = Map.of(1L, root, 2L, worker, 3L, pending, 4L, finished, 99L, unrelated);
        when(tasks.listTasks(isNull())).thenReturn(all);
        when(tasks.getTaskById(anyLong())).thenAnswer(call -> byId.get((Long) call.getArgument(0)));
        when(tasks.markCancelled(anyLong(), anyBoolean())).thenAnswer(call -> {
            var task = byId.get((Long) call.getArgument(0));
            task.setStatus((boolean) call.getArgument(1) ? "CANCELLED" : "CANCEL_UNCONFIRMED"); task.setFinishedAt(LocalDateTime.now());
            return true;
        });
        recovery.cancelChildren(100L);
        assertEquals("CANCEL_UNCONFIRMED", root.getStatus()); assertEquals("CANCEL_UNCONFIRMED", worker.getStatus());
        assertEquals("CANCELLED", pending.getStatus()); assertEquals("SUCCESS", finished.getStatus());
        assertEquals("saved-result", finished.getOutputPayload()); assertEquals("RUNNING", unrelated.getStatus());
        verify(tasks, never()).markCancelled(eq(4L), anyBoolean()); verify(tasks, never()).markCancelled(eq(99L), anyBoolean());
        verify(mysql, times(3)).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }
}
