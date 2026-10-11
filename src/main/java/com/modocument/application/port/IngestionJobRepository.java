package com.modocument.application.port;

import com.modocument.application.dto.IngestionJobSummaryRow;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IngestionJobRepository {

    Optional<IngestionJob> getById(int id);

    /** Owner-scoped read: empty for a job the caller does not own. */
    Optional<IngestionJob> getByIdAndOwner(int id, int ownerUserId);

    List<IngestionJob> list();

    List<IngestionJob> listByOwner(int ownerUserId);

    IngestionJob save(IngestionJob job);

    /**
     * MappingJson of the most recent jobs for a template that reached
     * VALIDATED or EXPORTED — the learned-mappings signal for auto-mapping.
     * Scoped to the owner so one user's history never trains another's mapping.
     */
    List<String> listRecentMappingJson(int templateId, int ownerUserId, int limit);

    /** Jobs referencing a template — used before deleting the template. */
    int countByTemplateId(int templateId);

    /**
     * ws22: not-yet-finished jobs (PENDING / RUNNING) of users OTHER than
     * {@code ownerUserId} on the template — a bank-shared template in active
     * use by colleagues cannot be deleted or made private (409).
     */
    int countActiveByTemplateIdOfOtherUsers(int templateId, int ownerUserId);

    /** Removes the job row entirely (ws14 job deletion, gate C5). */
    void delete(IngestionJob job);

    /**
     * Jobs of one owner referencing the same stored upload — deleting a job
     * only removes the shared file when no other job still needs it.
     */
    int countByFileNameAndOwner(String fileName, int ownerUserId);

    // ---- ws28 read paths (defaults serve in-memory fakes; the JPA adapter
    // answers each with one query that never reads the checkpoint JSON) ------

    /** Newest first: created date, then id, descending. */
    Comparator<IngestionJob> NEWEST_FIRST = Comparator
            .comparing(IngestionJob::getCreatedDate, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingInt(IngestionJob::getIngestionJobId)
            .reversed();

    /**
     * One page (zero-based) of the owner's jobs as summary rows, newest
     * first — scalar columns, the template name and the small mapping only.
     */
    default List<IngestionJobSummaryRow> listSummariesByOwner(int ownerUserId, int page, int size) {
        return listByOwner(ownerUserId).stream()
                .sorted(NEWEST_FIRST)
                .skip((long) page * size)
                .limit(size)
                .map(job -> new IngestionJobSummaryRow(job.getIngestionJobId(), job.getFileName(),
                        job.getTemplateId(), null, job.getStatus(), job.getCurrentStep(),
                        job.getParsedBy(), job.getParseFallbackReason(), job.getParseConfidence(),
                        job.getValidationErrorCount(), job.getMappingJson(),
                        job.getMappedRowsJson() != null, job.getCreatedDate(), job.getUpdatedDate()))
                .toList();
    }

    /** How many jobs the owner has. */
    default long countByOwner(int ownerUserId) {
        return listByOwner(ownerUserId).size();
    }

    /** The owner's job count per status (statuses without jobs are absent). */
    default Map<JobStatus, Long> countByOwnerGroupedByStatus(int ownerUserId) {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (IngestionJob job : listByOwner(ownerUserId)) {
            if (job.getStatus() != null) {
                counts.merge(job.getStatus(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /** The owner's {@code limit} newest jobs (full rows), newest first. */
    default List<IngestionJob> listNewestByOwner(int ownerUserId, int limit) {
        return listByOwner(ownerUserId).stream().sorted(NEWEST_FIRST).limit(limit).toList();
    }
}
