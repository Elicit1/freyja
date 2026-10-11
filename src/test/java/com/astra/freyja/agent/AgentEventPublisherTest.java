package com.astra.freyja.agent;

import com.astra.freyja.agent.event.AgentEventPublisher;
import com.astra.freyja.agent.model.AgentProtocol.Event;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentEventPublisherTest {
    @Test void eventsAreInvisibleBeforeCommitAndRollbackDoesNotPublish() {
        ApplicationEventPublisher application = mock(ApplicationEventPublisher.class);
        AgentEventPublisher publisher = new AgentEventPublisher(application);
        List<Event> seen = new ArrayList<>(); publisher.addListener(seen::add);
        Event event = new Event(1L, 2L, 3, "RUN_COMPLETED", "result");
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishAfterCommit(event, false);
            assertTrue(seen.isEmpty()); verifyNoInteractions(application);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertTrue(seen.isEmpty()); verifyNoInteractions(application);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishAfterCommit(event, false);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertEquals(List.of(event), seen); verify(application).publishEvent(any(Object.class));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void testRunEventsDoNotEnterTaskCenter() {
        ApplicationEventPublisher application = mock(ApplicationEventPublisher.class);
        AgentEventPublisher publisher = new AgentEventPublisher(application);
        List<Event> seen = new ArrayList<>(); publisher.addListener(seen::add);
        publisher.publishAfterCommit(new Event(1L, 2L, 3, "RUN_COMPLETED", "test result"), true);
        assertEquals(1, seen.size()); verifyNoInteractions(application);
    }
}
