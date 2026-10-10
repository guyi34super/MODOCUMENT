package com.modocument.domain.model.integration;

/**
 * How MoDocument authenticates TO a bank system on a DIRECT REST_API or
 * WEBHOOK connection (ws25). Secret material for every type except NONE sits
 * in the connection's vault secret (or the agent's local secret).
 */
public enum OutboundAuthType {
    NONE,
    BASIC,
    BEARER,
    API_KEY_HEADER,
    OAUTH2_CLIENT_CREDENTIALS,
    MTLS,
    /** WEBHOOK only: HMAC signature header, no other auth. */
    HMAC_SIGNATURE
}
