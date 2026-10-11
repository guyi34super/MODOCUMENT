package com.modocument.infrastructure.retention;

import com.modocument.application.port.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.List;

/**
 * Keeps stored files consistent with the database across transaction
 * boundaries for the ws14 destruction paths (retention purge, DSR erase,
 * job deletion), mirroring the doc-creation {@code StoredFileCleanup} idiom:
 * a file delete must only hit the store once the row changes have committed —
 * a rollback after the bytes are gone would brick the surviving records.
 * Outside a transaction the deletes run immediately. Works on store keys
 * (ws29 {@link FileStore}), so the same code deletes from disk or S3.
 */
public final class AfterCommitFileDeleter {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitFileDeleter.class);

    private AfterCommitFileDeleter() {
    }

    /** Deletes the keys from the store only after the surrounding transaction commits. */
    public static void deleteAfterCommit(FileStore store, List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        List<String> toDelete = List.copyOf(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    toDelete.forEach(key -> deleteQuietly(store, key));
                }
            });
        } else {
            toDelete.forEach(key -> deleteQuietly(store, key));
        }
    }

    private static void deleteQuietly(FileStore store, String key) {
        try {
            store.delete(key);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete stored file {}", key, e);
        }
    }
}
