package com.modocument.domain.model.integration;

/**
 * Lifecycle of an on-prem Connector Agent (ws26, V261). Stored by NAME.
 *
 * <pre>
 * (AGENT_REGISTER approved) ──▶ PENDING_ENROLMENT ──(POST /api/agent/v1/enroll)──▶ ONLINE ⇄ OFFLINE
 *                                     └────────────── revoke ─────────────▶ REVOKED ◀── revoke
 * </pre>
 *
 * An enrolled agent is {@link #ONLINE} or {@link #OFFLINE}. The API REPORTS
 * OFFLINE as soon as its last heartbeat is older than the bank's
 * {@code integration.agentHeartbeatTimeout}; within a minute the
 * AgentOfflineSweep also STORES OFFLINE (once per transition, audit
 * {@code AGENT_OFFLINE}, alert {@code agent-offline}), and the next
 * heartbeat stores ONLINE again (audit {@code AGENT_ONLINE}). An OFFLINE
 * agent may still call {@code /api/agent/v1}. {@link #REVOKED} is terminal.
 */
public enum AgentStatus {
    PENDING_ENROLMENT,
    ONLINE,
    OFFLINE,
    REVOKED
}
