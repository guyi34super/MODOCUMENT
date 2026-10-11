package com.modocument.domain.model;

/**
 * Which engine produced an ingestion job's parsed table (ws23). Stored by NAME
 * in {@code IngestionJob.ParsedBy}; null for jobs parsed before V230.
 */
public enum ParsedBy {

    /** The LLM produced every row. */
    AI,

    /** Code produced the table: the Excel/structured fast paths or the code extractor. */
    CODE,

    /** Batched LLM extraction where some batches were re-parsed by code. */
    AI_PARTIAL
}
