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
     * ws28: {@link #saveIfNew} for a whole fetch result — element {@code i}
     * of the answer is true when {@code records.get(i)} was stored, false
     * when it was a duplicate (of a stored record or of an earlier one in the
     * list). The SQL store writes the batch in one transaction.
     */
    default boolean[] saveAllIfNew(List<RawRecord> records) {
        boolean[] stored = new boolean[records.size()];
        for (int i = 0; i < records.size(); i++) {
            stored[i] = saveIfNew(records.get(i));
        }
        return stored;
    }

    /**
     * Returns up to limit records of a job with id greater than afterId, in
     * id order — cursor-style batching so callers never hold the whole set.
     * Pass null to start from the beginning.
     */
    List<RawRecord> findBatch(String jobId, String afterId, int limit);

    long countByJob(String jobId);
}
