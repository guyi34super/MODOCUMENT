package com.modocument.infrastructure.extraction.worker;

import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.application.port.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Crash recovery: returns tasks whose lease expired (worker died mid-task) to
 * PENDING so any instance can pick them up. Backoff-delayed retries become
 * claimable automatically once nextAttemptAt passes.
 */
@Component
@ConditionalOnProperty(name = "extraction.worker.enabled", havingValue = "true", matchIfMissing = true)
public class RetrySweeper {

    private static final Logger log = LoggerFactory.getLogger(RetrySweeper.class);

    private final ExtractionTaskQueue queue;
    private final ExtractionMetrics metrics;

    public RetrySweeper(ExtractionTaskQueue queue, ExtractionMetrics metrics) {
        this.queue = queue;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${extraction.worker.sweep-millis:15000}")
    public void sweep() {
        // runAsSystem: scheduled maintenance crosses tenants by design — it
        // must requeue EVERY tenant's expired leases, so it runs with the
        // RLS wildcard (see the call-site registry in TenantContext).
        TenantContext.runAsSystem(() -> {
            int requeued = queue.requeueExpired(Instant.now());
            if (requeued > 0) {
                metrics.expiredLeasesRequeued(requeued);
                log.info("Requeued {} extraction task(s) with expired leases", requeued);
            }
        });
    }
}
