package com.astra.freyja.service;

import com.astra.freyja.event.RenderQueueChangedEvent;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenderDispatchCoordinatorTest {
    private final AtomicBoolean videoPending = new AtomicBoolean();
    private final AtomicInteger admissionReads = new AtomicInteger();
    private final CountDownLatch pendingRead = new CountDownLatch(1);

    private RenderDispatchCoordinator coordinator() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(invocation -> {
            Connection connection = mock(Connection.class);
            when(connection.getCatalog()).thenReturn("freyja");
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                PreparedStatement statement = mock(PreparedStatement.class);
                when(statement.executeQuery()).thenAnswer(query -> {
                    ResultSet result = mock(ResultSet.class);
                    when(result.next()).thenReturn(true, false);
                    if (sql.contains("GET_LOCK")) when(result.getInt(1)).thenReturn(1);
                    else if (sql.contains("COUNT(*)")) {
                        admissionReads.incrementAndGet();
                        when(result.getLong(1)).thenReturn(videoPending.get() ? 1L : 0L);
                        pendingRead.countDown();
                    }
                    return result;
                });
                return statement;
            });
            return connection;
        });
        return new RenderDispatchCoordinator(ds);
    }

    @Test void singleSubmissionsWaitForThePreviousOperationToFinish() throws Exception {
        var coordinator = coordinator();
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> coordinator.executeSingle(() -> {
                order.add("first-start"); entered.countDown();
                await(release); order.add("first-finish"); return 1;
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var second = executor.submit(() -> coordinator.executeSingle(() -> { order.add("second-start"); return 2; }));
            try { assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            assertEquals(1, first.get(3, TimeUnit.SECONDS));
            assertEquals(2, second.get(3, TimeUnit.SECONDS));
        }
        assertEquals(List.of("first-start", "first-finish", "second-start"), order);
    }

    @Test void queuedBatchBlocksSinglesUntilACompletionEventWithoutPolling() throws Exception {
        var coordinator = coordinator(); videoPending.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var single = executor.submit(() -> coordinator.executeSingle(() -> "image"));
            assertTrue(pendingRead.await(3, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> single.get(150, TimeUnit.MILLISECONDS));
                assertEquals(1, admissionReads.get(), "Waiting must not periodically query the database");
            } finally {
                coordinator.executeVideoQueue(() -> videoPending.set(false));
                coordinator.queueChanged(new RenderQueueChangedEvent());
            }
            assertEquals("image", single.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancelledWaiterNeverInvokesGeneration() throws Exception {
        var coordinator = coordinator(); videoPending.set(true);
        AtomicBoolean submitted = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var single = executor.submit(() -> coordinator.executeSingle(() -> { submitted.set(true); return null; }));
            assertTrue(pendingRead.await(3, TimeUnit.SECONDS));
            assertTrue(single.cancel(true));
        }
        assertFalse(submitted.get());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
    }
}
