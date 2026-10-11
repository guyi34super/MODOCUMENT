package com.modocument.doccreation.usecase;

import com.modocument.application.port.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.List;

/**
 * Keeps stored files consistent with the database across transaction
 * boundaries: a delete must only hit the store once the row delete has
 * committed (a rollback after the file is gone bricks the record), and a
 * file stored during a transaction must be removed when that transaction
 * rolls back (otherwise it is orphaned forever). Outside a transaction the
 * action runs immediately. Works on store keys (ws29 {@link FileStore}).
 */
final class StoredFileCleanup {

    private static final Logger log = LoggerFactory.getLogger(StoredFileCleanup.class);

    private StoredFileCleanup() {
    }

    /** Deletes the keys only after the surrounding transaction commits. */
    static void deleteAfterCommit(FileStore store, List<String> keys) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    keys.forEach(key -> deleteQuietly(store, key));
                }
            });
        } else {
            keys.forEach(key -> deleteQuietly(store, key));
        }
    }

    /** Removes a freshly stored file when the surrounding transaction rolls back. */
    static void deleteOnRollback(FileStore store, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteQuietly(store, key);
                }
            }
        });
    }

    static void deleteQuietly(FileStore store, String key) {
        try {
            store.delete(key);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete stored file {}", key, e);
        }
    }
}
