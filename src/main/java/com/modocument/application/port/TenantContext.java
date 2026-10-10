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
 *       bank_id claim, or (ws26) the authenticated API client's bank
 *       (cleared in a finally block).</li>
 *   <li>{@code ExecuteExtractionTaskUseCase#run} — extraction worker
 *       threads: {@code runAsSystem} to resolve whose job a queued task is,
 *       then {@code runAs(job.getBankId())} for the actual work.</li>
 *   <li>{@code RetrySweeper#sweep} — scheduled lease recovery,
 *       {@code runAsSystem} (requeues every tenant's expired tasks).</li>
 *   <li>{@code SqlExtractionStartupRecovery#run} — startup crash recovery,
 *       {@code runAsSystem} (settles every tenant's interrupted jobs).</li>
 *   <li>{@code BootstrapAdminRunner#run} — startup first-admin seeding
 *       (first and optional second ADMIN, one transaction) and the
 *       every-start "fewer than two approvers" warning (a COUNT of the
 *       default bank's enabled accounts by role), {@code runAsSystem}
 *       (reads/creates the default Bank row before any tenant exists).</li>
 *   <li>{@code OidcLoginService#login} — OIDC JIT provisioning,
 *       {@code runAsSystem} (resolves the provider's Bank and links/creates
 *       the user before any tenant context exists — the caller is
 *       anonymous until MoDocument issues its own tokens; ws21 also reads
 *       the user's Bank status there to refuse suspended banks).</li>
 *   <li>{@code OidcClientConfig#oidcBankCodeValidator} — startup check that
 *       every configured OIDC provider's bank-code names an existing Bank,
 *       {@code runAsSystem} (reads the RLS-protected Bank table).</li>
 *   <li>{@code PlatformBankDirectory#platformBankId} — phase 2: resolves
 *       the platform bank by code, {@code runAsSystem} in its own read-only
 *       transaction (the Bank table is RLS-protected); id cached.</li>
 *   <li>{@code PlatformBankDirectory#find} — ws21 {@code BankDirectory}:
 *       one Bank row's kind/status by id (permission guard, role policy,
 *       wave-2 suspension checks), {@code runAsSystem} in its own read-only
 *       transaction; identifying columns only, short-TTL cache.</li>
 *   <li>{@code BootstrapPlatformRunner#run} — ws21 startup seeding of the
 *       platform bank and its two PLATFORM_ADMIN accounts,
 *       {@code runAsSystem} (reads/creates an RLS-protected Bank row
 *       before any tenant exists), one transaction.</li>
 *   <li>{@code PlatformBankUseCase#listBanks} / {@code #getBank} — ws21
 *       platform console ({@code GET /api/platform/banks[/{id}]}, and
 *       {@code GET /api/platform/overview} through {@code #overview} →
 *       {@code #listBanks}; PLATFORM_READ, platform bank only):
 *       {@code runAsSystem} in its own read-only transaction for a
 *       cross-bank COUNTS-ONLY read (Bank identifying columns +
 *       COUNT/MAX/SUM aggregates; no row content).</li>
 *   <li>{@code SettingsCache#reload} / {@code SettingsCache#refreshIfChanged}
 *       — phase 2 (ws20) settings snapshot: {@code runAsSystem} in its own
 *       read-only REQUIRES_NEW transaction to read the tenant-less
 *       ConfigRevision counter and EVERY bank's BankSetting rows (the
 *       platform layer is the platform bank's rows, so each bank's effective
 *       values need two banks' rows). Called at startup, after each settings
 *       write commits, and by {@code ConfigRevisionPoller#poll} every
 *       {@code modocument.settings.poll-interval-ms}. Read-only; writes stay
 *       under the writer's own tenant.</li>
 *   <li>{@code PlatformBankLifecycleUseCase#requireAvailableCode} — ws21
 *       BANK_CREATE submit: {@code runAsSystem} in its own read-only
 *       transaction to check that no bank carries the requested code (the
 *       maker's tenant sees only the platform bank).</li>
 *   <li>{@code PlatformBankLifecycleUseCase#applyOnboarding},
 *       {@code #applySuspension}, {@code #applyAdminAdd} (the BANK_CREATE /
 *       BANK_SUSPEND / BANK_ADMIN_ADD appliers, run when a SECOND platform
 *       admin approves) and {@code #reactivate} (direct, PLATFORM_BANKS):
 *       {@code runAsSystem} around a REQUIRES_NEW write transaction — the
 *       approval transaction is bound to the platform bank. They write the
 *       target Bank row, that bank's AppUsers (create, or TokenVersion bump
 *       + RefreshToken revocation on suspension) and AuditEvent rows
 *       stamped explicitly with the customer bank and the platform bank;
 *       new users and their USER_CREATED rows are written under
 *       {@code runAs(newBank)} inside the same system transaction, so a
 *       failed user insert rolls the new Bank row back too. No customer
 *       content is read.
 *       {@code #revertOnboarding} (wave 4) is the compensating cleanup when
 *       the approval transaction rolls back after the onboarding committed:
 *       {@code runAsSystem} around a REQUIRES_NEW write transaction that
 *       deletes the new bank's first users (and any refresh token) and the
 *       Bank row, and writes BANK_ONBOARDING_REVERTED in the platform
 *       bank.</li>
 *   <li>{@code AiCircuitBreaker#auditOpened} — ws23: when the AI circuit
 *       opens, {@code runAs(platformBankId)} to write AI_CIRCUIT_OPENED in
 *       the platform bank (skipped when no platform bank exists). No
 *       customer data is read or written.</li>
 *   <li>{@code ApiClientAuthenticator#lookupAsSystem} — ws26 machine
 *       identity: {@code runAsSystem} in its own read-only REQUIRES_NEW
 *       transaction to fetch ONE ApiClient (by ClientId, API-key prefix,
 *       certificate fingerprint) plus that client's ApiClientSecret hashes,
 *       before any tenant exists (OAuth2 token endpoint, API-key and
 *       client-certificate authentication, the client-token branch of
 *       RevocationValidator, and the "certificate already registered"
 *       check). Identifying/credential columns only; the request then runs
 *       under the client's bank (IdentityContextFilter).</li>
 *   <li>{@code AgentApiClientIssuer#issueAgentClient} — ws26: when the
 *       caller (SE5's public agent enrolment) has bound no tenant,
 *       {@code runAs(bankId)} to write the agent's new ApiClient row, its
 *       secret hash and the API_CLIENT_CREATED audit in that bank; a caller
 *       bound to another bank is refused.</li>
 *   <li>{@code TenantTaskDecorator} — copies the submitting thread's context
 *       (via {@link #snapshot()}/{@link #runWith}) onto pooled async
 *       threads: the ingestion background runner and the extraction worker
 *       executor.</li>
 *   <li>{@code IngestionParseStepImpl#extractBatchesParallel} — the same
 *       {@link #snapshot()}/{@link #runWith} hand-off onto the short-lived
 *       pool that sends parallel LLM batches (the AI gateway refuses a
 *       thread with no bound tenant). It only copies the caller's bank.</li>
 *   <li>{@code JpaAuditRecorder#write} — anonymous flows (login, refresh,
 *       MFA) where no tenant is bound yet: {@code runAs(event's bank)} for
 *       that one AuditEvent insert, so the RLS WITH CHECK passes; restored
 *       right after. Never {@code runAsSystem}.</li>
 *   <li>{@code MfaUseCase#inUsersBank} — the MFA login step (the caller is
 *       anonymous until tokens are issued): {@code runAs(user's bank)}
 *       around a new transaction for that user's second-factor check and
 *       session creation. Never {@code runAsSystem}.</li>
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
 *   <li>{@code AlertSweepJob#runOnce} — the ws18 minute sweep of the
 *       windowed alert rules: {@code runAsSystem} for the cross-bank
 *       aggregate READ of AuditEvent (a brute-force burst is detected
 *       wherever it lands); every alert it raises is recorded by
 *       {@code AlertService} under its own bank.</li>
 *   <li>{@code AlertService} — ws18 alert recording/delivery on its
 *       private, {@code TenantTaskDecorator}-wrapped executor:
 *       {@code runAs(alert bank)} for every AlertLog write and every
 *       ALERT_SENT / ALERT_DELIVERY_FAILED audit row;
 *       {@code runAsSystem} only for the sweep's cross-bank scan of
 *       PENDING deliveries to retry.</li>
 *   <li>{@code SiemWebhookForwarder#resolveBank} — ws18 SIEM push:
 *       {@code runAsSystem} to look the configured bank-code up in the
 *       RLS-protected Bank table (startup, then each sweep until it
 *       resolves); the forwarder touches no other table.</li>
 *   <li>{@code DeployedVersionRecorder#record} — ws24 startup record of a
 *       newly deployed version (only the task that inserted the non-tenant
 *       DeployedVersion row): {@code runAsSystem} in its own read-only
 *       transaction to list the Bank ids, then {@code runAs(bank)} per bank
 *       for that bank's VERSION_DEPLOYED audit row, then
 *       {@code runAs(platform bank)} to write the RELEASE announcement the
 *       platform bank owns (+ its ANNOUNCEMENT_PUBLISHED audit).</li>
 *   <li>{@code AnnouncementCache#live} (reload) — ws24: {@code runAs(platform
 *       bank)} in its own read-only transaction to load the platform-owned
 *       announcements (RLS hides them from every customer tenant); served
 *       to each bank from memory, filtered by its bank code. Never
 *       {@code runAsSystem}: it reads the platform's rows only.</li>
 *   <li>{@code EgressRuleUseCase#listForPlatform} /
 *       {@code #requireApprovable} — ws25 platform console
 *       ({@code GET /api/platform/egress-rules}, PLATFORM_READ) and the
 *       EGRESS_RULE_APPROVE submit check (PLATFORM_BANKS):
 *       {@code runAsSystem} in its own read-only REQUIRES_NEW transaction to
 *       read BankEgressRule rows across banks (destination host/CIDR, port,
 *       status, reason — no customer content).</li>
 *   <li>{@code EgressRuleUseCase#applyApproval} — ws25 EGRESS_RULE_APPROVE
 *       applier (a SECOND platform admin approves): {@code runAsSystem}
 *       read-only to find the rule's bank by id, then
 *       {@code runAs(rule bank)} around a REQUIRES_NEW write transaction that
 *       flips the rule PENDING → APPROVED and writes its EGRESS_RULE_APPROVED
 *       audit row in that bank (the platform bank's copy joins the
 *       approval transaction).</li>
 *   <li>{@code BankEgressGuard#approvedRules} — ws25 egress check of DIRECT
 *       connections: {@code runAs(bankId)} in its own read-only REQUIRES_NEW
 *       transaction to load that bank's APPROVED egress rules (cached
 *       briefly), whatever context the connector runs in. Never
 *       {@code runAsSystem}.</li>
 *   <li>{@code AgentEnrolmentService#enroll} (via {@code #pendingAgentBank})
 *       — ws26 {@code POST /api/agent/v1/enroll}, a public route whose
 *       credential is the agent's one-time enrolment token:
 *       {@code runAsSystem} in its own read-only REQUIRES_NEW transaction to
 *       read ONE IntegrationAgent row by the id embedded in the token and
 *       return only its BankId (when the agent is PENDING_ENROLMENT) —
 *       the bank is unknown before the token is resolved. The token check
 *       (constant-time hash compare, expiry, single use), the ApiClient
 *       issue and the AGENT_ENROLLED audit then run under
 *       {@code runAs(that bank)}.</li>
 *   <li>{@code AgentTaskLongPoll} — ws26 agent long poll: the scheduler
 *       thread re-tries {@code AgentRunPort#claimTasks} for each waiting
 *       agent under {@code runAs(agent's bank)} (taken from the
 *       authenticated agent client on the request thread). Never
 *       {@code runAsSystem}.</li>
 *   <li>{@code IntegrationScheduler#tick} — ws25 integration flow engine,
 *       every 30 s: {@code runAsSystem} in its own read-only transaction
 *       for the cross-bank scan that lists ONLY the ids of banks with
 *       integration work (an enabled flow, a PENDING outbox delivery, an
 *       unfinished run); then {@code runAs(bank)} per bank for that bank's
 *       due cron slots, run timeouts and outbox dispatch. No row content
 *       is read as SYSTEM.</li>
 *   <li>{@code IntegrationRunExecutor#submit} — ws25: executes one run on
 *       the integration worker pool under {@code runAs(run's bank)} (from
 *       the scheduler, "Run now" and retry).</li>
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
