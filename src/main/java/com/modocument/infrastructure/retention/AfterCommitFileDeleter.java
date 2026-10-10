package com.modocument.infrastructure.retention;

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
 * boundaries for the ws14 destruction paths (retention purge, DSR erase,
 * job deletion), mirroring the doc-creation {@code StoredFileCleanup} idiom:
 * a file delete must only hit the disk once the row changes have committed —
 * a rollback after the bytes are gone would brick the surviving records.
 * Outside a transaction the deletes run immediately.
 */
public final class AfterCommitFileDeleter {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitFileDeleter.class);

    private AfterCommitFileDeleter() {
    }

    /** Deletes the files only after the surrounding transaction commits. */
    public static void deleteAfterCommit(List<Path> paths) {
        if (paths.isEmpty()) {
            return;
        }
        List<Path> toDelete = List.copyOf(paths);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    toDelete.forEach(AfterCommitFileDeleter::deleteQuietly);
                }
            });
        } else {
            toDelete.forEach(AfterCommitFileDeleter::deleteQuietly);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete stored file {}", path, e);
        }
    }
}
