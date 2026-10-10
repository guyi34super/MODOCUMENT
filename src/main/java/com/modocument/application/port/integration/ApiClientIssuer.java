package com.modocument.application.port.integration;

import com.modocument.domain.model.integration.ApiAuthMethod;

/**
 * Creates the ApiClient of an enrolling Connector Agent (ws26, SE2
 * implements; SE5's {@code POST /api/agent/v1/enroll} calls it after the
 * one-time enrolment token checks out) and revokes it again when the agent
 * is revoked. Kind AGENT, scope AGENT_CONNECT only.
 */
public interface ApiClientIssuer {

    /**
     * @param certificatePem the agent's client certificate for MTLS (null otherwise)
     */
    Issued issueAgentClient(int bankId, int agentId, String name, ApiAuthMethod method,
                            String certificatePem);

    /**
     * Revokes the ApiClient of a revoked Connector Agent (SE5's agent
     * revoke calls it): REVOKED + TokenVersion bump, so every token the
     * agent holds fails its very next request. Idempotent; refuses a client
     * that is not an AGENT client of {@code bankId}.
     */
    void revokeAgentClient(int bankId, int apiClientId);

    /** {@code secret} is returned exactly once (null for MTLS). */
    record Issued(int apiClientId, String clientId, String secret, ApiAuthMethod method) {
        @Override
        public String toString() {
            return "Issued[apiClientId=" + apiClientId + ", clientId=" + clientId + ", method="
                    + method + "]";
        }
    }
}
