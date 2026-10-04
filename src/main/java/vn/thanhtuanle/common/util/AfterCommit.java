package vn.thanhtuanle.common.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs a side effect only once the surrounding transaction has committed. */
public final class AfterCommit {

    private AfterCommit() {
    }

    /** After the current transaction commits, or immediately when no transaction is active (unit tests). */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
