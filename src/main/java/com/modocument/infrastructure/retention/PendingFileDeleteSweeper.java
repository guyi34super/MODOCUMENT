package com.modocument.infrastructure.retention;

import com.modocument.application.port.FileStore;
import com.modocument.application.port.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Retries the stored-object deletes that failed after a retention purge, a
 * DSR erase or a job deletion (ws29, BUG-12): every
 * {@code modocument.retention.file-delete-retry.interval-ms} (default 5
 * minutes) it drains the due rows of the PendingFileDelete queue
 * ({@link PendingFileDeletes}). {@code modocument.retention.file-delete-retry.enabled}
 * gates only the schedule (off in the test profile); {@link #runOnce()}
 * always runs.
 *
 * <p><b>Tenancy</b> (call-site registry in {@link TenantContext}):
 * {@code runAsSystem} for the cross-bank read that lists ONLY the ids of
 * banks with a due row, then {@code runAs(bank)} per bank for that bank's
 * retries and row deletes. One bank's failure never starves the others.
 */
@Component
public class PendingFileDeleteSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingFileDeleteSweeper.class);

    private final PendingFileDeletes pending;
    private final FileStore storage;
    private final boolean enabled;

    public PendingFileDeleteSweeper(
            PendingFileDeletes pending, FileStore storage,
            @Value("${modocument.retention.file-delete-retry.enabled:true}") boolean enabled) {
        this.pending = pending;
        this.storage = storage;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${modocument.retention.file-delete-retry.interval-ms:300000}",
            initialDelayString = "${modocument.retention.file-delete-retry.interval-ms:300000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Pending file-delete sweep failed", e);
        }
    }

    /** One sweep over every bank with a due row; returns the objects deleted. */
    public int runOnce() {
        Instant now = Instant.now();
        List<Integer> banks;
        try {
            // runAsSystem: the cross-bank listing of bank ids with due rows (ids only).
            banks = TenantContext.runAsSystem(() -> pending.banksWithDue(now));
        } catch (Exception e) {
            throw new IllegalStateException("Could not list the banks with pending file deletes.", e);
        }
        int deleted = 0;
        for (Integer bankId : banks == null ? List.<Integer>of() : banks) {
            try {
                deleted += TenantContext.runAs(bankId, () -> pending.retryDue(bankId, storage, now));
            } catch (Exception e) {
                log.error("Pending file-delete retry failed for bank {} (continuing with the rest)",
                        bankId, e);
            }
        }
        if (deleted > 0) {
            log.info("PendingFileDeleteSweep: {} stored file(s) deleted on retry", deleted);
        }
        return deleted;
    }
}
