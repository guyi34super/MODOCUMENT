package com.modocument.infrastructure.retention;

import com.modocument.application.port.FileStore;
import com.modocument.domain.entity.PendingFileDelete;
import com.modocument.infrastructure.persistence.PendingFileDeleteJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-12, the durable half of "a failed delete is retried, not lost": a key
 * the store still refuses after the commit and the in-call retries is
 * written to PendingFileDelete (in its own transaction, from afterCommit),
 * a failed sweep retry keeps it with backoff, and the sweep deletes the
 * object and the row once the store works again. Under row level security:
 * PendingFileDeleteRlsPgIT.
 */
@SpringBootTest
// Join the shared MockMvc context of the API suites (test plan G9).
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PendingFileDeleteSweeperTest {

    private static final int BANK = 1;

    @Autowired
    private PendingFileDeletes pending;
    @Autowired
    private PendingFileDeleteSweeper sweeper;
    @Autowired
    private PendingFileDeleteJpaRepository repository;
    @Autowired
    private FileStore storage;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    /** A store whose deletes fail until {@code healthy} is set, delegating everything else. */
    private static final class OutageStore implements FileStore {
        private final FileStore delegate;
        volatile boolean healthy;

        OutageStore(FileStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String key, InputStream content, long length, String contentType) throws IOException {
            delegate.put(key, content, length, contentType);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public Path localCopy(String key) throws IOException {
            return delegate.localCopy(key);
        }

        @Override
        public boolean exists(String key) throws IOException {
            return delegate.exists(key);
        }

        @Override
        public void delete(String key) throws IOException {
            if (!healthy) {
                throw new IOException("503 Slow Down");
            }
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) throws IOException {
            return delegate.list(prefix);
        }
    }

    private String storedFile() throws IOException {
        String key = "uploads/bug12-" + UUID.randomUUID() + ".csv";
        storage.put(key, new ByteArrayInputStream("Name\nAda\n".getBytes()), 9, "text/csv");
        return key;
    }

    @Test
    void aDeleteThatFailsPastTheCommitIsQueuedRetriedWithBackoffAndThenDeleted() throws Exception {
        String key = storedFile();
        OutageStore outage = new OutageStore(storage);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                AfterCommitFileDeleter.deleteAfterCommit(outage, List.of(key), BANK, pending));

        PendingFileDelete row = repository.findByBankIdAndStorageKey(BANK, key).orElseThrow();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isEqualTo("IOException: 503 Slow Down");
        assertThat(storage.exists(key)).as("the object is still there").isTrue();

        // The outage lasts: the sweep's retry fails, the row stays with backoff.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(1);
        assertThat(pending.retryDue(BANK, outage, now)).isZero();
        row = repository.findByBankIdAndStorageKey(BANK, key).orElseThrow();
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isEqualTo(now.plus(PendingFileDeletes.FIRST_BACKOFF));
        assertThat(pending.retryDue(BANK, outage, now.plusSeconds(60)))
                .as("not due before its backoff").isZero();
        assertThat(repository.findByBankIdAndStorageKey(BANK, key).orElseThrow().getAttempts())
                .isEqualTo(1);

        // The store recovers: the next due retry deletes the object and the row.
        outage.healthy = true;
        assertThat(pending.retryDue(BANK, outage, now.plus(Duration.ofMinutes(6)))).isEqualTo(1);
        assertThat(storage.exists(key)).isFalse();
        assertThat(repository.findByBankIdAndStorageKey(BANK, key)).isEmpty();
    }

    @Test
    void theSweepDeletesEveryBanksDueObjectsFromTheStore() throws Exception {
        String key = storedFile();
        pending.enqueue(BANK, key, new IOException("403 AccessDenied"));
        pending.enqueue(BANK, key, new IOException("403 AccessDenied"));
        assertThat(repository.count()).as("queuing a key twice is one row").isEqualTo(1);

        assertThat(sweeper.runOnce()).isEqualTo(1);

        assertThat(storage.exists(key)).isFalse();
        assertThat(repository.count()).isZero();
    }

    @Test
    void theBackoffDoublesFromFiveMinutesUpToSixHours() {
        assertThat(PendingFileDeletes.backoff(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(PendingFileDeletes.backoff(2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(PendingFileDeletes.backoff(4)).isEqualTo(Duration.ofMinutes(40));
        assertThat(PendingFileDeletes.backoff(7)).isEqualTo(Duration.ofMinutes(320));
        assertThat(PendingFileDeletes.backoff(8)).isEqualTo(Duration.ofHours(6));
        assertThat(PendingFileDeletes.backoff(1_000)).isEqualTo(Duration.ofHours(6));
    }
}
