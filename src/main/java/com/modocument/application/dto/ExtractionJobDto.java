package com.modocument.application.dto;

import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.ExtractionTaskStatus;

import java.time.Instant;
import java.util.Map;

/** Extraction job status view, including live task counts from the queue. */
public record ExtractionJobDto(String id, String sourceId, Integer templateId, String dataSpec,
                               ExtractionJobStatus status, boolean incremental,
                               Map<String, Long> counters,
                               Map<ExtractionTaskStatus, Long> tasks,
                               String errorSummary, Instant createdDate, Instant updatedDate) {

    public static ExtractionJobDto from(ExtractionJob job, Map<ExtractionTaskStatus, Long> tasks) {
        return new ExtractionJobDto(job.getId(), job.getSourceId(), job.getTemplateId(),
                job.getDataSpec(), job.getStatus(), job.isIncremental(), job.getCounters(),
                tasks, job.getErrorSummary(), job.getCreatedDate(), job.getUpdatedDate());
    }
}
