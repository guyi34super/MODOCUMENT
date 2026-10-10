package com.modocument.infrastructure.integration.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.modocument.application.port.integration.IntegrationEventLog;
import com.modocument.domain.entity.IntegrationEvent;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.infrastructure.persistence.IntegrationEventJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The integration event stream and transactional outbox (ws25). {@link #publish}
 * runs INSIDE the producer's transaction (propagation REQUIRED): it appends
 * one {@code IntegrationEvent} and one PENDING {@code OutboundDelivery} per
 * (enabled OUTBOUND EVENT flow subscribed to the event, payload kind the
 * event can feed). A rolled-back state change therefore leaves no event and
 * no delivery; a committed one can never lose its deliveries. Payloads are
 * built at dispatch time — only references are stored.
 */
@Component
public class JpaIntegrationEventLog implements IntegrationEventLog {

    /** Which payload kinds each event can feed. */
    static final Map<FlowEvent, Set<PayloadKind>> KINDS_BY_EVENT = Map.of(
            FlowEvent.INGESTION_VALIDATED, EnumSet.of(PayloadKind.VALIDATED_RECORDS,
                    PayloadKind.REJECTED_ROWS, PayloadKind.JOB_STATUS, PayloadKind.EVENTS),
            FlowEvent.INGESTION_FAILED, EnumSet.of(PayloadKind.JOB_STATUS, PayloadKind.EVENTS),
            FlowEvent.DOC_RELEASED, EnumSet.of(PayloadKind.RELEASED_DOCUMENTS, PayloadKind.EVENTS),
            FlowEvent.EXTRACTION_COMPLETED, EnumSet.of(PayloadKind.EVENTS));

    private final IntegrationEventJpaRepository events;
    private final IntegrationFlowJpaRepository flows;
    private final OutboundDeliveryJpaRepository deliveries;
    private final Clock clock;

    public JpaIntegrationEventLog(IntegrationEventJpaRepository events,
                                  IntegrationFlowJpaRepository flows,
                                  OutboundDeliveryJpaRepository deliveries) {
        this.events = events;
        this.flows = flows;
        this.deliveries = deliveries;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional
    public void publish(int bankId, FlowEvent type, String entityType, String entityId,
                        Map<String, Object> data) {
        Instant now = clock.instant();
        IntegrationEvent event = new IntegrationEvent();
        event.setBankId(bankId);
        event.setType(type);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setDataJson(IntegrationJson.write(data == null ? Map.of() : data));
        event.setOccurredAt(now);
        IntegrationEvent saved = events.save(event);

        Object templateId = data == null ? null : data.get("templateId");
        for (IntegrationFlow flow : flows.findByBankIdAndEnabledTrueAndDirectionAndTriggerType(
                bankId, FlowDirection.OUTBOUND, FlowTrigger.EVENT)) {
            if (!flow.eventSet().contains(type)) {
                continue;
            }
            if (flow.getTemplateId() != null && templateId != null
                    && !String.valueOf(flow.getTemplateId()).equals(String.valueOf(templateId))) {
                continue;
            }
            for (PayloadKind kind : flow.payloadKindSet()) {
                if (!KINDS_BY_EVENT.get(type).contains(kind)) {
                    continue;
                }
                OutboundDelivery delivery = new OutboundDelivery();
                delivery.setBankId(bankId);
                delivery.setFlowId(flow.getId());
                delivery.setEventId(saved.getId());
                delivery.setPayloadKind(kind);
                delivery.setPayloadRef(payloadRef(kind, type, entityId, saved.getId()));
                delivery.setStatus(DeliveryStatus.PENDING);
                delivery.setNextAttemptAt(now);
                delivery.setCreatedAt(now);
                deliveries.save(delivery);
            }
        }
    }

    static String payloadRef(PayloadKind kind, FlowEvent type, String entityId, long eventId) {
        return switch (kind) {
            case VALIDATED_RECORDS, REJECTED_ROWS, JOB_STATUS -> "job:" + entityId;
            case RELEASED_DOCUMENTS -> "doc:" + entityId;
            case EVENTS -> "event:" + eventId;
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<Event> page(int bankId, long afterId, int limit) {
        int size = Math.max(1, Math.min(limit, 1000));
        return events.page(bankId, Math.max(0, afterId), PageRequest.of(0, size)).stream()
                .map(JpaIntegrationEventLog::toEvent)
                .toList();
    }

    static Event toEvent(IntegrationEvent event) {
        Map<String, Object> data;
        try {
            data = IntegrationJson.MAPPER.readValue(event.getDataJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (Exception e) {
            data = Map.of();
        }
        return new Event(event.getId(), event.getType(), event.getOccurredAt(), event.getEntityType(),
                event.getEntityId(), data);
    }
}
