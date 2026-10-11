package com.modocument.application.port;

import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.model.ExtractionJobStatus;

import java.time.Instant;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Persistence port for extraction jobs. */
public interface ExtractionJobStore {

    ExtractionJob save(ExtractionJob job);

    /** Unscoped lookup for the background worker path (no user context). */
    Optional<ExtractionJob> getById(String id);

    /** Owner-scoped lookup for user-facing reads; empty for another user's job. */
    Optional<ExtractionJob> getByIdAndOwner(String id, int ownerUserId);

    /** Unscoped listing — must never back a user-facing endpoint. */
    List<ExtractionJob> list();

    List<ExtractionJob> listByOwner(int ownerUserId);

    /** Atomically adds delta to the named counter (concurrent workers). */
    void increment(String jobId, String counter, long delta);

    /**
     * Admin dashboard (phase 2, ws22): how many jobs of ONE bank — any
     * owner — were created at or after {@code since}, per status. Counts
     * only; statuses without jobs are absent from the map.
     */
    Map<ExtractionJobStatus, Long> countByStatusSince(int bankId, Instant since);

    /**
     * ws28 (dashboard): the owner's job count per status (statuses without
     * jobs are absent). The SQL store answers with one grouped count.
     */
    default Map<ExtractionJobStatus, Long> countByOwnerGroupedByStatus(int ownerUserId) {
        Map<ExtractionJobStatus, Long> counts = new EnumMap<>(ExtractionJobStatus.class);
        for (ExtractionJob job : listByOwner(ownerUserId)) {
            if (job.getStatus() != null) {
                counts.merge(job.getStatus(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /** ws28 (dashboard): the owner's {@code limit} newest jobs, newest first. */
    default List<ExtractionJob> listRecentByOwner(int ownerUserId, int limit) {
        return listByOwner(ownerUserId).stream().limit(limit).toList();
    }
}
