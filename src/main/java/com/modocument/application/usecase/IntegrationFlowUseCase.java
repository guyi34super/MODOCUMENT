package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.dto.integration.FieldMappingView;
import com.modocument.application.dto.integration.FlowPreview;
import com.modocument.application.dto.integration.FlowRequest;
import com.modocument.application.dto.integration.FlowView;
import com.modocument.application.mapping.ColumnNameSimilarity;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PendingChangeRepository;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.PendingChangeStatus;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.export.JdbcIdentifiers;
import com.modocument.infrastructure.integration.connector.SftpIntegrationConnector;
import com.modocument.infrastructure.integration.engine.FlowSchedule;
import com.modocument.infrastructure.integration.engine.IntegrationConnectors;
import com.modocument.infrastructure.integration.engine.IntegrationJson;
import com.modocument.infrastructure.integration.engine.PayloadBuilder;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunEventJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Flows management (ws25, INTEGRATION_ADMIN, contracts §3 SE4/§7.3).
 * <ul>
 *   <li>Create/update validate everything through the connection's
 *       connector ({@code validate}), the cron against
 *       {@code integration.minScheduleInterval}, the template (bank-shared),
 *       the payload kinds and format. A new flow is DISABLED. Masking
 *       defaults to the bank's {@code integration.outboundPiiMasking}.</li>
 *   <li>Enable: INBOUND directly (200); OUTBOUND through
 *       {@code INTEGRATION_FLOW_ENABLE} (202, a second admin). Disable is
 *       always direct.</li>
 *   <li>Widening an ENABLED outbound flow (masking off, more payload kinds or
 *       events, another connection/target/template/mapping/format) disables
 *       it at once and requests approval again (202).</li>
 *   <li>Preview: a dry run of at most 20 rows; nothing is written or moved.</li>
 * </ul>
 * Audited: INTEGRATION_FLOW_SAVED / _DELETED / _ENABLED / _DISABLED.
 */
@Service
public class IntegrationFlowUseCase {

    public static final int PREVIEW_ROWS = 20;

    /** 200 with a view, or 202 with the pending change. */
    public record Outcome(FlowView view, PendingChangeDto pendingChange) {
    }

