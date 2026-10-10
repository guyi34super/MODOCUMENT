package com.modocument.infrastructure.retention;

import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.Bank;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The 30-day automatic deletion of uploaded customer data (ws14, gate C5,
 * PRIV-013): a daily scheduled sweep (registered like the extraction
 * {@code RetrySweeper}; {@code @EnableScheduling} comes from
 * ExtractionSchedulingConfig) that purges, PER BANK, every customer-data
 * category older than the retention cutoff. The bank listing runs as SYSTEM
 * (maintenance crosses tenants by design — see the call-site registry in
 * {@link TenantContext}); each bank's purge then runs under THAT bank's
 * tenant context, so the RLS policies see exactly the tenant being purged
 * and the per-bank RETENTION_PURGE_COMPLETED audit event lands in the right
 * tenant.
 *
 * <p>One bank's purge failing must not starve the others: failures are
 * logged per bank and the sweep continues. {@code modocument.retention.enabled}
 * gates only the schedule; {@link #runOnce()} (the admin's manual
 * POST /api/admin/retention/run) always runs.
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

    private final RetentionProperties properties;
    private final RetentionPurger purger;
    private final BankJpaRepository banks;

    public RetentionPurgeJob(RetentionProperties properties, RetentionPurger purger,
                             BankJpaRepository banks) {
        this.properties = properties;
        this.purger = purger;
        this.banks = banks;
    }

    @Scheduled(cron = "${modocument.retention.cron:0 41 2 * * *}")
    public void sweep() {
        if (!properties.enabled()) {
            log.debug("Retention purge is disabled (modocument.retention.enabled=false); skipping.");
            return;
        }
        runOnce();
    }

    /** One full sweep over every bank; returns the per-bank evidence. */
    public List<BankPurgeSummary> runOnce() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.customerDataDays());
        Instant cutoffInstant = Instant.now().minus(properties.customerDataDays(), ChronoUnit.DAYS);

        // runAsSystem: listing the RLS-protected Bank table across tenants.
        List<Bank> allBanks;
        try {
            allBanks = TenantContext.runAsSystem(() -> banks.findAll());
        } catch (Exception e) {
            throw new IllegalStateException("Retention purge could not list banks.", e);
        }

        List<BankPurgeSummary> summaries = new ArrayList<>();
        for (Bank bank : allBanks) {
            int bankId = bank.getBankId();
            try {
                summaries.add(TenantContext.runAs(bankId, () ->
                        purger.purgeBank(bankId, cutoff, cutoffInstant,
                                properties.customerDataDays())));
            } catch (Exception e) {
                log.error("Retention purge failed for bank {} (continuing with the rest)",
                        bankId, e);
            }
        }

        // The one-line run summary promised by the gate ("RetentionRun" in
        // the app log). Counts only — never values.
        int total = summaries.stream().mapToInt(BankPurgeSummary::total).sum();
        log.info("RetentionRun completed: {} bank(s) swept, {} item(s) purged, cutoff {} ({} days)",
                summaries.size(), total, cutoff, properties.customerDataDays());
        return summaries;
    }
}
