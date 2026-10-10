package com.modocument.domain.model.integration;

/**
 * How a bank system authenticates to MoDocument's integration API (ws26),
 * chosen per {@code ApiClient} by the bank admin (each one can be switched off
 * per bank by the platform settings {@code integration.auth.allow*}).
 */
public enum ApiAuthMethod {

    /** {@code POST /api/integration/oauth/token} with client_id/secret → short-lived JWT. */
    OAUTH2_CLIENT_CREDENTIALS,

    /** {@code Authorization: ApiKey <prefix>.<secret>} (Argon2id-hashed at rest). */
    API_KEY,

    /** Client certificate verified by the ALB trust store; fingerprint → client. */
    MTLS
}
