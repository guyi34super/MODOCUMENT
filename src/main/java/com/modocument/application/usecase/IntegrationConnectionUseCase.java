package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.dto.integration.ConnectionRequest;
import com.modocument.application.dto.integration.ConnectionView;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PendingChangeRepository;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.PendingChangeStatus;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.infrastructure.integration.engine.IntegrationConnectors;
import com.modocument.infrastructure.integration.engine.IntegrationJson;
import com.modocument.infrastructure.integration.engine.IntegrationRuns;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunEventJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Connections management (ws25, INTEGRATION_ADMIN, contracts §3 SE4/§7.3).
 * A new connection, or any change other than a rename, is maker/checker
 * ({@code INTEGRATION_CONNECTION_CHANGE}, 202); a rename is direct (200);
 * deleting is direct (the lower-risk direction; refused while a flow uses
 * the connection). Secrets are write-only: views carry {@code secretSet}.
 */
@Service
public class IntegrationConnectionUseCase {

    /** The response of a connection action: a view (200) or a pending change (202). */
    public record Outcome(ConnectionView view, PendingChangeDto pendingChange, Long runId,
                          ConnectionTestResult testResult) {
    }

    private final IntegrationConnectionJpaRepository connections;
    private final IntegrationFlowJpaRepository flows;
    private final IntegrationRunJpaRepository runs;
    private final IntegrationRunEventJpaRepository runEvents;
    private final PendingChangeUseCase pendingChanges;
    private final PendingChangeRepository pendingChangeRepository;
    private final IntegrationConnectors connectors;
    private final IntegrationRuns runRecorder;
    private final CurrentUserProvider currentUser;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    public IntegrationConnectionUseCase(IntegrationConnectionJpaRepository connections,
                                        IntegrationFlowJpaRepository flows,
                                        IntegrationRunJpaRepository runs,
                                        IntegrationRunEventJpaRepository runEvents,
                                        PendingChangeUseCase pendingChanges,
                                        PendingChangeRepository pendingChangeRepository,
                                        IntegrationConnectors connectors, IntegrationRuns runRecorder,
                                        CurrentUserProvider currentUser, AuditRecorder audit,
                                        ObjectMapper objectMapper) {
        this.connections = connections;
        this.flows = flows;
        this.runs = runs;
        this.runEvents = runEvents;
        this.pendingChanges = pendingChanges;
        this.pendingChangeRepository = pendingChangeRepository;
        this.connectors = connectors;
        this.runRecorder = runRecorder;
        this.currentUser = currentUser;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ConnectionView> list() {
        int bankId = currentUser.requireBankId();
        Map<String, Integer> pending = pendingByTarget(bankId);
        return connections.findByBankIdOrderByNameAsc(bankId).stream()
                .map(connection -> view(connection, pending.get(String.valueOf(connection.getId()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConnectionView get(long id) {
        IntegrationConnection connection = require(id);
        return view(connection, pendingByTarget(connection.getBankId())
                .get(String.valueOf(connection.getId())));
    }

    /** A new connection: always 202 (maker/checker). */
    @Transactional
    public PendingChangeDto create(ConnectionRequest request) {
        return pendingChanges.submit(ChangeType.INTEGRATION_CONNECTION_CHANGE, payload(null, request), null);
    }

    /** Rename-only → 200 (direct); anything else → 202 (maker/checker). */
    @Transactional
    public Outcome update(long id, ConnectionRequest request) {
        IntegrationConnection connection = require(id);
        if (request.expectedVersion() != null && request.expectedVersion() != connection.getVersion()) {
            throw new IllegalStateException("The connection was changed by someone else; reload it.");
        }
        if (isRenameOnly(connection, request)) {
            String name = request.name() == null ? "" : request.name().trim();
            if (name.isEmpty() || name.length() > 100) {
                throw new IllegalArgumentException("The name must be 1 to 100 characters.");
            }
            boolean taken = connections.findByBankIdOrderByNameAsc(connection.getBankId()).stream()
                    .anyMatch(other -> other.getName().equalsIgnoreCase(name)
                            && !other.getId().equals(connection.getId()));
            if (taken) {
                throw new IllegalStateException("A connection named '" + name + "' already exists.");
            }
            String before = connection.getName();
            connection.setName(name);
            connection.setUpdatedAt(Instant.now());
            // Flush so @Version is incremented before the view is built: the
            // answer must carry the committed version the next edit sends.
            IntegrationConnection saved = connections.saveAndFlush(connection);
            Map<String, Object> values = IntegrationConnectionChangeApplier.auditValues(saved, "RENAMED", false);
            audit.record(AuditEventSpec.builder("INTEGRATION_CONNECTION_CHANGED", AuditOutcome.SUCCESS)
                    .entity("IntegrationConnection", String.valueOf(saved.getId()))
                    .oldValues(Map.of("name", before))
                    .newValues(values)
                    .build());
            return new Outcome(view(saved, null), null, null, null);
        }
        return new Outcome(null, pendingChanges.submit(ChangeType.INTEGRATION_CONNECTION_CHANGE,
                payload(id, request), null), null, null);
    }

    private boolean isRenameOnly(IntegrationConnection connection, ConnectionRequest request) {
        if (request.secret() != null && !request.secret().isEmpty()) {
            return false;
        }
        if (request.type() != null && !request.type().equalsIgnoreCase(connection.getType().name())) {
            return false;
        }
        if (request.mode() != null && !request.mode().equalsIgnoreCase(connection.getMode().name())) {
            return false;
        }
        if (!Objects.equals(request.agentId(), connection.getAgentId())) {
            return false;
        }
        if (request.authType() != null && !request.authType().equalsIgnoreCase(connection.getAuthType())) {
            return false;
        }
        String secretRef = request.secretRef() == null || request.secretRef().isBlank() ? null
                : request.secretRef();
        if (!Objects.equals(secretRef, connection.getSecretRef())) {
            return false;
        }
        Map<String, String> stored = IntegrationJson.config(connection.getConfigJson());
        Map<String, String> requested = new LinkedHashMap<>();
        if (request.config() != null) {
            request.config().forEach((key, value) -> {
                if (value != null && !String.valueOf(value).isBlank()) {
                    requested.put(key, String.valueOf(value).trim());
                }
            });
        }
        return stored.equals(requested);
    }

    private ObjectNode payload(Long id, ConnectionRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (id != null) {
            payload.put("connectionId", id);
        }
        payload.put("name", request.name());
        payload.put("type", request.type());
        payload.put("mode", request.mode());
        if (request.agentId() != null) {
            payload.put("agentId", request.agentId());
        }
        payload.set("config", objectMapper.valueToTree(request.config() == null ? Map.of() : request.config()));
        payload.put("authType", request.authType());
        if (request.secretRef() != null) {
            payload.put("secretRef", request.secretRef());
        }
        if (request.secret() != null && !request.secret().isEmpty()) {
            payload.set("secret", objectMapper.valueToTree(request.secret()));
        }
        return payload;
    }

    /** Direct delete; refused (409) while a flow uses the connection. */
    @Transactional
    public void delete(long id) {
        IntegrationConnection connection = require(id);
        if (flows.countByBankIdAndConnectionId(connection.getBankId(), id) > 0) {
            throw new IllegalStateException("The connection is used by a flow; delete the flow first.");
        }
        List<IntegrationRun> testRuns = runs.findByBankIdAndConnectionId(connection.getBankId(), id);
        if (!testRuns.isEmpty()) {
            runEvents.deleteByRunIds(connection.getBankId(),
                    testRuns.stream().map(IntegrationRun::getId).toList());
            runs.deleteAll(testRuns);
        }
        connections.delete(connection);
        audit.record(AuditEventSpec.builder("INTEGRATION_CONNECTION_CHANGED", AuditOutcome.SUCCESS)
                .entity("IntegrationConnection", String.valueOf(id))
                .newValues(IntegrationConnectionChangeApplier.auditValues(connection, "DELETED", false))
                .build());
    }

    /**
     * "Test connection": DIRECT runs the connector's test now and records the
     * result on the connection; AGENT queues a TEST run for the agent (202).
     */
    public Outcome test(long id) {
        IntegrationConnection connection = require(id);
        if (connection.getMode() == ConnectionMode.AGENT) {
            IntegrationRun run = runRecorder.createTest(connection.getBankId(), id, currentUser.requireUserId());
            if (run == null) {
                throw new IllegalStateException("A test is already queued; try again.");
            }
            runRecorder.log(connection.getBankId(), run.getId(), "INFO", "Connection test queued for the agent.");
            return new Outcome(null, null, run.getId(), null);
        }
        ConnectionTestResult result;
        try {
            ConnectionContext context = connectors.context(connection, true);
            result = connectors.forType(connection.getType()).test(context);
        } catch (RuntimeException e) {
            result = ConnectionTestResult.failed(
                    com.modocument.infrastructure.integration.connector.ConnectorErrors.safeMessage(e), 0);
        }
        recordTest(connection.getBankId(), id, result);
        return new Outcome(null, null, null, result);
    }

    @Transactional
    void recordTest(int bankId, long id, ConnectionTestResult result) {
        connections.findByIdAndBankId(id, bankId).ifPresent(connection -> {
            connection.setLastTestAt(Instant.now());
            String message = result.message() == null ? "" : result.message();
            connection.setLastTestResult(message.length() <= 500 ? message : message.substring(0, 500));
            if (!IntegrationConnection.STATUS_DISABLED.equals(connection.getStatus())) {
                connection.setStatus(result.ok() ? IntegrationConnection.STATUS_OK
                        : IntegrationConnection.STATUS_FAILED);
            }
            connections.save(connection);
        });
    }

    // ---- helpers -------------------------------------------------------------------

    private IntegrationConnection require(long id) {
        return connections.findByIdAndBankId(id, currentUser.requireBankId())
                .orElseThrow(() -> new NoSuchElementException("Connection " + id + " not found."));
    }

    private Map<String, Integer> pendingByTarget(int bankId) {
        Map<String, Integer> pending = new LinkedHashMap<>();
        for (PendingChange change : pendingChangeRepository.listByBankAndStatus(bankId,
                PendingChangeStatus.REQUESTED)) {
            if (change.getChangeType() == ChangeType.INTEGRATION_CONNECTION_CHANGE
                    && change.getTargetEntityId() != null) {
                pending.putIfAbsent(change.getTargetEntityId(), change.getPendingChangeId());
            }
        }
        return pending;
    }

    static ConnectionView view(IntegrationConnection connection, Integer pendingChangeId) {
        Map<String, Object> config = new LinkedHashMap<>(IntegrationJson.config(connection.getConfigJson()));
        return new ConnectionView(connection.getId(), connection.getName(), connection.getType().name(),
                connection.getMode().name(), connection.getAgentId(), config, connection.getAuthType(),
                connection.isSecretSet(), connection.getSecretRef(), connection.getStatus(),
                connection.getLastTestAt(), connection.getLastTestResult(), connection.getVersion(),
                pendingChangeId, connection.getCreatedBy(), connection.getUpdatedAt());
    }
}
