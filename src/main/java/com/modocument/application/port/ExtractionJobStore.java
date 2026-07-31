package com.modocument.application.port;

import com.modocument.domain.document.ExtractionJob;

import java.util.List;
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
}
