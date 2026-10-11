package com.modocument.connector.core.rest;

/** Outbound auth of a REST_API / WEBHOOK connection (the server's {@code OutboundAuthType}, by name). */
public enum OutboundAuthType {
    NONE,
    BASIC,
    BEARER,
    API_KEY_HEADER,
    OAUTH2_CLIENT_CREDENTIALS,
    MTLS,
    HMAC_SIGNATURE
}
