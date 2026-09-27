package com.modocument.doccreation.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Keeps stored files consistent with the database across transaction
 * boundaries: a delete must only hit the disk once the row delete has
 * committed (a rollback after the file is gone bricks the record), and a
 * file stored during a transaction must be removed when that transaction
 * rolls back (otherwise it is orphaned forever). Outside a transaction the
 * action runs immediately.
 */
final class StoredFileCleanup {

    private static final Logger log = LoggerFactory.getLogger(StoredFileCleanup.class);

    private StoredFileCleanup() {
    }

    /** Deletes the files only after the surrounding transaction commits. */
    static void deleteAfterCommit(List<Path> paths) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    paths.forEach(StoredFileCleanup::deleteQuietly);
                }
            });
        } else {
            paths.forEach(StoredFileCleanup::deleteQuietly);
        }
    }

    /** Removes a freshly stored file when the surrounding transaction rolls back. */
    static void deleteOnRollback(Path path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteQuietly(path);
                }
            }
        });
    }

    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete stored file {}", path, e);
        }
    }
}
