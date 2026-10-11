package com.astra.freyja.util;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Never let a worker observe an AI task that its caller has not committed yet. */
public final class AfterCommitTask {
    private AfterCommitTask() {}
    public static void start(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { Thread.ofVirtual().start(action); }
            });
        } else Thread.ofVirtual().start(action);
    }
}
