package com.modocument.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * A Connector Agent as the dashboard shows it (ws26, docs/phase2/contracts.md
 * §7.3 {@code AgentView}). {@code status} is COMPUTED: an enrolled agent
 * whose last heartbeat is older than {@code integration.agentHeartbeatTimeout}
 * reads OFFLINE. {@code latestVersion} is the version of the running
 * MoDocument (agents ship with the same release number);
 * {@code updateAvailable} is true when the agent reported an older one.
 * {@code connections} are the connection ids the agent reported on its last
 * heartbeat.
 *
 * <p>Additive to the contract: {@code createdBy}, {@code createdAt},
 * {@code enrolmentExpiresAt} (while PENDING_ENROLMENT) and
 * {@code enrolmentAvailable} — true only for the maker while the one-time
 * token can still be read ({@code GET …/agents/{id}/enrolment}).
 */
public record AgentView(int id, String name, String status, String version, String latestVersion,
                        boolean updateAvailable, Instant lastHeartbeatAt, Integer apiClientId,
                        List<Long> connections, int createdBy, Instant createdAt,
                        Instant enrolmentExpiresAt, boolean enrolmentAvailable) {

    public AgentView {
        connections = connections == null ? List.of() : List.copyOf(connections);
    }
}
