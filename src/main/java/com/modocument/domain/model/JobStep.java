package com.modocument.domain.model;

/**
 * Last successfully completed stage of an ingestion job. Persisted by NAME
 * (unlike the legacy ordinal-persisted RuleType) so constants can be added
 * safely later.
 */
public enum JobStep {
    UPLOADED,
    PARSED,
    MAPPED,
    VALIDATED,
    EXPORTED
}
