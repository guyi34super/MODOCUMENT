package com.modocument.infrastructure.housekeeping;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.BankKind;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.settings.IntegrationSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily housekeeping of operational tables that only ever grew (ws27,
 * v1.2.0): it runs after the retention purge (02:41) and the SoD scan
 * (03:23); {@code modocument.housekeeping.cron}, default 04:07.
 * {@code modocument.housekeeping.enabled} gates only the schedule (off in
 * the test profile); {@link #runOnce()} always runs.
 *
 * <p>What goes (the rules themselves: {@link HousekeepingPurger}):
 * <ul>
 *   <li>RefreshToken rows expired or revoked more than 30 days ago
 *       ({@code refresh-token-grace});</li>
 *   <li>RevokedToken rows whose access token has expired (plus the
 *       {@code revoked-token-grace} clock-skew margin);</li>
 *   <li>finished IntegrationRun rows with their IntegrationRunEvent log,
 *       DELIVERED/DEAD OutboundDelivery rows and IntegrationEvent rows older
 *       than the bank's {@code integration.historyRetention} (default 90
 *       days) — an event an undelivered delivery still needs is kept;</li>
 *   <li>expired IdempotencyKey rows, in one batched sweep (the integration
 *       API no longer deletes them inside every request).</li>
 * </ul>
 * Every rule is bounded per run (batched deletes, a row limit per
 * statement, a statement limit per rule).
 *
 * <p><b>Tenancy</b> (call-site registry in {@link TenantContext}): the same
 * shape as the retention purge — {@code runAsSystem} to list the
 * RLS-protected Bank table and to sweep the non-tenant RevokedToken table,
 * then {@code runAs(bank)} per bank, so each bank's deletes and its
 * HOUSEKEEPING_COMPLETED audit event (counts only) run strictly inside that
 * tenant. One bank's failure never starves the others.
 */
@Component
public class HousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingJob.class);

    static final String ACTION = "HOUSEKEEPING_COMPLETED";

    private final HousekeepingProperties properties;
    private final HousekeepingPurger purger;
    private final BankJpaRepository banks;
    private final IntegrationSettings integrationSettings;
    private final AuditRecorder audit;

    public HousekeepingJob(HousekeepingProperties properties, HousekeepingPurger purger,
                           BankJpaRepository banks, IntegrationSettings integrationSettings,
                           AuditRecorder audit) {
        this.properties = properties;
        this.purger = purger;
        this.banks = banks;
        this.integrationSettings = integrationSettings;
        this.audit = audit;
    }

    @Scheduled(cron = "${modocument.housekeeping.cron:0 7 4 * * *}")
    public void sweep() {
        if (!properties.isEnabled()) {
            log.debug("Housekeeping is disabled (modocument.housekeeping.enabled=false); skipping.");
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Housekeeping run failed", e);
        }
    }

    /** One full run over every bank; returns the per-bank evidence. */
    public List<BankHousekeepingSummary> runOnce() {
        Instant now = Instant.now();

        // runAsSystem: listing the RLS-protected Bank table across tenants.
        List<Bank> allBanks;
        try {
            allBanks = TenantContext.runAsSystem(() -> banks.findAll());
        } catch (Exception e) {
            throw new IllegalStateException("Housekeeping could not list banks.", e);
        }

        // runAsSystem: RevokedToken has no BankId (V15) — one platform-wide sweep.
        int revokedTokens = 0;
        try {
            revokedTokens = TenantContext.runAsSystem(() ->
                    purger.revokedTokens(now.minus(properties.getRevokedTokenGrace())));
        } catch (Exception e) {
            log.error("Housekeeping of RevokedToken failed (continuing with the banks)", e);
        }
        int reportingBank = reportingBank(allBanks);

        List<BankHousekeepingSummary> summaries = new ArrayList<>();
        for (Bank bank : allBanks) {
            int bankId = bank.getBankId();
            int platformWide = bankId == reportingBank ? revokedTokens : 0;
            try {
                summaries.add(TenantContext.runAs(bankId, () -> housekeepBank(bankId, now,
                        platformWide)));
            } catch (Exception e) {
                log.error("Housekeeping failed for bank {} (continuing with the rest)", bankId, e);
            }
        }

        int total = summaries.stream().mapToInt(BankHousekeepingSummary::total).sum();
        log.info("HousekeepingRun completed: {} bank(s), {} row(s) deleted ({} revoked token(s))",
                summaries.size(), total, revokedTokens);
        return summaries;
    }

    /**
     * One bank's housekeeping; must run inside {@code runAs(bankId)}.
     * {@code revokedTokens} is the platform-wide count to report on this
     * bank's audit row (0 except on the reporting bank).
     */
    public BankHousekeepingSummary housekeepBank(int bankId, Instant now, int revokedTokens) {
        Duration history = integrationSettings.historyRetention(bankId);
        Instant historyCutoff = now.minus(history);

        int refreshTokens = purger.refreshTokens(bankId,
                now.minus(properties.getRefreshTokenGrace()));
        // Deliveries first: a run is only deleted once no outbox row points at it.
        int deliveries = purger.terminalDeliveries(bankId, historyCutoff);
        int[] runs = purger.finishedRuns(bankId, historyCutoff);
        int events = purger.integrationEvents(bankId, historyCutoff);
        int idempotencyKeys = purger.expiredIdempotencyKeys(bankId, now);

        BankHousekeepingSummary summary = new BankHousekeepingSummary(bankId, history.toDays(),
                refreshTokens, revokedTokens, runs[0], runs[1], deliveries, events,
                idempotencyKeys);

        // Evidence per bank and run, zero counts included (proof the bank was swept).
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("historyRetentionDays", history.toDays());
        evidence.put("historyCutoff", historyCutoff.toString());
        evidence.putAll(summary.counts());
        audit.record(AuditEventSpec.builder(ACTION, AuditOutcome.SUCCESS)
                .entity("Bank", String.valueOf(bankId))
                .bankId(bankId)
                .newValues(evidence)
                .build());
        return summary;
    }

    /** The bank whose audit row carries the platform-wide counts: the platform bank, else DEFAULT. */
    private static int reportingBank(List<Bank> allBanks) {
        return allBanks.stream()
                .filter(bank -> bank.getKind() == BankKind.PLATFORM)
                .mapToInt(Bank::getBankId)
                .findFirst()
                .orElse(Bank.DEFAULT_BANK_ID);
    }
}
