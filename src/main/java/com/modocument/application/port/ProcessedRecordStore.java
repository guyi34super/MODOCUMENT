package com.modocument.application.port;

import com.modocument.domain.document.ProcessedRecord;

import java.util.List;

/** Persistence port for normalized records, deduplicated per job. */
public interface ProcessedRecordStore {

    /**
     * Stores each record whose (jobId, dedupKey) is not present yet.
     *
     * @return how many were actually stored (the rest were duplicates).
     */
    int saveAllIfNew(List<ProcessedRecord> records);

    /** Cursor-style batch read; see RawRecordStore#findBatch. */
    List<ProcessedRecord> findBatch(String jobId, String afterId, int limit);

    long countByJob(String jobId);
}
