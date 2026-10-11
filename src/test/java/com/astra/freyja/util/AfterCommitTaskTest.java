package com.astra.freyja.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AfterCommitTaskTest {
    @AfterEach void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    void transaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
    @Test void commitStartsOnlyWorkerAfterCommit() throws Exception {
        transaction(); var worker = new CountDownLatch(1); var rollback = new AtomicInteger();
        AfterCommitTask.start(worker::countDown, rollback::incrementAndGet);
        assertEquals(1, worker.getCount());
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        callback.afterCommit(); callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertTrue(worker.await(3, TimeUnit.SECONDS)); assertEquals(0, rollback.get());
    }
    @Test void rollbackEndsDraftWithoutStartingWorker() throws Exception {
        transaction(); var worker = new AtomicInteger(); var rollback = new CountDownLatch(1);
        AfterCommitTask.start(worker::incrementAndGet, rollback::countDown);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertTrue(rollback.await(3, TimeUnit.SECONDS)); assertEquals(0, worker.get());
    }
    @Test void standaloneWorkStartsImmediatelyWithoutRollback() throws Exception {
        var worker = new CountDownLatch(1); var rollback = new AtomicInteger();
        AfterCommitTask.start(worker::countDown, rollback::incrementAndGet);
        assertTrue(worker.await(3, TimeUnit.SECONDS)); assertEquals(0, rollback.get());
    }
}
