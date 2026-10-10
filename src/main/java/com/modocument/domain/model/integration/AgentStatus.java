package com.modocument.domain.model.integration;

/**
 * Lifecycle of an on-prem Connector Agent (ws26, V261). Stored by NAME.
 *
 * <pre>
 * (AGENT_REGISTER approved) ──▶ PENDING_ENROLMENT ──(POST /api/agent/v1/enroll)──▶ ONLINE
 *                                     └────────────── revoke ─────────────▶ REVOKED ◀── revoke
 * </pre>
 *
 * The stored status of an enrolled agent stays {@link #ONLINE}; the API
 * REPORTS {@link #OFFLINE} when its last heartbeat is older than the bank's
 * {@code integration.agentHeartbeatTimeout} (computed, never written by the
 * heartbeat path). {@link #REVOKED} is terminal.
 */
public enum AgentStatus {
    PENDING_ENROLMENT,
    ONLINE,
    OFFLINE,
    REVOKED
}
