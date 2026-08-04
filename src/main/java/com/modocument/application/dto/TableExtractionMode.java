package com.modocument.application.dto;

/** Which phase of the batched table-extraction LLM pipeline to run. */
public enum TableExtractionMode {
    /** Return columns and all rows in one call (small documents). */
    FULL,
    /** Return column names only (first pass of batched extraction). */
    SCHEMA,
    /** Return rows for one batch given a fixed column schema. */
    ROW_BATCH,
    /** Return columns, suggested template-field mapping, and rows in one call. */
    EXTRACT_AND_MAP
}
