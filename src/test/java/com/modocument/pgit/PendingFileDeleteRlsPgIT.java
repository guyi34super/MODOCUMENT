package com.modocument.pgit;

import com.modocument.application.port.FileStore;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.PendingFileDelete;
import com.modocument.infrastructure.persistence.PendingFileDeleteJpaRepository;
import com.modocument.infrastructure.retention.AfterCommitFileDeleter;
import com.modocument.infrastructure.retention.PendingFileDeleteSweeper;
import com.modocument.infrastructure.retention.PendingFileDeletes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-12 (ws29, V290) on the REAL schema with row level security ENFORCED:
 * a stored-object delete that keeps failing after the purge/erase/deletion
 * transaction committed is queued in PendingFileDelete from
 * {@code afterCommit} (its own REQUIRES_NEW transaction, under the bank's
 * tenant), another bank neither sees nor can write that bank's rows, and
 * {@link PendingFileDeleteSweeper#runOnce()} (SYSTEM listing of bank ids,
 * then {@code runAs(bank)}) deletes the object and removes the row.
 */
class PendingFileDeleteRlsPgIT extends PgSpringItSupport {

    private static final int BANK_A = 892;
    private static final int BANK_B = 893;
    private static final String KEY = "uploads/pg-bug12-" + System.nanoTime() + ".csv";

    @Autowired
    private PendingFileDeletes pending;
    @Autowired
    private PendingFileDeleteSweeper sweeper;
    @Autowired
    private PendingFileDeleteJpaRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            sql.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(892, 'PFD_A', 'Pending delete A'), (893, 'PFD_B', 'Pending delete B') "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("DELETE FROM PendingFileDelete WHERE BankId IN (892, 893)");
        }
    }

    /** A store that refuses every delete, like S3 without the s3:DeleteObject grant. */
    private static final FileStore REFUSING = new FileStore() {
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
            return true;
        }

        @Override
        public void delete(String key) throws IOException {
            throw new IOException("403 AccessDenied");
        }

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }
    };

    @Test
    void aFailedDeleteIsQueuedInItsBankOnlyAndTheSweepDeletesIt() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        TenantContext.runAs(BANK_A, () -> transaction.executeWithoutResult(status ->
                AfterCommitFileDeleter.deleteAfterCommit(REFUSING, List.of(KEY), BANK_A, pending)));
        assertThat(count("SELECT count(*) FROM PendingFileDelete WHERE BankId = 892 AND StorageKey = '"
                + KEY + "'")).as("queued after commit, in bank A").isEqualTo(1);

        Instant later = Instant.now().plusSeconds(1);
        List<PendingFileDelete> seenByB = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                repository.findDue(BANK_A, later, PageRequest.of(0, 10))));
        assertThat(seenByB).as("RLS hides bank A's queue from bank B, even when it names bank A").isEmpty();
        TenantContext.runAs(BANK_B, () -> pending.enqueue(BANK_A, "uploads/pg-bug12-forged.csv",
                new IOException("x")));
        assertThat(count("SELECT count(*) FROM PendingFileDelete WHERE StorageKey = "
                + "'uploads/pg-bug12-forged.csv'")).as("WITH CHECK refuses bank B writing for bank A")
                .isZero();

        // The local store of the context has no such object; deleting an
        // absent object succeeds, as on S3 — the row goes.
        assertThat(sweeper.runOnce()).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM PendingFileDelete WHERE BankId = 892"))
                .as("the sweep removed the row once the delete succeeded").isZero();
    }

    /** Read as the owner (bypasses RLS): the truth on disk. */
    private static long count(String query) throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement();
             ResultSet rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
