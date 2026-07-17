package com.modocument.application.port;

import com.modocument.domain.document.ExtractionJob;

import java.util.List;
import java.util.Optional;

/** Persistence port for extraction jobs. */
public interface ExtractionJobStore {

    ExtractionJob save(ExtractionJob job);

    Optional<ExtractionJob> getById(String id);

    List<ExtractionJob> list();

    /** Atomically adds delta to the named counter (concurrent workers). */
    void increment(String jobId, String counter, long delta);
}
