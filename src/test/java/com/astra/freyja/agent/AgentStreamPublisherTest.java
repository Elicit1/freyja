package com.astra.freyja.agent;

import com.astra.freyja.agent.event.*;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.dao.AgentRunMapper;
import com.astra.freyja.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentStreamPublisherTest {
    AgentRunMapper runs = mock(AgentRunMapper.class);
    ApplicationEventPublisher application = mock(ApplicationEventPublisher.class);
    AgentEventPublisher events = new AgentEventPublisher(application);
    AgentStreamPublisher streams = new AgentStreamPublisher(runs, events);
    List<StreamEvent> seen = new ArrayList<>();
    AgentRun run;
    AgentStep step;
    @BeforeEach void setup() {
        run = AgentTestSupport.run(AgentTestSupport.snapshot(List.of(), "NORMAL"), new AgentJson());
        when(runs.selectById(run.getId())).thenReturn(run);
        step = new AgentStep(); step.setId(AgentTestSupport.STEP); step.setConstraintVersion(0);
        streams.addListener(seen::add);
    }
    AgentStreamPublisher.Channel open() { return streams.open(run.getId(), run.getSessionId(), "test-owner", 1, step); }
    @Test void transientPreviewHasIndependentSequenceAndReplaySnapshotAndNoTaskUpdates() {
        try (var channel = open()) {
            channel.accept("灯");
            assertEquals("灯", streams.current(run.getSessionId()).getFirst().text());
            assertEquals(1, seen.getFirst().sequence());
            assertTrue(streams.current(999L).isEmpty());
            verifyNoInteractions(application);
        }
        assertTrue(streams.current(run.getSessionId()).isEmpty()); assertEquals("CLOSED", seen.getLast().type());
    }
    @Test void steeringAndTerminalEventsPreventLateChunksAndClearSnapshots() {
        for (String type : List.of("USER_INPUT_SAVED", "MODEL_RETRY", "RUN_COMPLETED", "RUN_CANCELLED", "RUN_FAILED", "RUN_STARTED")) {
            try (var channel = open()) {
                channel.accept("旧要求");
                events.publishAfterCommit(new Event(run.getSessionId(), run.getId(), 45, type, Map.of()), true);
                int count = seen.size(); channel.accept("过时文字");
                assertEquals(count, seen.size()); assertTrue(streams.current(run.getSessionId()).isEmpty());
                assertEquals("CLOSED", seen.getLast().type());
            }
        }
    }
    @Test void staleConstraintExpiredLeaseAndReplacementOwnerCannotPublish() {
        run.setConstraintVersion(1);
        try (var channel = open()) { channel.accept("过时文字"); }
        run.setConstraintVersion(0); run.setLeaseEpoch(2);
        try (var channel = open()) { channel.accept("过时文字"); }
        run.setLeaseEpoch(1); run.setLeaseUntil(java.time.LocalDateTime.now().minusSeconds(1));
        try (var channel = open()) { channel.accept("过时文字"); }
        assertTrue(seen.stream().allMatch(e -> "CLOSED".equals(e.type())));
    }
    @Test void retryUsesNewStreamIdentityAndOldCloseDoesNotRemoveNewPreview() {
        var old = open(); old.accept("旧尝试");
        try (var replacement = open()) {
            replacement.accept("新尝试"); old.close(); old.accept("晚到的旧尝试");
            var preview = streams.current(run.getSessionId()).getFirst();
            assertNotEquals(seen.getFirst().streamId(), preview.streamId()); assertEquals("新尝试", preview.text());
        }
    }
}
