package com.modocument.infrastructure.retention;

import com.modocument.application.port.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Keeps stored files consistent with the database across transaction
 * boundaries for the ws14 destruction paths (retention purge, DSR erase,
 * job deletion), mirroring the doc-creation {@code StoredFileCleanup} idiom:
 * a file delete must only hit the store once the row changes have committed —
 * a rollback after the bytes are gone would brick the surviving records.
 * Outside a transaction the deletes run immediately. Works on store keys
 * (ws29 {@link FileStore}), so the same code deletes from disk or S3.
 *
 * <p><b>A failed delete is retried, not lost (BUG-12).</b> Once the rows are
 * gone nothing else points at the object, so a dropped delete would let
 * customer data outlive its retention or erasure:
 * <ol>
 *   <li>each key gets {@value #ATTEMPTS} attempts in the call, with a short
 *       backoff ({@link #BACKOFF}) — enough for an S3 503 "Slow Down" blip
 *       the SDK's own retries did not absorb. Once one key has used them all
 *       the store is treated as down for the rest of the batch: the
 *       remaining keys get one attempt each, so an outage never stalls a
 *       large purge on sleeps;</li>
 *   <li>a key that still fails goes to the {@link FailedDeleteQueue}
 *       ({@link PendingFileDeletes}: the durable PendingFileDelete table,
 *       V290), which {@link PendingFileDeleteSweeper} retries with backoff
 *       until the store confirms the delete — across restarts, a throttling
 *       storm or a missing {@code s3:DeleteObject} grant fixed later.</li>
 * </ol>
 * Without a queue (unit wiring only) a key that fails every in-call attempt
 * is logged at ERROR.
 */
public final class AfterCommitFileDeleter {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitFileDeleter.class);

    /** In-call attempts per key while the store looks healthy. */
    static final int ATTEMPTS = 3;

    /** Sleep before the 2nd, 3rd … in-call attempt. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMillis(100), Duration.ofMillis(500));

    /** Where a key goes when the in-call attempts are used up. */
    @FunctionalInterface
    public interface FailedDeleteQueue {

        /** Remembers {@code key} of {@code bankId} for a later retry; never throws. */
        void enqueue(int bankId, String key, Exception lastError);
    }

    private AfterCommitFileDeleter() {
    }

    /** Deletes the keys after commit, with in-call retries only (unit wiring). */
    public static void deleteAfterCommit(FileStore store, List<String> keys) {
        deleteAfterCommit(store, keys, 0, null);
    }

    /**
     * Deletes the keys from the store only after the surrounding transaction
     * commits; a key that keeps failing is handed to {@code queue} under
     * {@code bankId} (the bank whose rows referenced it).
     */
    public static void deleteAfterCommit(FileStore store, List<String> keys, int bankId,
                                         FailedDeleteQueue queue) {
        if (keys.isEmpty()) {
            return;
        }
        List<String> toDelete = List.copyOf(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteAll(store, toDelete, bankId, queue);
                }
            });
        } else {
            deleteAll(store, toDelete, bankId, queue);
        }
    }

    static void deleteAll(FileStore store, List<String> keys, int bankId, FailedDeleteQueue queue) {
        boolean storeHealthy = true;
        for (String key : keys) {
            int allowed = storeHealthy ? ATTEMPTS : 1;
            Exception failure = null;
            int attempt = 0;
            while (attempt < allowed) {
                if (attempt > 0 && !pause(BACKOFF.get(Math.min(attempt, BACKOFF.size()) - 1))) {
                    break;
                }
                attempt++;
                try {
                    store.delete(key);
                    failure = null;
                    break;
                } catch (IOException | RuntimeException e) {
                    failure = e;
                }
            }
            if (failure == null) {
                continue;
            }
            storeHealthy = false;
            if (queue == null) {
                log.error("Could not delete stored file {} after {} attempt(s); no retry queue "
                        + "is wired, the object stays", key, attempt, failure);
                continue;
            }
            log.warn("Could not delete stored file {} after {} attempt(s); queued for retry: {}",
                    key, attempt, failure.toString());
            queue.enqueue(bankId, key, failure);
        }
    }

    /** Sleeps; false when interrupted (the interrupt flag is restored). */
    private static boolean pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
