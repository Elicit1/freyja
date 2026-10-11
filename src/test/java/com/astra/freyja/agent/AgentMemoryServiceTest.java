package com.astra.freyja.agent;

import com.astra.freyja.agent.memory.AgentMemoryService;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.dao.AgentMemoryMapper;
import com.astra.freyja.dao.AgentMessageMapper;
import com.astra.freyja.entity.AgentMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentMemoryServiceTest {
    @Test void projectMemoryCannotBeSavedWithoutBoundProjectOrRealUserSource() {
        AgentTestSupport.metadata();
        var memory = mock(AgentMemoryMapper.class); var messages = mock(AgentMessageMapper.class);
        var service = new AgentMemoryService(memory, messages);
        Snapshot snapshot = AgentTestSupport.snapshot(List.of(), "NORMAL");
        ToolContext context = new ToolContext(1L, 2L, 3L, null, 4L, "key", snapshot);
        var input = new AgentMemoryService.SaveNote("PROJECT", "style", "写实风格", 4L, true);
        assertEquals("INVALID_SOURCE", service.save(input, context).errorCode());
        AgentMessage source = new AgentMessage(); source.setMessageType("USER"); source.setSessionId(1L); source.setRunId(2L);
        when(messages.selectById(4L)).thenReturn(source);
        assertEquals("NO_PROJECT_SCOPE", service.save(input, context).errorCode());
        verify(memory, never()).insert(any(com.astra.freyja.entity.AgentMemory.class));
    }
    @Test void testRunsNeverReadOrWriteRealMemory() {
        var mapper = mock(AgentMemoryMapper.class); var messages = mock(AgentMessageMapper.class);
        var service = new AgentMemoryService(mapper, messages);
        ToolContext context = new ToolContext(1L, 2L, 3L, 4L, 5L, "key", AgentTestSupport.snapshot(List.of(), "CONFIG_TEST"));
        assertTrue(service.list(context).isEmpty());
        assertEquals("READ_ONLY_TEST", service.save(new AgentMemoryService.SaveNote("PROJECT", "key", "value", 5L, true), context).errorCode());
        verifyNoInteractions(mapper, messages);
    }
    @Test void disabledMemoryScopeCannotBeWritten() {
        var mapper = mock(AgentMemoryMapper.class); var messages = mock(AgentMessageMapper.class);
        var service = new AgentMemoryService(mapper, messages);
        Snapshot snapshot = AgentTestSupport.snapshot(List.of(), "NORMAL");
        snapshot.config().getMemory().setScopes(List.of());
        ToolContext context = new ToolContext(1L, 2L, 3L, 4L, 5L, "key", snapshot);
        assertEquals("SCOPE_DISABLED", service.save(new AgentMemoryService.SaveNote("PROJECT", "key", "value", 5L, true), context).errorCode());
        verifyNoInteractions(mapper, messages);
    }
}
