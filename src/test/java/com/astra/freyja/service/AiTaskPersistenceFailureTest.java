package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiTaskMapper;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.service.impl.AiTaskServiceImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiTaskPersistenceFailureTest {
    AiTaskMapper mapper = mock(AiTaskMapper.class);
    AiTaskServiceImpl service = new AiTaskServiceImpl(mapper);
    @Test void failedInsertNeverReturnsAnUnpersistedTaskId() {
        when(mapper.insert(any(AiTask.class))).thenThrow(new IllegalStateException("database unavailable"));
        var error = assertThrows(BizException.class, () -> service.createTask(null, null, AiTaskType.SHOT_PROMPT_DERIVE, "1", null, "{}", "model", 100));
        assertEquals(503, error.getCode());
    }
    @Test void failedReadAndMissingRetryAreNotFabricatedStates() {
        when(mapper.selectById(1L)).thenThrow(new IllegalStateException("database unavailable"));
        assertEquals(503, assertThrows(BizException.class, () -> service.getTaskById(1L)).getCode());
        assertEquals(404, assertThrows(BizException.class, () -> service.retryTask(2L)).getCode());
        verify(mapper, never()).updateById(any(AiTask.class));
    }
}
