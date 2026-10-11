package com.modocument.application.port.integration;

import com.modocument.domain.model.integration.ConnectionType;

import java.util.List;
import java.util.Map;

/**
 * The flow engine's side of the agent protocol (ws25, SE4 implements; SE5's
 * {@code /api/agent/v1/**} controllers call it). Every method is scoped to
 * the calling agent: a run of another agent or bank is "not found".
 */
public interface AgentRunPort {

    /** Claims up to {@code max} AWAITING_AGENT runs of this agent (→ RUNNING_AGENT). */
    List<AgentTask> claimTasks(int bankId, int agentId, int max);

    /** Inbound records pulled by the agent; {@code last} completes the read. */
    void acceptRecords(int bankId, int agentId, long runId, List<Map<String, Object>> records,
                       String nextCursor, boolean last);

    /** Outbound: the batch the agent must write locally. */
    OutboundBatch payload(int bankId, int agentId, long runId);

    /** Final outcome reported by the agent (→ SUCCEEDED / FAILED). */
    void complete(int bankId, int agentId, long runId, AgentRunResult result);

    /**
     * A unit of work for the agent. {@code operation} is READ, WRITE or TEST;
     * {@code secretRef} names the secret in the agent's local store; the spec
     * is the flow's source/target spec (identifiers only, never SQL).
     */
    record AgentTask(long runId, long connectionId, ConnectionType type, String operation,
                     Map<String, String> config, String secretRef, Map<String, Object> spec,
                     String cursor, int maxRecords) {
    }

    record AgentRunResult(boolean success, int recordsIn, int recordsOut, String error,
                          String remoteReference) {
    }
}
