package com.modocument.application.port;

import com.modocument.domain.entity.IngestionJob;

/**
 * The searchable record index of validated rows (ws32,
 * docs/doccreation/flow-records-contract.md §3), as the ingestion pipeline
 * sees it: the pipeline reports what happened to a job and the index keeps
 * itself in step. Implemented by {@code TemplateRecordIndexer}; unit-test
 * wiring passes none (no index).
 */
public interface TemplateRecordIndex {

    /**
     * The job's step or rows changed — it reached VALIDATED or EXPORTED, or a
     * remap, row edit or re-validation reset it. The job's records are
     * deleted and, when it is VALIDATED/EXPORTED, its valid rows inserted
     * again, after the caller's transaction commits (at once when there is
     * none). Never throws: the index must not fail the pipeline.
     */
    void jobChanged(IngestionJob job);

    /** The job is being deleted: its records go in the caller's transaction. */
    void jobDeleted(int bankId, int jobId);
}
