package com.modocument.infrastructure.siem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.model.AuditOutcome;
import org.springframework.stereotype.Component;

/**
 * Renders one AuditEvent row as a SIEM event document (ws18, SEC-031) — the
 * ONE serializer behind both transports (the pull feed and the push
 * forwarder), so they can never drift apart. Elastic Common Schema field
 * names where ECS has a home for the value, a {@code modocument.*} namespace
 * for the rest; absent values are OMITTED (ECS convention), never null.
 *
 * <p>THE SCHEMA IS A CONTRACT: docs/security/siem-event-schema.md lists every
 * field, and SiemFeedE6Test pins that list against this class's output —
 * renaming or dropping a field fails the build; adding one requires the doc
 * row in the same change.
 */
@Component
public class SiemEventMapper {

    /** The ECS version the field names follow. */
    public static final String ECS_VERSION = "8.11";
    /** event.dataset value; consumers route on it. */
    public static final String DATASET = "modocument.audit";

    private final ObjectMapper objectMapper;

    public SiemEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode toDocument(AuditEvent e) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("@timestamp", e.getOccurredAt().toString());
        doc.putObject("ecs").put("version", ECS_VERSION);

        ObjectNode event = doc.putObject("event");
        event.put("id", e.getAuditEventId().toString());
        event.put("action", e.getAction());
        event.put("outcome", ecsOutcome(e.getOutcome()));
        event.put("kind", "event");
        event.put("module", "modocument");
        event.put("dataset", DATASET);

        doc.putObject("organization").put("id", String.valueOf(e.getBankId()));

        if (e.getActorUserId() != null || e.getActorEmail() != null || e.getActorRole() != null) {
            ObjectNode user = doc.putObject("user");
            if (e.getActorUserId() != null) {
                user.put("id", String.valueOf(e.getActorUserId()));
            }
            if (e.getActorEmail() != null) {
                user.put("email", e.getActorEmail());
            }
            if (e.getActorRole() != null) {
                user.putArray("roles").add(e.getActorRole());
            }
        }
        if (e.getIp() != null) {
            doc.putObject("source").put("ip", e.getIp());
        }
        if (e.getRequestId() != null) {
            doc.putObject("trace").put("id", e.getRequestId());
        }

        ObjectNode modocument = doc.putObject("modocument");
        modocument.put("outcome", e.getOutcome().name());
        if (e.getSessionJti() != null) {
            modocument.put("session_jti", e.getSessionJti().toString());
        }
        if (e.getEntityType() != null || e.getEntityId() != null) {
            ObjectNode entity = modocument.putObject("entity");
            if (e.getEntityType() != null) {
                entity.put("type", e.getEntityType());
            }
            if (e.getEntityId() != null) {
                entity.put("id", e.getEntityId());
            }
        }
        if (e.getOldValues() != null) {
            modocument.set("old_values", json(e.getOldValues()));
        }
        if (e.getNewValues() != null) {
            modocument.set("new_values", json(e.getNewValues()));
        }
        return doc;
    }

    /** One NDJSON line (no trailing newline). */
    public String toLine(AuditEvent e) {
        return toDocument(e).toString();
    }

    /** ECS event.outcome: success / failure (DENIED and FAILED both fail). */
    static String ecsOutcome(AuditOutcome outcome) {
        return outcome == AuditOutcome.SUCCESS ? "success" : "failure";
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception notJson) {
            // The recorder only writes valid JSON; keep the raw text if not.
            return objectMapper.getNodeFactory().textNode(value);
        }
    }
}
