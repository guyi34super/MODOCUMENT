package com.modocument.domain.model;

/** What an extraction audit event records. */
public enum AuditAction {
    FETCH,
    ROBOTS_BLOCKED,
    DOMAIN_BLOCKED,
    NOT_MODIFIED,
    RATE_LIMITED,
    RETRY,
    DEDUPED,
    LLM_CALL,
    BRIDGE,
    EXPORT,
    ERROR
}
