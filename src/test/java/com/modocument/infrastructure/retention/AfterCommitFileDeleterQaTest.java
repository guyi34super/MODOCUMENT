package com.modocument.infrastructure.retention;

import com.modocument.application.port.FileStore;
import com.modocument.infrastructure.file.store.LocalFileStore;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * QA, optimization round, test plan F4 (contracts §7): when the retention
 * purge, the DSR erase or a job deletion has committed its row deletes, the
 * stored object must go too — and a failed object delete must be retried,
 * not lost. Today {@link AfterCommitFileDeleter} logs the failure and drops
 * the key: the database no longer points at the file, so nothing ever
 * deletes it (customer data outliving its retention / erasure). The S3 SDK's
 * own retries cover short blips only. BUG-12 (docs/qa/optimization-signoff.md).
 */
class AfterCommitFileDeleterQaTest {

    @TempDir
    Path root;

    /** A store whose first {@code failures} deletes fail like an S3 503 / a missing IAM grant. */
    private static FileStore flaky(FileStore delegate, int failures, AtomicInteger attempts) {
        return new FileStore() {
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
                if (attempts.incrementAndGet() <= failures) {
                    throw new IOException("503 Slow Down");
                }
                delegate.delete(key);
            }

            @Override
            public List<String> list(String prefix) throws IOException {
                return delegate.list(prefix);
            }
        };
    }

    @Test
    void aDeleteThatSucceedsRemovesTheObject() throws Exception {
        FileStore store = new LocalFileStore(root);
        store.put("uploads/a.csv", new ByteArrayInputStream("x".getBytes()), 1, "text/csv");

        AfterCommitFileDeleter.deleteAfterCommit(store, List.of("uploads/a.csv"));

        assertThat(store.exists("uploads/a.csv")).isFalse();
    }

    @Test
    @Disabled("BUG-12: AfterCommitFileDeleter logs a failed object delete and drops the key - "
            + "no retry, the stored file outlives its purged/erased row (test plan F4)")
    void aFailedDeleteIsRetriedNotLost() throws Exception {
        FileStore local = new LocalFileStore(root);
        local.put("uploads/b.csv", new ByteArrayInputStream("x".getBytes()), 1, "text/csv");
        AtomicInteger attempts = new AtomicInteger();
        FileStore store = flaky(local, 1, attempts);

        AfterCommitFileDeleter.deleteAfterCommit(store, List.of("uploads/b.csv"));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(local.exists("uploads/b.csv"))
                        .as("the object is deleted after the transient failure").isFalse());
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
    }
}
