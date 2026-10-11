package com.modocument.application.port;

import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.model.ExtractionJobStatus;

import java.time.Instant;
import java.util.List;
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
}
