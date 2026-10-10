package com.modocument.infrastructure.settings;

import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.BankSetting;
import com.modocument.domain.entity.ConfigRevision;
import com.modocument.infrastructure.persistence.BankSettingJpaRepository;
import com.modocument.infrastructure.persistence.ConfigRevisionJpaRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The in-memory settings snapshot of THIS application task (ws20) and its
 * reload mechanics. Reads ({@link #current()}) are a lock-free
 * {@link AtomicReference} get — no database access per setting read.
 *
 * <p><b>Propagation across tasks (≥ 2 ECS tasks):</b> every settings write
 * bumps {@code ConfigRevision} in its own transaction and reloads THIS task
 * right after commit ({@link #reloadAfterCommit()}); every other task's
 * {@link ConfigRevisionPoller} calls {@link #refreshIfChanged()} every
 * {@code modocument.settings.poll-interval-ms} and reloads when the revision
 * moved — so a change is visible everywhere within one poll interval.
 * Polling was chosen over LISTEN/NOTIFY: simpler, self-healing after a lost
 * connection, and testable.
 *
 * <p><b>Tenancy:</b> a reload reads every bank's rows, so it runs as
 * SYSTEM ({@code TenantContext.runAsSystem}) in its own read-only
 * REQUIRES_NEW transaction — a registered call site (see
 * {@link TenantContext}). It reads the revision FIRST and the rows second:
 * a write committing in between leaves the snapshot newer than its
 * revision, which only costs one extra reload on the next poll (the other
 * order could miss a change for good).
 *
 * <p>The ConfigRevision row is ensured at startup (the migration seeds it
 * on PostgreSQL; the H2 test schema starts empty).
 */
@Component
public class SettingsCache {

    private static final Logger log = LoggerFactory.getLogger(SettingsCache.class);

    private final BankSettingJpaRepository rows;
    private final ConfigRevisionJpaRepository revisions;
    private final SettingsCatalogue catalogue;
    private final PlatformBankLookup platformBanks;
    private final TransactionTemplate readOnlyNewTx;
    private final TransactionTemplate writeNewTx;
    private final AtomicReference<SettingsSnapshot> current =
            new AtomicReference<>(SettingsSnapshot.EMPTY);

    public SettingsCache(BankSettingJpaRepository rows, ConfigRevisionJpaRepository revisions,
                         SettingsCatalogue catalogue, PlatformBankLookup platformBanks,
                         PlatformTransactionManager transactionManager) {
        this.rows = rows;
        this.revisions = revisions;
        this.catalogue = catalogue;
        this.platformBanks = platformBanks;
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readOnly.setReadOnly(true);
        this.readOnlyNewTx = readOnly;
        TransactionTemplate write = new TransactionTemplate(transactionManager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeNewTx = write;
    }

    /**
     * Startup: ensure the counter row, then the first load. A database that
     * is not reachable yet leaves the shipped defaults in force (an EMPTY
     * snapshot) until the next poll succeeds — settings never block a boot.
     */
    @PostConstruct
    void start() {
        try {
            ensureRevisionRow();
            SettingsSnapshot loaded = reload();
            log.info("Settings snapshot loaded: revision {}, {} stored value(s)",
                    loaded.revision(), loaded.size());
        } catch (RuntimeException e) {
            log.warn("Settings could not be loaded at startup ({}); shipped defaults apply "
                    + "until the next poll", e.getMessage());
        }
    }

    /** The current snapshot (never null). */
    public SettingsSnapshot current() {
        return current.get();
    }

    /** Re-reads every stored setting now (SYSTEM, own read-only transaction). */
    public synchronized SettingsSnapshot reload() {
        OptionalInt platformBankId = platformBanks.platformBankId();
        SettingsSnapshot next = asSystem(() -> readOnlyNewTx.execute(status -> {
            long revision = readRevision();
            List<BankSetting> all = rows.findAll();
            return SettingsSnapshot.build(revision, platformBankId, all, catalogue);
        }));
        current.set(next);
        return next;
    }

    /**
     * The poll: one primary-key read of ConfigRevision; a full reload only
     * when the revision moved (or the platform bank appeared since the last
     * load — it is created at startup by the bootstrap runner).
     *
     * @return whether a reload happened
     */
    public boolean refreshIfChanged() {
        SettingsSnapshot snapshot = current.get();
        Long revision = asSystem(() -> readOnlyNewTx.execute(status -> readRevision()));
        boolean platformAppeared = snapshot.platformBankId().isEmpty()
                && platformBanks.platformBankId().isPresent();
        if ((revision != null && revision != snapshot.revision()) || platformAppeared) {
            reload();
            return true;
        }
        return false;
    }

    /**
     * Reloads this task once the surrounding transaction COMMITS (immediately
     * when there is none) — the writer sees its own change at once; other
     * tasks follow on their next poll. A failed reload is logged, never
     * propagated: the write itself already committed.
     */
    public void reloadAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeReload();
                }
            });
        } else {
            safeReload();
        }
    }

    private void safeReload() {
        try {
            reload();
        } catch (RuntimeException e) {
            log.warn("Settings reload after a write failed ({}); the next poll retries",
                    e.getMessage());
        }
    }

    /** The counter; a missing row (fresh H2 schema) reads as 0. */
    private long readRevision() {
        return revisions.findById(ConfigRevision.SINGLETON_ID)
                .map(ConfigRevision::getRevision)
                .orElse(0L);
    }

    private void ensureRevisionRow() {
        try {
            writeNewTx.executeWithoutResult(status -> {
                if (!revisions.existsById(ConfigRevision.SINGLETON_ID)) {
                    revisions.save(new ConfigRevision(0L, Instant.now()));
                }
            });
        } catch (DataIntegrityViolationException concurrentInsert) {
            // Another task created it first — exactly what we wanted.
        }
    }

    private static <T> T asSystem(Callable<T> action) {
        try {
            return TenantContext.runAsSystem(action);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
