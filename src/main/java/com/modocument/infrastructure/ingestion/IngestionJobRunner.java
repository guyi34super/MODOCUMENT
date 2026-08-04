package com.modocument.infrastructure.ingestion;

import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.model.JobStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs ingestion pipeline stages off the HTTP thread, preserving security context. */
@Component
public class IngestionJobRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobRunner.class);

    private final IngestionJobUseCase useCase;
    private final ExecutorService executor;

    public IngestionJobRunner(IngestionJobUseCase useCase) {
        this.useCase = useCase;
        this.executor = new DelegatingSecurityContextExecutorService(
                Executors.newFixedThreadPool(2));
    }

    public void enqueueResume(int jobId, JobStep until) {
        executor.execute(() -> {
            try {
                useCase.resume(jobId, until);
            } catch (Exception e) {
                log.error("Background ingestion failed for job {}: {}", jobId, e.getMessage(), e);
            }
        });
    }
}