    private final IntegrationFlowJpaRepository flows;
    private final IntegrationConnectionJpaRepository connections;
    private final IntegrationRunJpaRepository runs;
    private final IntegrationRunEventJpaRepository runEvents;
    private final OutboundDeliveryJpaRepository deliveries;
    private final TemplateRepository templates;
    private final TemplateFieldsRepository templateFields;
    private final IntegrationConnectors connectors;
    private final PayloadBuilder payloads;
    private final PendingChangeUseCase pendingChanges;
    private final PendingChangeRepository pendingChangeRepository;
    private final SettingsReader settings;
    private final CurrentUserProvider currentUser;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public IntegrationFlowUseCase(IntegrationFlowJpaRepository flows,
                                  IntegrationConnectionJpaRepository connections,
                                  IntegrationRunJpaRepository runs,
                                  IntegrationRunEventJpaRepository runEvents,
                                  OutboundDeliveryJpaRepository deliveries,
                                  TemplateRepository templates, TemplateFieldsRepository templateFields,
                                  IntegrationConnectors connectors, PayloadBuilder payloads,
                                  PendingChangeUseCase pendingChanges,
                                  PendingChangeRepository pendingChangeRepository,
                                  SettingsReader settings, CurrentUserProvider currentUser,
                                  AuditRecorder audit, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.flows = flows;
        this.connections = connections;
        this.runs = runs;
        this.runEvents = runEvents;
        this.deliveries = deliveries;
        this.templates = templates;
        this.templateFields = templateFields;
        this.connectors = connectors;
        this.payloads = payloads;
        this.pendingChanges = pendingChanges;
        this.pendingChangeRepository = pendingChangeRepository;
        this.settings = settings;
        this.currentUser = currentUser;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    // ---- reads ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FlowView> list() {
        int bankId = currentUser.requireBankId();
        Map<String, Integer> pending = pendingByTarget(bankId);
        return flows.findByBankIdOrderByNameAsc(bankId).stream()
                .map(flow -> view(flow, pending.get(String.valueOf(flow.getId()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public FlowView get(long id) {
        IntegrationFlow flow = require(id);
        return view(flow, pendingByTarget(flow.getBankId()).get(String.valueOf(id)));
    }

    // ---- writes -----------------------------------------------------------------------

    @Transactional
    public FlowView create(FlowRequest request) {
        int bankId = currentUser.requireBankId();
        IntegrationFlow flow = new IntegrationFlow();
        flow.setBankId(bankId);
        flow.setCreatedBy(currentUser.requireUserId());
        flow.setCreatedAt(Instant.now());
        flow.setEnabled(false);
        applyRequest(flow, request, true);
        IntegrationFlow saved = flows.save(flow);
        auditFlow("INTEGRATION_FLOW_SAVED", saved, Map.of("change", "CREATED"));
        return view(saved, null);
    }

    @Transactional
    public Outcome update(long id, FlowRequest request) {
        IntegrationFlow flow = require(id);
        if (request.expectedVersion() != null && request.expectedVersion() != flow.getVersion()) {
            throw new IllegalStateException("The flow was changed by someone else; reload it.");
        }
        Snapshot before = Snapshot.of(flow);
        applyRequest(flow, request, false);
        boolean widened = flow.isEnabled() && flow.getDirection() == FlowDirection.OUTBOUND
                && before.widenedBy(Snapshot.of(flow));
        if (widened) {
            flow.setEnabled(false);
            flow.setApprovedVersion(null);
        }
        if (flow.isEnabled()) {
            schedule(flow);
        }
        flow.setUpdatedAt(Instant.now());
        IntegrationFlow saved = flows.saveAndFlush(flow);
        auditFlow("INTEGRATION_FLOW_SAVED", saved, Map.of("change", "UPDATED", "widened", widened));
        if (!widened) {
            return new Outcome(view(saved, pendingByTarget(saved.getBankId()).get(String.valueOf(id))), null);
        }
        auditFlow("INTEGRATION_FLOW_DISABLED", saved, Map.of("reason",
                "widened: data leaving the bank changed; approval required again"));
        return new Outcome(null, requestEnable(saved));
    }

    @Transactional
    public void delete(long id) {
        IntegrationFlow flow = require(id);
        int bankId = flow.getBankId();
        deliveries.deleteByFlow(bankId, id);
        List<IntegrationRun> flowRuns = runs.findByBankIdAndFlowId(bankId, id);
        if (!flowRuns.isEmpty()) {
            runEvents.deleteByRunIds(bankId, flowRuns.stream().map(IntegrationRun::getId).toList());
            runs.deleteAll(flowRuns);
        }
        flows.delete(flow);
        auditFlow("INTEGRATION_FLOW_DELETED", flow, Map.of("change", "DELETED"));
    }

    /** INBOUND: enabled now (200). OUTBOUND: INTEGRATION_FLOW_ENABLE (202). */
    @Transactional
    public Outcome enable(long id) {
        IntegrationFlow flow = require(id);
        if (flow.isEnabled()) {
            return new Outcome(view(flow, null), null);
        }
        validateForRun(flow);
        if (flow.getDirection() == FlowDirection.OUTBOUND) {
            return new Outcome(null, requestEnable(flow));
        }
        flow.setEnabled(true);
        schedule(flow);
        IntegrationFlow saved = flows.save(flow);
        auditFlow("INTEGRATION_FLOW_ENABLED", saved, Map.of("approval", "not required (inbound)"));
        return new Outcome(view(saved, null), null);
    }

    /** Disabling is always direct (the lower-risk direction). */
    @Transactional
    public FlowView disable(long id) {
        IntegrationFlow flow = require(id);
        if (flow.isEnabled()) {
            flow.setEnabled(false);
            flow.setNextRunAt(null);
            flow = flows.save(flow);
            auditFlow("INTEGRATION_FLOW_DISABLED", flow, Map.of("reason", "disabled by an admin"));
        }
        return view(flow, pendingByTarget(flow.getBankId()).get(String.valueOf(id)));
    }

    /** The INTEGRATION_FLOW_ENABLE applier (approval transaction, the checker's bank). */
    public void applyApprovedEnable(int bankId, long flowId, int flowVersion) {
        IntegrationFlow flow = flows.findByIdAndBankId(flowId, bankId)
                .orElseThrow(() -> new NoSuchElementException("Flow " + flowId + " not found."));
        if (flow.getVersion() != flowVersion) {
            throw new IllegalStateException("The flow changed after this request; request enabling again.");
        }
        validateForRun(flow);
        flow.setEnabled(true);
        flow.setApprovedVersion(flowVersion);
        schedule(flow);
        IntegrationFlow saved = flows.save(flow);
        auditFlow("INTEGRATION_FLOW_ENABLED", saved, Map.of("approvedVersion", flowVersion));
    }

    private PendingChangeDto requestEnable(IntegrationFlow flow) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("flowId", flow.getId());
        payload.put("flowVersion", flow.getVersion());
        return pendingChanges.submit(ChangeType.INTEGRATION_FLOW_ENABLE, payload, null);
    }

    private void schedule(IntegrationFlow flow) {
        if (flow.getTriggerType() == FlowTrigger.SCHEDULE && flow.isEnabled()) {
            flow.setNextRunAt(FlowSchedule.next(flow.getCronExpression(), Instant.now()));
        } else {
            flow.setNextRunAt(null);
        }
    }

    // ---- validation -----------------------------------------------------------------

    private void applyRequest(IntegrationFlow flow, FlowRequest request, boolean creating) {
        int bankId = flow.getBankId();
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new IllegalArgumentException("The flow name must be 1 to 100 characters.");
        }
        boolean taken = flows.findByBankIdOrderByNameAsc(bankId).stream()
                .anyMatch(other -> other.getName().equalsIgnoreCase(name)
                        && (creating || !other.getId().equals(flow.getId())));
        if (taken) {
            throw new IllegalStateException("A flow named '" + name + "' already exists.");
        }
        FlowDirection direction = enumOf(FlowDirection.class, request.direction(), "direction", null);
        if (!creating && direction != flow.getDirection()) {
            throw new IllegalArgumentException("A flow's direction cannot change; create a new flow.");
        }
        if (request.connectionId() == null) {
            throw new IllegalArgumentException("'connectionId' is required.");
        }
        IntegrationConnection connection = connections.findByIdAndBankId(request.connectionId(), bankId)
                .orElseThrow(() -> new NoSuchElementException("Connection " + request.connectionId()
                        + " not found."));
        if (connection.getType() == ConnectionType.WEBHOOK && direction == FlowDirection.INBOUND) {
            throw new IllegalArgumentException("A WEBHOOK connection can only send (OUTBOUND).");
        }
        Integer templateId = request.templateId();
        if (direction == FlowDirection.INBOUND && templateId == null) {
            throw new IllegalArgumentException("An inbound flow needs a template.");
        }
        if (templateId != null) {
            templates.getById(templateId)
                    .filter(template -> template.getBankId() == bankId && template.isShared())
                    .orElseThrow(() -> new NoSuchElementException("Template " + templateId
                            + " not found (flows use bank-shared templates)."));
        }
        FlowTrigger trigger = enumOf(FlowTrigger.class, request.trigger(), "trigger", FlowTrigger.MANUAL);
        String cron = null;
        if (trigger == FlowTrigger.SCHEDULE) {
            cron = request.cron() == null ? null : request.cron().trim();
            Duration minimum = settings.getDuration(SettingKeys.INTEGRATION_MIN_SCHEDULE_INTERVAL, bankId);
            FlowSchedule.requireMinimumInterval(cron, minimum, Instant.now());
        }
        Set<FlowEvent> events = enumSet(FlowEvent.class, request.events(), "events");
        if (trigger == FlowTrigger.EVENT) {
            if (direction != FlowDirection.OUTBOUND) {
                throw new IllegalArgumentException("EVENT triggers are for OUTBOUND flows.");
            }
            if (events.isEmpty()) {
                throw new IllegalArgumentException("An EVENT flow subscribes to at least one event.");
            }
        } else {
            events = EnumSet.noneOf(FlowEvent.class);
        }
        Set<PayloadKind> kinds = enumSet(PayloadKind.class, request.payloadKinds(), "payloadKinds");
        if (direction == FlowDirection.OUTBOUND && kinds.isEmpty()) {
            throw new IllegalArgumentException("Choose what to send (payloadKinds).");
        }
        PayloadFormat format = enumOf(PayloadFormat.class, request.format(), "format", PayloadFormat.JSON);
        if (format == PayloadFormat.PDF && !kinds.equals(EnumSet.of(PayloadKind.RELEASED_DOCUMENTS))) {
            throw new IllegalArgumentException("PDF is available for released documents only.");
        }
        if (direction == FlowDirection.OUTBOUND && kinds.contains(PayloadKind.RELEASED_DOCUMENTS)
                && connection.getType() == ConnectionType.DATABASE) {
            throw new IllegalArgumentException("A DATABASE target cannot receive documents.");
        }
        List<IntegrationJson.FieldMapping> mapping = mapping(request.fieldMapping(),
                direction == FlowDirection.OUTBOUND && connection.getType() == ConnectionType.DATABASE);
        Integer rowCap = request.rowCap();
        if (rowCap != null && rowCap < 1) {
            throw new IllegalArgumentException("'rowCap' must be at least 1.");
        }
        boolean maskPii = request.maskPii() != null ? request.maskPii()
                : creating ? settings.getBool(SettingKeys.INTEGRATION_OUTBOUND_PII_MASKING, bankId)
                : flow.isMaskPii();

        Map<String, Object> source = direction == FlowDirection.INBOUND
                ? withDefaults(connection.getType(), request.sourceSpec()) : null;
        Map<String, Object> target = direction == FlowDirection.OUTBOUND
                ? (request.targetSpec() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.targetSpec()))
                : null;
        ConnectionContext context = connectors.context(connection, connection.getMode() == ConnectionMode.DIRECT);
        IntegrationConnector connector = connectors.forType(connection.getType());
        connector.validate(context, direction == FlowDirection.INBOUND ? source : target,
                direction == FlowDirection.OUTBOUND);

        flow.setName(name);
        flow.setDirection(direction);
        flow.setConnectionId(connection.getId());
        flow.setTemplateId(templateId);
        flow.setTriggerType(trigger);
        flow.setCronExpression(cron);
        flow.setEventSet(events);
        flow.setPayloadKindSet(kinds);
        flow.setFormat(format);
        flow.setSourceSpecJson(source == null ? null : IntegrationJson.write(source));
        flow.setTargetSpecJson(target == null ? null : IntegrationJson.write(target));
        flow.setFieldMappingJson(mapping.isEmpty() ? null : IntegrationJson.write(mapping));
        flow.setMaskPii(maskPii);
        flow.setRowCap(rowCap);
    }

    /** Before enabling or applying an approval: the stored specs still pass the connector. */
    private void validateForRun(IntegrationFlow flow) {
        IntegrationConnection connection = connections.findByIdAndBankId(flow.getConnectionId(), flow.getBankId())
                .orElseThrow(() -> new IllegalStateException("The flow's connection no longer exists."));
        boolean outbound = flow.getDirection() == FlowDirection.OUTBOUND;
        ConnectionContext context = connectors.context(connection, connection.getMode() == ConnectionMode.DIRECT);
        connectors.forType(connection.getType()).validate(context,
                IntegrationJson.spec(outbound ? flow.getTargetSpecJson() : flow.getSourceSpecJson()), outbound);
        if (flow.getTriggerType() == FlowTrigger.SCHEDULE) {
            FlowSchedule.requireMinimumInterval(flow.getCronExpression(), settings.getDuration(
                    SettingKeys.INTEGRATION_MIN_SCHEDULE_INTERVAL, flow.getBankId()), Instant.now());
        }
    }

    private static Map<String, Object> withDefaults(ConnectionType type, Map<String, Object> spec) {
        Map<String, Object> copy = spec == null ? new LinkedHashMap<>() : new LinkedHashMap<>(spec);
        if (type == ConnectionType.SFTP && (copy.get("glob") == null || String.valueOf(copy.get("glob")).isBlank())) {
            copy.put("glob", "*");
        }
        return copy;
    }

    private static List<IntegrationJson.FieldMapping> mapping(List<FieldMappingView> requested,
                                                              boolean targetsAreColumns) {
        List<IntegrationJson.FieldMapping> mapping = new ArrayList<>();
        if (requested == null) {
            return mapping;
        }
        if (requested.size() > 500) {
            throw new IllegalArgumentException("At most 500 field mappings.");
        }
        Set<String> targets = new LinkedHashSet<>();
        for (FieldMappingView pair : requested) {
            if (pair == null || pair.from() == null || pair.to() == null
                    || pair.from().isBlank() || pair.to().isBlank()) {
                continue;
            }
            String from = pair.from().trim();
            String to = pair.to().trim();
            if (from.length() > 255 || to.length() > 255) {
                throw new IllegalArgumentException("Mapped names are at most 255 characters.");
            }
            if (targetsAreColumns) {
                JdbcIdentifiers.requireValidIdentifier(to, "target column");
            }
            if (!targets.add(to.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("'" + to + "' is mapped twice.");
            }
            mapping.add(new IntegrationJson.FieldMapping(from, to));
        }
        return mapping;
    }

    // ---- preview -----------------------------------------------------------------------

    /** A dry run: at most 20 rows, nothing written, nothing moved. */
    public FlowPreview preview(long id) throws Exception {
        IntegrationFlow flow = require(id);
        IntegrationConnection connection = connections.findByIdAndBankId(flow.getConnectionId(), flow.getBankId())
                .orElseThrow(() -> new IllegalStateException("The flow's connection no longer exists."));
        List<Map<String, Object>> rows;
        if (flow.getDirection() == FlowDirection.INBOUND) {
            if (connection.getMode() == ConnectionMode.AGENT) {
                throw new IllegalStateException("Preview reads DIRECT connections only; an AGENT "
                        + "connection is read by the bank's agent.");
            }
            ConnectionContext context = connectors.context(connection, true);
            IntegrationConnector connector = connectors.forType(connection.getType());
            Map<String, Object> spec = IntegrationJson.spec(flow.getSourceSpecJson());
            if (connector instanceof SftpIntegrationConnector sftp) {
                rows = sftp.listFiles(context, spec, PREVIEW_ROWS);
            } else {
                String cursor = connection.getType() == ConnectionType.DATABASE ? flow.getCursorValue() : null;
                ReadBatch batch = connector.read(context, spec, cursor, PREVIEW_ROWS);
                rows = batch.records();
            }
        } else {
            rows = outboundSample(flow);
        }
        List<Map<String, Object>> limited = rows.size() > PREVIEW_ROWS ? rows.subList(0, PREVIEW_ROWS) : rows;
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        limited.forEach(row -> columns.addAll(row.keySet()));
        List<FlowPreview.SuggestedMapping> suggested = new ArrayList<>();
        if (flow.getDirection() == FlowDirection.INBOUND && flow.getTemplateId() != null) {
            List<TemplateFields> fields = templateFields.listByTemplateId(flow.getTemplateId());
            for (String column : columns) {
                String best = null;
                double bestScore = 0;
                for (TemplateFields field : fields) {
                    double score = ColumnNameSimilarity.score(column, field.getFieldName(),
                            field.getAliases(), Map.of());
                    if (score > bestScore) {
                        bestScore = score;
                        best = field.getFieldName();
                    }
                }
                if (best != null && bestScore >= 0.5) {
                    suggested.add(new FlowPreview.SuggestedMapping(column, best,
                            Math.round(bestScore * 100) / 100.0));
                }
            }
        }
        return new FlowPreview(new ArrayList<>(columns), new ArrayList<>(limited), suggested);
    }

    /** The payload the flow would send for its template's latest validated job (or events). */
    private List<Map<String, Object>> outboundSample(IntegrationFlow flow) {
        Set<PayloadKind> kinds = flow.payloadKindSet();
        PayloadKind kind = kinds.contains(PayloadKind.VALIDATED_RECORDS) ? PayloadKind.VALIDATED_RECORDS
                : kinds.contains(PayloadKind.REJECTED_ROWS) ? PayloadKind.REJECTED_ROWS
                : kinds.contains(PayloadKind.JOB_STATUS) ? PayloadKind.JOB_STATUS
                : kinds.contains(PayloadKind.EVENTS) ? PayloadKind.EVENTS : null;
        if (kind == null) {
            return List.of();
        }
        String ref;
        if (kind == PayloadKind.EVENTS) {
            List<Long> ids = jdbc.queryForList("SELECT IntegrationEventId FROM IntegrationEvent WHERE "
                    + "BankId = ? ORDER BY IntegrationEventId DESC LIMIT " + PREVIEW_ROWS, Long.class,
                    flow.getBankId());
            if (ids.isEmpty()) {
                return List.of();
            }
            ref = "events:" + ids.get(ids.size() - 1) + "-" + ids.get(0);
        } else {
            if (flow.getTemplateId() == null) {
                return List.of();
            }
            List<Integer> jobs = jdbc.queryForList("SELECT IngestionJobId FROM IngestionJob WHERE BankId = ? "
                    + "AND TemplateId = ? AND CurrentStep IN ('VALIDATED', 'EXPORTED') "
                    + "ORDER BY IngestionJobId DESC LIMIT 1", Integer.class, flow.getBankId(),
                    flow.getTemplateId());
            if (jobs.isEmpty()) {
                return List.of();
            }
            ref = "job:" + jobs.get(0);
        }
        OutboundDelivery sample = new OutboundDelivery();
        sample.setBankId(flow.getBankId());
        sample.setFlowId(flow.getId());
        sample.setPayloadKind(kind);
        sample.setPayloadRef(ref);
        try {
            OutboundBatch batch = payloads.build(flow, sample, false);
            return batch.records();
        } catch (PayloadBuilder.PayloadRefusedException refused) {
            throw new IllegalStateException(refused.getMessage());
        }
    }

    // ---- views ------------------------------------------------------------------------

    private IntegrationFlow require(long id) {
        return flows.findByIdAndBankId(id, currentUser.requireBankId())
                .orElseThrow(() -> new NoSuchElementException("Flow " + id + " not found."));
    }

    private Map<String, Integer> pendingByTarget(int bankId) {
        Map<String, Integer> pending = new LinkedHashMap<>();
        for (PendingChange change : pendingChangeRepository.listByBankAndStatus(bankId,
                PendingChangeStatus.REQUESTED)) {
            if (change.getChangeType() == ChangeType.INTEGRATION_FLOW_ENABLE
                    && change.getTargetEntityId() != null) {
                pending.putIfAbsent(change.getTargetEntityId(), change.getPendingChangeId());
            }
        }
        return pending;
    }

    FlowView view(IntegrationFlow flow, Integer pendingChangeId) {
        String connectionName = connections.findByIdAndBankId(flow.getConnectionId(), flow.getBankId())
                .map(IntegrationConnection::getName).orElse(null);
        String templateName = flow.getTemplateId() == null ? null
                : templates.getById(flow.getTemplateId()).map(Template::getName).orElse(null);
        FlowView.LastRun lastRun = runs.findFirstByBankIdAndFlowIdOrderByCreatedAtDescIdDesc(
                        flow.getBankId(), flow.getId())
                .map(run -> new FlowView.LastRun(run.getId(), run.getStatus().name(), run.getFinishedAt()))
                .orElse(null);
        List<FieldMappingView> mapping = IntegrationJson.mapping(flow.getFieldMappingJson()).stream()
                .map(pair -> new FieldMappingView(pair.from(), pair.to())).toList();
        return new FlowView(flow.getId(), flow.getName(), flow.getDirection().name(),
                flow.getConnectionId(), connectionName, flow.getTemplateId(), templateName,
                flow.getTriggerType().name(), flow.getCronExpression(),
                flow.eventSet().stream().map(Enum::name).toList(),
                flow.payloadKindSet().stream().map(Enum::name).toList(),
                flow.getFormat().name(),
                flow.getSourceSpecJson() == null ? null : IntegrationJson.spec(flow.getSourceSpecJson()),
                flow.getTargetSpecJson() == null ? null : IntegrationJson.spec(flow.getTargetSpecJson()),
                mapping, flow.isMaskPii(), flow.getRowCap(), flow.isEnabled(), pendingChangeId,
                flow.getVersion(), flow.getNextRunAt(), lastRun);
    }

    private void auditFlow(String action, IntegrationFlow flow, Map<String, Object> extra) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("flowId", flow.getId());
        values.put("name", flow.getName());
        values.put("direction", flow.getDirection() == null ? null : flow.getDirection().name());
        values.put("connectionId", flow.getConnectionId());
        values.put("payloadKinds", flow.getPayloadKinds());
        values.put("maskPii", flow.isMaskPii());
        values.put("enabled", flow.isEnabled());
        values.put("version", flow.getVersion());
        values.putAll(extra);
        audit.record(AuditEventSpec.builder(action, AuditOutcome.SUCCESS)
                .entity("IntegrationFlow", String.valueOf(flow.getId()))
                .newValues(values)
                .build());
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field, E fallback) {
        if (value == null || value.isBlank()) {
            if (fallback == null) {
                throw new IllegalArgumentException("'" + field + "' is required.");
            }
            return fallback;
        }
        return IntegrationConnectionChangeApplier.enumOf(type, value, field);
    }

    private static <E extends Enum<E>> Set<E> enumSet(Class<E> type, List<String> values, String field) {
        EnumSet<E> set = EnumSet.noneOf(type);
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    set.add(IntegrationConnectionChangeApplier.enumOf(type, value, field));
                }
            }
        }
        return set;
    }

    /** What decides whether an enabled outbound flow was widened. */
    record Snapshot(boolean maskPii, Set<PayloadKind> kinds, Set<FlowEvent> events, long connectionId,
                    Integer templateId, String target, String mapping, PayloadFormat format) {

        static Snapshot of(IntegrationFlow flow) {
            return new Snapshot(flow.isMaskPii(), flow.payloadKindSet(), flow.eventSet(),
                    flow.getConnectionId(), flow.getTemplateId(), flow.getTargetSpecJson(),
                    flow.getFieldMappingJson(), flow.getFormat());
        }

        boolean widenedBy(Snapshot after) {
            return (maskPii && !after.maskPii)
                    || !kinds.containsAll(after.kinds)
                    || !events.containsAll(after.events)
                    || connectionId != after.connectionId
                    || !Objects.equals(templateId, after.templateId)
                    || !Objects.equals(target, after.target)
                    || !Objects.equals(mapping, after.mapping)
                    || format != after.format;
        }
    }
}
