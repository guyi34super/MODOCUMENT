package com.modocument.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.AgentProtocol;
import com.modocument.application.port.integration.AgentRunPort;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.infrastructure.integration.agent.AgentTaskLongPoll;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import com.modocument.infrastructure.release.SystemVersionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The cloud side of the Connector Agent protocol (ws26,
 * docs/phase2/contracts.md §7.4) for an authenticated agent: every call
 * resolves the caller through {@link AgentCallerResolver} (an enrolled,
 * non-revoked agent whose ApiClient is the caller) and acts only for that
 * agent and bank. Runs, records and payloads belong to SE4's flow engine
 * ({@link AgentRunPort}); this class validates sizes and delegates.
 * Unknown or foreign run ids are the port's NoSuchElementException → 404.
 */
@Service
public class AgentProtocolUseCase {

    /** Heartbeat cadence the cloud asks for (seconds). */
    public static final int HEARTBEAT_SECONDS = 30;

    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,64}");
    private static final int MAX_CONNECTIONS = 200;
    private static final int MAX_MESSAGE = 200;
    private static final int MAX_ERROR = 2000;
    private static final int MAX_REFERENCE = 500;
    private static final int MAX_CURSOR = 1000;

    private final AgentCallerResolver callers;
    private final AgentRunPort runs;
    private final AgentTaskLongPoll longPoll;
    private final IntegrationAgentJpaRepository agents;
    private final SystemVersionService versions;
    private final ObjectMapper objectMapper;
    private final int maxRecordsPerPost;
    private Clock clock = Clock.systemUTC();

    public AgentProtocolUseCase(AgentCallerResolver callers, AgentRunPort runs,
                                AgentTaskLongPoll longPoll, IntegrationAgentJpaRepository agents,
                                SystemVersionService versions, ObjectMapper objectMapper,
                                @Value("${modocument.agent.max-records-per-post:1000}")
                                int maxRecordsPerPost) {
        this.callers = callers;
        this.runs = runs;
        this.longPoll = longPoll;
        this.agents = agents;
        this.versions = versions;
        this.objectMapper = objectMapper;
        this.maxRecordsPerPost = maxRecordsPerPost;
    }

    /** Test seam. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** {@code GET /tasks?wait=N}: claimable tasks, long-polled (wait clamped to 0-30 s). */
    public DeferredResult<AgentProtocol.TasksResponse> tasks(int waitSeconds) {
        AgentCallerResolver.AgentCaller caller = callers.require();
        return longPoll.poll(caller.bankId(), caller.agentId(), waitSeconds);
    }

    /** {@code POST /runs/{id}/records}: one page of pulled records (≤ max-records-per-post). */
    public void records(long runId, AgentProtocol.RecordsRequest request) {
        AgentCallerResolver.AgentCaller caller = callers.require();
        if (request == null || request.records() == null) {
            throw new IllegalArgumentException("records is required (it may be empty).");
        }
        if (request.records().size() > maxRecordsPerPost) {
            throw new IllegalArgumentException("At most " + maxRecordsPerPost
                    + " records per request; send the rest in further pages.");
        }
        if (request.nextCursor() != null && request.nextCursor().length() > MAX_CURSOR) {
            throw new IllegalArgumentException("nextCursor is too long.");
        }
        runs.acceptRecords(caller.bankId(), caller.agentId(), runId, request.records(),
                request.nextCursor(), request.last());
    }

    /** {@code GET /runs/{id}/payload}: the outbound batch the agent must write. */
    public OutboundBatch payload(long runId) {
        AgentCallerResolver.AgentCaller caller = callers.require();
        return runs.payload(caller.bankId(), caller.agentId(), runId);
    }

    /** {@code POST /runs/{id}/result}: the run's outcome. */
    public void result(long runId, AgentProtocol.ResultRequest request) {
        AgentCallerResolver.AgentCaller caller = callers.require();
        if (request == null) {
            throw new IllegalArgumentException("A result body is required.");
        }
        if (request.recordsIn() < 0 || request.recordsOut() < 0) {
            throw new IllegalArgumentException("Record counts cannot be negative.");
        }
        runs.complete(caller.bankId(), caller.agentId(), runId, new AgentRunPort.AgentRunResult(
                request.success(), request.recordsIn(), request.recordsOut(),
                truncate(request.error(), MAX_ERROR), truncate(request.remoteReference(), MAX_REFERENCE)));
    }

    /** {@code POST /heartbeat}: liveness, version and per-connection health. */
    @Transactional
    public AgentProtocol.HeartbeatResponse heartbeat(AgentProtocol.HeartbeatRequest request) {
        AgentCallerResolver.AgentCaller caller = callers.require();
        if (request == null) {
            throw new IllegalArgumentException("A heartbeat body is required.");
        }
        String version = request.version() == null ? null : request.version().strip();
        if (version != null && !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("The agent version is not a release version.");
        }
        List<AgentProtocol.ConnectionHealth> connections = new ArrayList<>();
        if (request.connections() != null) {
            for (AgentProtocol.ConnectionHealth health : request.connections()) {
                if (connections.size() >= MAX_CONNECTIONS) {
                    break;
                }
                if (health != null && health.connectionId() > 0) {
                    connections.add(new AgentProtocol.ConnectionHealth(health.connectionId(),
                            health.ok(), truncate(health.message(), MAX_MESSAGE)));
                }
            }
        }
        IntegrationAgent agent = agents
                .findByIntegrationAgentIdAndBankId(caller.agentId(), caller.bankId())
                .orElseThrow(() -> new IllegalStateException("The agent disappeared."));
        agent.setLastHeartbeatAt(clock.instant());
        if (version != null) {
            agent.setVersion(version);
        }
        agent.setLastHeartbeatJson(json(connections));
        agents.save(agent);
        return new AgentProtocol.HeartbeatResponse(versions.version(), HEARTBEAT_SECONDS);
    }

    private String json(List<AgentProtocol.ConnectionHealth> connections) {
        try {
            return objectMapper.writeValueAsString(connections);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Heartbeat snapshot could not be stored.", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
