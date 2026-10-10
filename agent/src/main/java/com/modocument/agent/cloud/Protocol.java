package com.modocument.agent.cloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The agent's side of the MoDocument agent protocol (docs/phase2/contracts.md
 * §7.4). Mirrors the server's DTOs by shape only — the agent does not depend
 * on the main application jar. Unknown fields are ignored so a newer server
 * stays compatible with an older agent.
 */
public final class Protocol {

    private Protocol() {
    }

    public record EnrollRequest(String token, String name, String version, String authMethod,
                                String certificatePem) {
        @Override
        public String toString() {
            return "EnrollRequest[name=" + name + ", version=" + version + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnrollResponse(int agentId, String clientId, String secret, String tokenUrl) {
        @Override
        public String toString() {
            return "EnrollResponse[agentId=" + agentId + ", clientId=" + clientId + "]";
        }
    }

    /** OAuth2 client-credentials answer of the token endpoint. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(String access_token, String token_type, Long expires_in,
                                String scope) {
        @Override
        public String toString() {
            return "TokenResponse[expires_in=" + expires_in + "]";
        }
    }

    /**
     * A unit of work. {@code operation} READ, WRITE or TEST; {@code type}
     * DATABASE, REST_API or SFTP; {@code config} the connection's
     * non-secret settings; {@code secretRef} the name of the local secret;
     * {@code spec} the flow's source/target spec (identifiers only).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentTask(long runId, long connectionId, String type, String operation,
                            Map<String, String> config, String secretRef, Map<String, Object> spec,
                            String cursor, int maxRecords) {
        public AgentTask {
            config = config == null ? Map.of() : Map.copyOf(config);
            spec = spec == null ? Map.of() : spec;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TasksResponse(List<AgentTask> tasks) {
        public TasksResponse {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    public record RecordsRequest(List<Map<String, Object>> records, String nextCursor,
                                 boolean last) {
    }

    /** Server's OutboundBatch; {@code fileContent} arrives base64 and binds to bytes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutboundBatch(String idempotencyKey, String kind, String format,
                                List<Map<String, Object>> records, String fileName,
                                String contentType, byte[] fileContent) {
        public OutboundBatch {
            records = records == null ? List.of() : records;
        }
    }

    public record ResultRequest(boolean success, int recordsIn, int recordsOut, String error,
                                String remoteReference) {
    }

    public record ConnectionHealth(long connectionId, boolean ok, String message) {
    }

    public record HeartbeatRequest(String version, String status,
                                   List<ConnectionHealth> connections) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeartbeatResponse(String latestVersion, Integer pollSeconds) {
    }
}
