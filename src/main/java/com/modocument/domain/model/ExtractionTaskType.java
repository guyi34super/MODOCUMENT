package com.modocument.domain.model;

/** What a claimed extraction task does. */
public enum ExtractionTaskType {
    /** Fetch one web page (payload: url, depth). */
    FETCH_URL,
    /** Call one API page (payload: url or page/cursor state). */
    CALL_API,
    /** Read one database batch (payload: watermark position). */
    QUERY_DB_BATCH
}
