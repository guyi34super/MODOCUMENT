package com.modocument.infrastructure.retention;

import com.modocument.application.port.FileStore;
import com.modocument.domain.entity.PendingFileDelete;
import com.modocument.infrastructure.persistence.PendingFileDeleteJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The durable retry queue of failed stored-object deletes (ws29, V290,
 * BUG-12): {@link AfterCommitFileDeleter} hands it every key the store still
 * refused after the in-call retries, and {@link PendingFileDeleteSweeper}
 * drains it per bank. A row is removed only once the store confirmed the
 * delete (deleting an absent object succeeds on disk and on S3), so a key is
 * never lost while its object may still exist — it is due at the next sweep,
 * then retried with exponential backoff ({@link #backoff}: 5 minutes doubling
 * to 6 hours) for as long as it takes ({@code Attempts} counts the sweep's
 * retries), and logged at ERROR from {@value #ERROR_AFTER} failed retries on so a persistent cause (a missing {@code s3:DeleteObject}
 * grant) gets noticed.
 *
 * <p>Every write is its own REQUIRES_NEW transaction: {@link #enqueue} runs
 * in {@code afterCommit} of the purge/erase/deletion transaction, whose
 * resources are still bound. Tenancy: callers run under the row's bank (the
 * request's tenant, or the sweeper's {@code runAs(bank)}); the explicit
 * BankId keeps the H2 suite and the PostgreSQL RLS policies in agreement.
 */
@Component
public class PendingFileDeletes implements AfterCommitFileDeleter.FailedDeleteQueue {

    private static final Logger log = LoggerFactory.getLogger(PendingFileDeletes.class);

    /** Rows retried per bank per sweep. */
    static final int BATCH_SIZE = 200;

    /** From this many failed attempts on, a failure is logged at ERROR. */
    static final int ERROR_AFTER = 8;

    static final Duration FIRST_BACKOFF = Duration.ofMinutes(5);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final PendingFileDeleteJpaRepository repository;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public PendingFileDeletes(PendingFileDeleteJpaRepository repository,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * Queues {@code key} for a retry at the next sweep. Queuing a key that is
     * already queued only refreshes its error. Never throws: a failure to
     * queue is logged at ERROR with the key (the last trace of the object).
     */
    @Override
    public void enqueue(int bankId, String key, Exception lastError) {
        try {
            Instant now = Instant.now();
            writeTransaction.executeWithoutResult(status -> {
                PendingFileDelete row = repository.findByBankIdAndStorageKey(bankId, key)
                        .orElseGet(() -> {
                            PendingFileDelete created = new PendingFileDelete();
                            created.setBankId(bankId);
                            created.setStorageKey(key);
                            created.setCreatedAt(now);
                            created.setNextAttemptAt(now);
                            return created;
                        });
                row.setLastError(describe(lastError));
                repository.save(row);
            });
        } catch (RuntimeException e) {
            log.error("Could not queue the failed delete of stored file {} (bank {}); "
                    + "the object stays until it is deleted by hand", key, bankId, e);
        }
    }

    /** The banks with a row due at {@code now}; ids only. Run as SYSTEM. */
    public List<Integer> banksWithDue(Instant now) {
        return readTransaction.execute(status -> repository.findBanksWithDue(now));
    }

    /**
     * Retries one bank's due rows (at most {@link #BATCH_SIZE}) against
     * {@code store}; must run under {@code runAs(bankId)}. Returns how many
     * objects were deleted (and their rows removed).
     */
    public int retryDue(int bankId, FileStore store, Instant now) {
        List<PendingFileDelete> due = readTransaction.execute(status ->
                repository.findDue(bankId, now, PageRequest.of(0, BATCH_SIZE)));
        int deleted = 0;
        for (PendingFileDelete row : due == null ? List.<PendingFileDelete>of() : due) {
            try {
                store.delete(row.getStorageKey());
            } catch (IOException | RuntimeException e) {
                rescheduled(row, now, e);
                continue;
            }
            writeTransaction.executeWithoutResult(status -> repository.deleteById(row.getId()));
            deleted++;
            log.info("Deleted stored file {} on retry {} (bank {})", row.getStorageKey(),
                    row.getAttempts() + 1, bankId);
        }
        return deleted;
    }

    private void rescheduled(PendingFileDelete row, Instant now, Exception error) {
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setLastError(describe(error));
        row.setNextAttemptAt(now.plus(backoff(attempts)));
        writeTransaction.executeWithoutResult(status -> repository.save(row));
        if (attempts >= ERROR_AFTER) {
            log.error("Stored file {} (bank {}) still cannot be deleted after {} attempts; next "
                    + "retry at {}: {}", row.getStorageKey(), row.getBankId(), attempts,
                    row.getNextAttemptAt(), error.toString());
        } else {
            log.warn("Retry {} of the delete of stored file {} (bank {}) failed; next at {}: {}",
                    attempts, row.getStorageKey(), row.getBankId(), row.getNextAttemptAt(),
                    error.toString());
        }
    }

    /** 5 minutes after the first failed sweep retry, doubling, at most 6 hours. */
    static Duration backoff(int attempts) {
        int doublings = Math.max(0, Math.min(attempts - 1, 10));
        Duration backoff = FIRST_BACKOFF.multipliedBy(1L << doublings);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    private static String describe(Exception error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
