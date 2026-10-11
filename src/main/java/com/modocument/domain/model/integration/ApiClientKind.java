package com.modocument.domain.model.integration;

/** What an {@code ApiClient} is (ws26). */
public enum ApiClientKind {

    /** A bank system calling {@code /api/integration/v1/**}. */
    SYSTEM,

    /** A Connector Agent calling {@code /api/agent/v1/**} (scope AGENT_CONNECT only). */
    AGENT
}
