package com.modocument.infrastructure.sod;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.PendingChangeRepository;
import com.modocument.application.port.TenantContext;
import com.modocument.application.security.SodPolicy;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The nightly separation-of-duties scan (SEC-024 layer 3): a daily
 * scheduled sweep (registered like the extraction {@code RetrySweeper};
 * {@code @EnableScheduling} comes from ExtractionSchedulingConfig, idioms
 * from {@code RetentionPurgeJob}) that re-verifies, PER BANK, the SoD
 * invariants the runtime layer ({@link SodPolicy}) enforces on the way in:
 * <ul>
 *   <li>no APPLIED PendingChange was approved by its own maker
 *       (ws10, gate B7);</li>
 *   <li>no RELEASED DocInstance was released by its own author (owner) or
 *       submitter (ws11, gate B8).</li>
 * </ul>
 * Both lists are expected EMPTY — a hit means the runtime gate was bypassed
 * (direct DB write, bug, restored backup) and is itself the finding. Every
 * hit writes a SOD_VIOLATION_FOUND (DENIED) audit row naming the object and
 * the parties; every bank's sweep ends with a SOD_SCAN_COMPLETED (SUCCESS)
 * row carrying the counts — the recurring evidence that the scan ran.
 *
 * <p>The bank listing runs as SYSTEM (maintenance crosses tenants by
 * design — see the call-site registry in {@link TenantContext}); each
 * bank's scan then runs under THAT bank's tenant context, so the RLS
 * policies see exactly the tenant being scanned and its audit rows land in
 * the right tenant. One bank's failure must not starve the others.
 *
 * <p>{@code modocument.sod.enabled} gates only the schedule;
 * {@link #runOnce()} (the admin's manual POST /api/admin/sod/scan) always
 * runs.
 */
@Component
public class SodScanJob {

    private static final Logger log = LoggerFactory.getLogger(SodScanJob.class);

    private final PendingChangeRepository pendingChanges;
    private final DocInstanceRepository docInstances;
    private final BankJpaRepository banks;
    private final AuditRecorder audit;
    private final boolean enabled;

    /**
     * Per-bank transaction: the SUCCESS SOD_SCAN_COMPLETED row must join a
     * transaction (C7 — the audit and the scan stand together), and a
     * scheduled sweep has none of its own.
     */
    private final TransactionTemplate tx;

    public SodScanJob(PendingChangeRepository pendingChanges,
                      DocInstanceRepository docInstances,
                      BankJpaRepository banks,
                      AuditRecorder audit,
                      PlatformTransactionManager transactionManager,
                      @Value("${modocument.sod.enabled:true}") boolean enabled) {
        this.pendingChanges = pendingChanges;
        this.docInstances = docInstances;
        this.banks = banks;
        this.audit = audit;
        this.enabled = enabled;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${modocument.sod.cron:0 23 3 * * *}")
    public void sweep() {
        if (!enabled) {
            log.debug("SoD scan is disabled (modocument.sod.enabled=false); skipping.");
            return;
        }
        runOnce();
    }

    /** One full scan over every bank; returns the per-bank evidence. */
    public List<BankScanSummary> runOnce() {
        // runAsSystem: listing the RLS-protected Bank table across tenants.
        List<Bank> allBanks;
        try {
            allBanks = TenantContext.runAsSystem(() -> banks.findAll());
        } catch (Exception e) {
            throw new IllegalStateException("SoD scan could not list banks.", e);
        }

        List<BankScanSummary> summaries = new ArrayList<>();
        for (Bank bank : allBanks) {
            int bankId = bank.getBankId();
            try {
                summaries.add(TenantContext.runAs(bankId,
                        () -> tx.execute(status -> scanBank(bankId))));
            } catch (Exception e) {
                log.error("SoD scan failed for bank {} (continuing with the rest)", bankId, e);
            }
        }

        int total = summaries.stream().mapToInt(BankScanSummary::findings).sum();
        log.info("SodScan completed: {} bank(s) scanned, {} violation(s) found",
                summaries.size(), total);
        return summaries;
    }

    /** Scans one bank; must run with that bank's tenant context bound. */
    private BankScanSummary scanBank(int bankId) {
        List<PendingChange> selfApproved = pendingChanges.listAppliedSodViolations(bankId);
        for (PendingChange change : selfApproved) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("conflict", SodPolicy.Operation.CHANGE_APPROVAL.name());
            values.put("changeType", change.getChangeType().name());
            values.put("makerUserId", change.getMakerUserId());
            values.put("checkerUserId", change.getCheckerUserId());
            recordViolation("PendingChange", change.getPendingChangeId(), values);
        }

        List<DocInstance> selfReleased = docInstances.listReleasedSodViolations(bankId);
        for (DocInstance instance : selfReleased) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("conflict", SodPolicy.Operation.DOC_RELEASE.name());
            values.put("ownerUserId", instance.getOwnerUserId());
            values.put("submittedByUserId", instance.getSubmittedByUserId());
            values.put("releasedByUserId", instance.getReleasedByUserId());
            values.put("aiComposed", instance.isAiComposed());
            recordViolation("DocInstance", instance.getDocInstanceId(), values);
        }

        BankScanSummary summary =
                new BankScanSummary(bankId, selfApproved.size(), selfReleased.size());
        audit.record(AuditEventSpec.builder("SOD_SCAN_COMPLETED", AuditOutcome.SUCCESS)
                .entity("Bank", String.valueOf(bankId))
                .newValues(summary.counts())
                .build());
        return summary;
    }

    private void recordViolation(String entityType, int entityId, Map<String, Object> values) {
        // DENIED: the row writes in its own transaction and marks a refusal
        // that SHOULD have happened at runtime — the alerting outcome.
        audit.record(AuditEventSpec.builder("SOD_VIOLATION_FOUND", AuditOutcome.DENIED)
                .entity(entityType, String.valueOf(entityId))
                .newValues(values)
                .build());
    }

    /** One bank's scan result; {@code counts()} is the audited payload. */
    public record BankScanSummary(int bankId, int selfApprovedChanges, int selfReleasedDocs) {

        public int findings() {
            return selfApprovedChanges + selfReleasedDocs;
        }

        public Map<String, Object> counts() {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("selfApprovedChanges", selfApprovedChanges);
            counts.put("selfReleasedDocs", selfReleasedDocs);
            counts.put("findings", findings());
            return counts;
        }
    }
}
