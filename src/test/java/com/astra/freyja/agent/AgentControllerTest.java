package com.astra.freyja.agent;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.runtime.AgentExecutionScheduler;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.agent.settings.AgentConfigService;
import com.astra.freyja.controller.AgentController;
import com.astra.freyja.dto.agent.AgentRequests.*;
import com.astra.freyja.entity.AgentRun;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class AgentControllerTest {
    AgentStore store = mock(AgentStore.class);
    AgentExecutionScheduler scheduler = mock(AgentExecutionScheduler.class);
    AgentController controller = new AgentController(store, mock(AgentConfigService.class), scheduler, new AgentJson());
    SendMessage replacement(Long expected) {
        var request = new SendMessage(); request.setIntent("REPLACE_RUN"); request.setExpectedRunId(expected);
        return request;
    }
    @Test void acceptedReplacementInterruptsOnlyCancelledRunInSameSession() {
        var request = replacement(42L);
        when(store.submit(1L, request, null)).thenReturn(new Submission(1L, 2L, 3L));
        var previous = new AgentRun(); previous.setId(42L); previous.setSessionId(1L); previous.setStatus("CANCELLED");
        when(store.run(42L)).thenReturn(previous);
        controller.send(1L, request);
        verify(scheduler).interrupt(42L);
    }
    @Test void duplicateCannotInterruptAnotherActiveTask() {
        var request = replacement(42L);
        when(store.submit(1L, request, null)).thenReturn(new Submission(1L, 2L, 3L));
        var previous = new AgentRun(); previous.setId(42L); previous.setSessionId(1L); previous.setStatus("RUNNING");
        when(store.run(42L)).thenReturn(previous);
        controller.send(1L, request);
        verifyNoInteractions(scheduler);
    }
    @Test void duplicateCannotInterruptTaskInAnotherSession() {
        var request = replacement(42L);
        when(store.submit(1L, request, null)).thenReturn(new Submission(1L, 2L, 3L));
        var previous = new AgentRun(); previous.setId(42L); previous.setSessionId(999L); previous.setStatus("CANCELLED");
        when(store.run(42L)).thenReturn(previous);
        controller.send(1L, request);
        verifyNoInteractions(scheduler);
    }
}
