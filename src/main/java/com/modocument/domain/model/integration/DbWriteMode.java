package com.modocument.domain.model.integration;

/**
 * How an outbound DATABASE flow writes rows (ws25): plain parameterised INSERT,
 * or the vendor's upsert (ON CONFLICT / MERGE / ON DUPLICATE KEY) on the
 * target spec's key columns.
 */
public enum DbWriteMode {
    INSERT,
    UPSERT
}
