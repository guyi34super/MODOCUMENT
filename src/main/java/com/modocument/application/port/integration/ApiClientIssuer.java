package com.modocument.application.port.integration;

import com.modocument.domain.model.integration.ApiAuthMethod;

/**
 * Creates the ApiClient of an enrolling Connector Agent (ws26, SE2
 * implements; SE5's {@code POST /api/agent/v1/enroll} calls it after the
 * one-time enrolment token checks out). Kind AGENT, scope AGENT_CONNECT only.
 */
public interface ApiClientIssuer {

    /**
     * @param certificatePem the agent's client certificate for MTLS (null otherwise)
     */
    Issued issueAgentClient(int bankId, int agentId, String name, ApiAuthMethod method,
                            String certificatePem);

    /** {@code secret} is returned exactly once (null for MTLS). */
    record Issued(int apiClientId, String clientId, String secret, ApiAuthMethod method) {
        @Override
        public String toString() {
            return "Issued[apiClientId=" + apiClientId + ", clientId=" + clientId + ", method="
                    + method + "]";
        }
    }
}
