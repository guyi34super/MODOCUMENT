package com.modocument.application.dto;

import com.modocument.application.port.integration.AgentRunPort;

import java.util.List;
import java.util.Map;

/**
 * Request/response bodies of the Connector Agent protocol
 * {@code /api/agent/v1/**} (ws26, docs/phase2/contracts.md §7.4). The agent
 * project ({@code agent/}) mirrors these shapes on its side.
 */
public final class AgentProtocol {

    private AgentProtocol() {
    }

    /**
     * {@code POST /enroll}. {@code token} is the one-time enrolment token,
     * {@code authMethod} OAUTH2_CLIENT_CREDENTIALS (default) or MTLS (then
     * {@code certificatePem} is the agent's client certificate).
     */
    public record EnrollRequest(String token, String name, String version, String authMethod,
                                String certificatePem) {
        @Override
        public String toString() {
            return "EnrollRequest[name=" + name + ", version=" + version + ", authMethod="
                    + authMethod + "]";
        }
    }

    /** {@code secret} is shown exactly once (null for MTLS). */
    public record EnrollResponse(int agentId, String clientId, String secret, String tokenUrl) {
        @Override
        public String toString() {
            return "EnrollResponse[agentId=" + agentId + ", clientId=" + clientId + "]";
        }
    }

    /** {@code GET /tasks?wait=30}. */
    public record TasksResponse(List<AgentRunPort.AgentTask> tasks) {
        public TasksResponse {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    /** {@code POST /runs/{id}/records}. */
    public record RecordsRequest(List<Map<String, Object>> records, String nextCursor,
                                 boolean last) {
    }

    /** {@code POST /runs/{id}/result}. */
    public record ResultRequest(boolean success, int recordsIn, int recordsOut, String error,
                                String remoteReference) {
    }

    /** {@code POST /heartbeat}. */
    public record HeartbeatRequest(String version, String status,
                                   List<ConnectionHealth> connections) {
    }

    /** One connection's health as the agent last saw it. */
    public record ConnectionHealth(long connectionId, boolean ok, String message) {
    }

    /** Answer to a heartbeat: the release to upgrade to and the poll cadence. */
    public record HeartbeatResponse(String latestVersion, int pollSeconds) {
    }
}
