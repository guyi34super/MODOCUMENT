package com.modocument.application.usecase;

import com.modocument.application.dto.DashboardSummary;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.JobStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The user dashboard's numbers (ws28, {@code GET /api/dashboard/summary}):
 * count queries plus the few newest rows of each list, so the home page no
 * longer downloads every job (with every job's parsed tables) to count them.
 * About eight small statements whatever the caller's history.
 */
@Service
public class DashboardSummaryUseCase {

    /** Newest ingestion jobs on the dashboard. */
    static final int RECENT_INGESTION = 8;
    /** Newest extraction jobs and imports on the dashboard. */
    static final int RECENT_OTHER = 5;

    private final CurrentUserProvider currentUser;
    private final TemplateRepository templates;
    private final IngestionJobRepository ingestionJobs;
    private final IngestionJobUseCase ingestion;
    private final ExtractionJobStore extractionJobs;
    private final ImportHistoryRepository imports;

    public DashboardSummaryUseCase(CurrentUserProvider currentUser,
                                   TemplateRepository templates,
                                   IngestionJobRepository ingestionJobs,
                                   IngestionJobUseCase ingestion,
                                   ExtractionJobStore extractionJobs,
                                   ImportHistoryRepository imports) {
        this.currentUser = currentUser;
        this.templates = templates;
        this.ingestionJobs = ingestionJobs;
        this.ingestion = ingestion;
        this.extractionJobs = extractionJobs;
        this.imports = imports;
    }

    public DashboardSummary summary() {
        int userId = currentUser.requireUserId();
        int bankId = currentUser.requireBankId();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        long ingestionTotal = 0;
        for (Map.Entry<JobStatus, Long> entry : ingestionJobs.countByOwnerGroupedByStatus(userId).entrySet()) {
            byStatus.put(entry.getKey().name(), entry.getValue());
            ingestionTotal += entry.getValue();
        }
        DashboardSummary.Ingestion ingestionPart = new DashboardSummary.Ingestion(ingestionTotal, byStatus,
                ingestionTotal == 0 ? java.util.List.of() : ingestion.recentSummaries(RECENT_INGESTION));

        Map<ExtractionJobStatus, Long> extractionCounts = extractionJobs.countByOwnerGroupedByStatus(userId);
        long extractionTotal = extractionCounts.values().stream().mapToLong(Long::longValue).sum();
        DashboardSummary.Extraction extractionPart = new DashboardSummary.Extraction(
                extractionTotal,
                extractionCounts.getOrDefault(ExtractionJobStatus.RUNNING, 0L),
                extractionCounts.getOrDefault(ExtractionJobStatus.FAILED, 0L),
                extractionTotal == 0 ? java.util.List.of()
                        : extractionJobs.listRecentByOwner(userId, RECENT_OTHER).stream()
                        .map(job -> new DashboardSummary.ExtractionJobRow(job.getId(), job.getSourceId(),
                                job.getTemplateId(), job.getStatus() == null ? null : job.getStatus().name(),
                                job.getCreatedDate(), job.getUpdatedDate()))
                        .toList());

        long importTotal = imports.countByOwner(userId);
        DashboardSummary.Imports importsPart = new DashboardSummary.Imports(importTotal,
                importTotal == 0 ? java.util.List.of() : imports.listRecentSummariesByOwner(userId, RECENT_OTHER));

        return new DashboardSummary(templates.countAccessible(userId, bankId),
                ingestionPart, extractionPart, importsPart);
    }
}
