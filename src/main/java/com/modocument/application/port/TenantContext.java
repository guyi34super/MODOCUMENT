package com.modocument.application.port;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The bank (tenant) the current thread is working for.
 *
 * <p>For web requests a servlet filter opens the context from the JWT's
 * bank_id claim and clears it in a finally block. Background work that acts
 * for a specific tenant wraps itself in {@link #runAs(int, Runnable)} (the
 * extraction worker does this with the job's bank); maintenance work that is
 * deliberately tenant-less uses {@link #runAsSystem(Runnable)}.
 *
 * <p>On PostgreSQL this context is ENFORCED at the SQL layer: it is bound to
 * the {@code app.tenant} setting at every connection checkout
 * (TenantAwareDataSource) and at every transaction begin
 * (TenantAwareJpaTransactionManager), and the V13 row level security
 * policies filter every BankId-stamped table by it. No context bound means
 * zero rows (fail closed); {@link #runAsSystem} maps to the {@code '*'}
 * wildcard the policies accept for maintenance work.
 *
 * <p>LOG CORRELATION (E9): this class also owns the {@code bankId} MDC key —
 * every mutation mirrors the bound tenant onto the logging context (a real
 * bank id, {@code *} for SYSTEM, removed when unbound), so request threads,
 * decorated async threads (TenantTaskDecorator copies the MDC map) and
 * {@code runAsSystem} maintenance work all log the tenant they act for.
 *
 * <p>CALL-SITE REGISTRY — every place that deliberately establishes a tenant
 * context outside a web request (grep for the class name to re-audit):
 * <ul>
 *   <li>{@code IdentityContextFilter} — request threads, from the JWT
 *       bank_id claim (cleared in a finally block).</li>
 *   <li>{@code ExecuteExtractionTaskUseCase#run} — extraction worker
 *       threads: {@code runAsSystem} to resolve whose job a queued task is,
 *       then {@code runAs(job.getBankId())} for the actual work.</li>
 *   <li>{@code RetrySweeper#sweep} — scheduled lease recovery,
 *       {@code runAsSystem} (requeues every tenant's expired tasks).</li>
 *   <li>{@code SqlExtractionStartupRecovery#run} — startup crash recovery,
 *       {@code runAsSystem} (settles every tenant's interrupted jobs).</li>
 *   <li>{@code BootstrapAdminRunner#run} — startup first-admin seeding,
 *       {@code runAsSystem} (reads/creates the default Bank row before any
 *       tenant exists).</li>
 *   <li>{@code OidcLoginService#login} — OIDC JIT provisioning,
 *       {@code runAsSystem} (resolves the provider's Bank and links/creates
 *       the user before any tenant context exists — the caller is
 *       anonymous until MoDocument issues its own tokens).</li>
 *   <li>{@code OidcClientConfig#oidcBankCodeValidator} — startup check that
 *       every configured OIDC provider's bank-code names an existing Bank,
 *       {@code runAsSystem} (reads the RLS-protected Bank table).</li>
 *   <li>{@code TenantTaskDecorator} — copies the submitting thread's context
 *       (via {@link #snapshot()}/{@link #runWith}) onto pooled async
 *       threads: the ingestion background runner and the extraction worker
 *       executor.</li>
 *   <li>{@code RetentionPurgeJob#runOnce} — the ws14 daily retention sweep
 *       (and its manual admin trigger): {@code runAsSystem} to list the
 *       RLS-protected Bank table across tenants, then
 *       {@code runAs(bank)} per bank so each bank's purge and its
 *       RETENTION_PURGE_COMPLETED audit event run strictly inside that
 *       tenant.</li>
 *   <li>{@code SodScanJob#runOnce} — the ws11 nightly separation-of-duties
 *       scan (and its manual admin trigger): the same shape as the
 *       retention sweep — {@code runAsSystem} for the cross-tenant bank
 *       listing, then {@code runAs(bank)} per bank so each bank's
 *       invariant checks and SOD_SCAN_COMPLETED / SOD_VIOLATION_FOUND
 *       audit events run strictly inside that tenant.</li>
 * </ul>
 */
public final class TenantContext {

    /** Sentinel stored by {@link #runAsSystem}; never a real BankId. */
    private static final int SYSTEM = Integer.MIN_VALUE;

    /** MDC key mirroring the bound tenant on every log line (E9). */
    public static final String MDC_BANK_ID = "bankId";

    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * The current tenant, or empty when unset, running as SYSTEM, or holding
     * a non-positive id (an unstamped legacy object).
     */
    public static Optional<Integer> currentBankId() {
        Integer bankId = CURRENT.get();
        return bankId == null || bankId <= 0 ? Optional.empty() : Optional.of(bankId);
    }

    public static boolean isSystem() {
        Integer bankId = CURRENT.get();
        return bankId != null && bankId == SYSTEM;
    }

    public static void runAs(int bankId, Runnable action) {
        Integer previous = CURRENT.get();
        bind(bankId);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T runAs(int bankId, Callable<T> action) throws Exception {
        Integer previous = CURRENT.get();
        bind(bankId);
        try {
            return action.call();
        } finally {
            restore(previous);
        }
    }

    public static void runAsSystem(Runnable action) {
        Integer previous = CURRENT.get();
        bind(SYSTEM);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T runAsSystem(Callable<T> action) throws Exception {
        Integer previous = CURRENT.get();
        bind(SYSTEM);
        try {
            return action.call();
        } finally {
            restore(previous);
        }
    }

    /**
     * Captures this thread's tenant (including the SYSTEM sentinel) for
     * hand-off to another thread; see {@link #runWith}. Used by
     * TenantTaskDecorator to propagate the context onto async executors.
     */
    public static Snapshot snapshot() {
        return new Snapshot(CURRENT.get());
    }

    /** Runs the action under a previously captured context, then restores. */
    public static void runWith(Snapshot snapshot, Runnable action) {
        Integer previous = CURRENT.get();
        Integer value = snapshot == null ? null : snapshot.bankId;
        bind(value);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    /** Opaque captured tenant context; only {@link #runWith} can apply it. */
    public static final class Snapshot {

        private final Integer bankId;

        private Snapshot(Integer bankId) {
            this.bankId = bankId;
        }
    }

    /**
     * Request-scoped entry point for the servlet filter; everything else
     * should prefer the runAs wrappers, which restore the previous value.
     */
    public static void open(int bankId) {
        bind(bankId);
    }

    /** Clears the context (the filter's finally block). */
    public static void clear() {
        bind(null);
    }

    private static void restore(Integer previous) {
        bind(previous);
    }

    /**
     * The one place the thread local changes: keeps the {@link #MDC_BANK_ID}
     * logging key in lockstep with the bound tenant.
     */
    private static void bind(Integer value) {
        if (value == null) {
            CURRENT.remove();
            MDC.remove(MDC_BANK_ID);
        } else {
            CURRENT.set(value);
            MDC.put(MDC_BANK_ID, value == SYSTEM ? "*" : String.valueOf(value));
        }
    }
}
