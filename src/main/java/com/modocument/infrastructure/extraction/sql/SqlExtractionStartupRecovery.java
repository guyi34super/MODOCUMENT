package com.modocument.infrastructure.extraction.sql;

import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionJobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Settles jobs that were mid-run when the previous process died. Their task
 * queue was in memory and is gone, so a RUNNING/PAUSED job could otherwise
 * never finish (nothing re-drives it — job completion is only derived when a
 * task settles). PENDING jobs are left alone: start() re-plans their seeds.
 * Re-extracting in a fresh job is cheap — unchanged pages are deduplicated by
 * the (SourceId, ContentHash) constraint.
 */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlExtractionStartupRecovery implements ApplicationRunner {

    static final String INTERRUPTED_SUMMARY =
            "Interrupted by an application restart; start a new job to re-extract.";

    private static final Logger log = LoggerFactory.getLogger(SqlExtractionStartupRecovery.class);

    private final ExtractionJobJpaRepository jobs;

    public SqlExtractionStartupRecovery(ExtractionJobJpaRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ExtractionJobEntity> interrupted = jobs.findByStatusIn(
                List.of(ExtractionJobStatus.RUNNING, ExtractionJobStatus.PAUSED));
        if (interrupted.isEmpty()) {
            return;
        }
        for (ExtractionJobEntity job : interrupted) {
            job.setStatus(ExtractionJobStatus.FAILED);
            job.setErrorSummary(INTERRUPTED_SUMMARY);
            job.setUpdatedDate(Instant.now());
        }
        jobs.saveAll(interrupted);
        log.warn("Marked {} extraction job(s) FAILED — they were mid-run when the "
                + "application last stopped.", interrupted.size());
    }
}
