package com.modocument.infrastructure.job;

import com.modocument.application.dto.ExtractionJobDto;
import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.usecase.ExtractionJobUseCase;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import org.springframework.stereotype.Service;

/**
 * Blocks the HTTP thread until a job reaches a target state, polling the store
 * at a fixed interval. Used for single-call job completion instead of client polling.
 */
@Service
public class JobWaitService {

    private final JobWaitProperties properties;
    private final IngestionJobUseCase ingestionJobs;
    private final ExtractionJobUseCase extractionJobs;

    public JobWaitService(JobWaitProperties properties,
                          IngestionJobUseCase ingestionJobs,
                          ExtractionJobUseCase extractionJobs) {
        this.properties = properties;
        this.ingestionJobs = ingestionJobs;
        this.extractionJobs = extractionJobs;
    }

    public IngestionJobDto waitForIngestionView(int jobId, JobStep until) {
        long deadline = System.currentTimeMillis() + properties.getWaitTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            IngestionJob job = ingestionJobs.get(jobId);
            if (ingestionReady(job, until)) {
                return ingestionJobs.view(job);
            }
            sleep();
        }
        throw new JobWaitTimeoutException(
                "Ingestion job " + jobId + " did not reach " + until + " within "
                        + properties.getWaitTimeoutSeconds() + " seconds.");
    }

    public ExtractionJobDto waitForExtractionView(String jobId) {
        long deadline = System.currentTimeMillis() + properties.getWaitTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ExtractionJob job = extractionJobs.get(jobId);
            if (extractionReady(job)) {
                return ExtractionJobDto.from(job, extractionJobs.taskCounts(job.getId()));
            }
            sleep();
        }
        throw new JobWaitTimeoutException(
                "Extraction job " + jobId + " did not finish within "
                        + properties.getWaitTimeoutSeconds() + " seconds.");
    }

    static boolean ingestionReady(IngestionJob job, JobStep until) {
        if (job == null || job.getStatus() == null || job.getCurrentStep() == null) {
            return false;
        }
        if (job.getStatus() == JobStatus.FAILED || job.getStatus() == JobStatus.CANCELLED) {
            return true;
        }
        if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.PENDING) {
            return false;
        }
        // COMPLETED between stages: keep waiting until the target step is actually reached.
        return job.getCurrentStep().ordinal() >= until.ordinal();
    }

    static boolean extractionReady(ExtractionJob job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }
        return switch (job.getStatus()) {
            case PENDING, RUNNING -> false;
            case PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED -> true;
        };
    }

    private void sleep() {
        try {
            Thread.sleep(Math.max(50, properties.getPollIntervalMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for job completion.", e);
        }
    }
}
