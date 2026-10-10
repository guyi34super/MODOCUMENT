package com.modocument.domain.model.integration;

/**
 * Stored lifecycle of an {@code ApiClient} (ws26, V260). Expiry is not a
 * stored status: {@code ApiClientView.status} reports {@code EXPIRED} for an
 * ACTIVE client whose {@code ExpiresAt} has passed.
 */
public enum ApiClientStatus {

    ACTIVE,

    /** Revoked by a bank admin: every credential and token stops at once. */
    REVOKED
}
