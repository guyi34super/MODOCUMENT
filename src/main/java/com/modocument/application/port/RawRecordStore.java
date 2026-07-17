package com.modocument.application.port;

import com.modocument.domain.document.RawRecord;

import java.util.List;

/** Persistence port for raw fetched payloads, deduplicated by content hash. */
public interface RawRecordStore {

    /**
     * Stores the record unless the same (sourceId, contentHash) already
     * exists.
     *
     * @return true if stored, false if it was a duplicate.
     */
    boolean saveIfNew(RawRecord record);

    /**
     * Returns up to limit records of a job with id greater than afterId, in
     * id order — cursor-style batching so callers never hold the whole set.
     * Pass null to start from the beginning.
     */
    List<RawRecord> findBatch(String jobId, String afterId, int limit);

    long countByJob(String jobId);
}
