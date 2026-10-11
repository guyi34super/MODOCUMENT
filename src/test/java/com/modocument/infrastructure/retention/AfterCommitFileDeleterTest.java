package com.modocument.infrastructure.retention;

import com.modocument.application.port.FileStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-12: the in-call half of "a failed delete is retried, not lost" — a
 * key gets {@value AfterCommitFileDeleter#ATTEMPTS} attempts, then goes to
 * the retry queue with its bank; once the store looks down the rest of the
 * batch gets one attempt each (no sleeps per key); nothing is deleted or
 * queued before the commit. The durable half: PendingFileDeleteSweeperTest
 * and PendingFileDeleteRlsPgIT.
 */
class AfterCommitFileDeleterTest {

    record Queued(int bankId, String key, String error) {
    }

    /** Counts deletes per key; the first {@code failures} deletes of each key fail. */
    private static final class CountingStore implements FileStore {
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final List<String> deleted = new ArrayList<>();
        private final int failures;

        CountingStore(int failures) {
            this.failures = failures;
        }

        @Override
        public void put(String key, InputStream content, long length, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream open(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path localCopy(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String key) {
            return !deleted.contains(key);
        }

        @Override
        public void delete(String key) throws IOException {
            if (attempts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() <= failures) {
                throw new IOException("503 Slow Down");
            }
            deleted.add(key);
        }

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }

        int attemptsOf(String key) {
            AtomicInteger count = attempts.get(key);
            return count == null ? 0 : count.get();
        }
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aTransientFailureIsRetriedInTheCallAndNothingIsQueued() {
        CountingStore store = new CountingStore(2);
        List<Queued> queued = new ArrayList<>();

        AfterCommitFileDeleter.deleteAfterCommit(store, List.of("uploads/a.csv"), 7,
                (bank, key, error) -> queued.add(new Queued(bank, key, error.getMessage())));

        assertThat(store.attemptsOf("uploads/a.csv")).isEqualTo(3);
        assertThat(store.deleted).containsExactly("uploads/a.csv");
        assertThat(queued).isEmpty();
    }

    @Test
    void aPersistentFailureIsQueuedWithItsBankAndTheRestOfTheBatchGetsOneAttemptEach() {
        CountingStore store = new CountingStore(Integer.MAX_VALUE);
        List<Queued> queued = new ArrayList<>();

        AfterCommitFileDeleter.deleteAfterCommit(store,
                List.of("uploads/a.csv", "uploads/b.csv", "exports/u1/job2.csv"), 7,
                (bank, key, error) -> queued.add(new Queued(bank, key, error.getMessage())));

        assertThat(store.attemptsOf("uploads/a.csv")).isEqualTo(AfterCommitFileDeleter.ATTEMPTS);
        assertThat(store.attemptsOf("uploads/b.csv")).as("the store looks down: one attempt").isEqualTo(1);
        assertThat(store.attemptsOf("exports/u1/job2.csv")).isEqualTo(1);
        assertThat(queued).containsExactly(
                new Queued(7, "uploads/a.csv", "503 Slow Down"),
                new Queued(7, "uploads/b.csv", "503 Slow Down"),
                new Queued(7, "exports/u1/job2.csv", "503 Slow Down"));
    }

    @Test
    void nothingIsDeletedOrQueuedBeforeTheCommitAndNothingAfterARollback() {
        CountingStore store = new CountingStore(Integer.MAX_VALUE);
        List<Queued> queued = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        AfterCommitFileDeleter.deleteAfterCommit(store, List.of("uploads/a.csv"), 7,
                (bank, key, error) -> queued.add(new Queued(bank, key, error.getMessage())));
        assertThat(store.attemptsOf("uploads/a.csv")).isZero();

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(store.attemptsOf("uploads/a.csv")).as("rolled back: the rows still need it").isZero();
        assertThat(queued).isEmpty();

        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(store.attemptsOf("uploads/a.csv")).isEqualTo(AfterCommitFileDeleter.ATTEMPTS);
        assertThat(queued).extracting(Queued::key).containsExactly("uploads/a.csv");
    }
}
