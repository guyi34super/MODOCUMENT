package com.modocument.doccreation.usecase;

import com.modocument.application.port.TenantContext;
import com.modocument.doccreation.repo.TemplateRecordJpaRepository;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.JobStep;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The one-off backfill of the record index (docs/doccreation/flow-records-contract.md
 * §3): at startup, every VALIDATED/EXPORTED job that still holds row data and
 * has no record yet is indexed. Idempotent — an indexed job is skipped, so
 * after the first run it only picks up jobs a failed index left behind.
 *
 * <p>Tenancy: {@code runAsSystem} to list the RLS-protected Bank table, then
 * {@code runAs(bank)} per bank for that bank's job ids and its indexing
 * (registered in the TenantContext call-site registry). A bank failing is
 * logged and the others continue; the application starts regardless.
 * {@code modocument.records.backfill-enabled=false} switches it off.
 */
@Component
public class TemplateRecordBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TemplateRecordBackfill.class);

    private final BankJpaRepository banks;
    private final TemplateRecordJpaRepository records;
    private final TemplateRecordIndexer indexer;
    private final boolean enabled;

    public TemplateRecordBackfill(BankJpaRepository banks,
                                  TemplateRecordJpaRepository records,
                                  TemplateRecordIndexer indexer,
                                  @Value("${modocument.records.backfill-enabled:true}") boolean enabled) {
        this.banks = banks;
        this.records = records;
        this.indexer = indexer;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Record index backfill is disabled (modocument.records.backfill-enabled=false).");
            return;
        }
        runOnce();
    }

    /** Indexes every unindexed VALIDATED/EXPORTED job of every bank; returns the jobs indexed. */
    public int runOnce() {
        List<Bank> allBanks;
        try {
            allBanks = TenantContext.runAsSystem(() -> banks.findAll());
        } catch (Exception e) {
            log.warn("Record index backfill could not list the banks ({}); skipped.", e.getClass().getSimpleName());
            return 0;
        }
        int jobs = 0;
        int written = 0;
        for (Bank bank : allBanks) {
            int bankId = bank.getBankId();
            try {
                int[] counts = TenantContext.runAs(bankId, () -> backfillBank(bankId));
                jobs += counts[0];
                written += counts[1];
            } catch (Exception e) {
                log.warn("Record index backfill failed for bank {} ({}); continuing with the rest.", bankId,
                        e.getClass().getSimpleName());
            }
        }
        if (jobs > 0) {
            log.info("Record index backfill: {} job(s) indexed, {} record(s) written.", jobs, written);
        }
        return jobs;
    }

    private int[] backfillBank(int bankId) {
        List<Integer> jobIds = records.findUnindexedJobIds(bankId, List.of(JobStep.VALIDATED, JobStep.EXPORTED));
        int written = 0;
        for (Integer jobId : jobIds) {
            written += indexer.reindex(bankId, jobId);
        }
        return new int[]{jobIds.size(), written};
    }
}
