package com.modocument.infrastructure.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.Uuid7;
import com.modocument.infrastructure.security.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link AuditRecorder} backed by the append-only AuditEvent table (V14).
 * Call sites hand over an {@link AuditEventSpec}; everything else is
 * enriched here:
 * <ul>
 *   <li>id (UUIDv7) + OccurredAt;</li>
 *   <li>BankId — spec override first, else {@link TenantContext}; with no
 *       tenant bound (anonymous flows) and no override, the DEFAULT bank
 *       (id 1) is used so the NOT NULL column always has an honest value —
 *       an unknown-email login attempt belongs to no tenant, and the
 *       default bank is the documented home for unattributable rows;</li>
 *   <li>actor — spec override first, else {@link CurrentUserProvider}
 *       (anonymous callers are tolerated: null actor fields);</li>
 *   <li>SessionJti — the authenticated JWT's jti claim, when present;</li>
 *   <li>RequestId + Ip — the request MDC keys {@code requestId}/{@code ip}
 *       that {@link RequestIdFilter} maintains (copied onto async threads by
 *       TenantTaskDecorator, so background work stays traceable).</li>
 * </ul>
 *
 * <p>Old/new values are serialized with the application {@link ObjectMapper}.
 * Payloads above {@value #MAX_VALUES_CHARS} chars are replaced by a marker
 * object {@code {"_truncated":true,"_originalLength":N,"_preview":"..."}} —
 * always valid JSON, so the jsonb columns never reject a row.
 *
 * <p>Transactions: SUCCESS joins the caller's transaction and a write
 * failure PROPAGATES (no change may commit unaudited — C7); DENIED/FAILED
 * write in REQUIRES_NEW and a write failure is logged, never masking the
 * refusal the caller is about to signal. When no tenant is bound, the write
 * runs under {@code TenantContext.runAs(event bank)} so the PostgreSQL RLS
 * WITH CHECK accepts the insert.
 */
@Service
public class JpaAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(JpaAuditRecorder.class);

    /** Cap for each serialized values payload (~64KB of JSON text). */
    static final int MAX_VALUES_CHARS = 64 * 1024;

    /** How much of an oversized payload the truncation marker preserves. */
    static final int TRUNCATION_PREVIEW_CHARS = 2 * 1024;

    private final AuditEventWriter writer;
    private final ObjectMapper objectMapper;
    private final CurrentUserProvider currentUser;

    public JpaAuditRecorder(AuditEventWriter writer, ObjectMapper objectMapper,
                            CurrentUserProvider currentUser) {
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.currentUser = currentUser;
    }

    @Override
    public void record(AuditEventSpec spec) {
        AuditEvent event = enrich(spec);
        boolean independent = spec.outcome() != AuditOutcome.SUCCESS;
        try {
            write(event, independent);
            // One INFO line per audit event: with the MDC-extended pattern
            // (logback-spring.xml) it carries requestId/userId/bankId, making
            // the action traceable end-to-end in the logs alone (E9). Never
            // log the value payloads here — they may contain PII.
            log.info("audit action={} outcome={} entity={}:{} actorUserId={} bankId={}",
                    event.getAction(), event.getOutcome(), event.getEntityType(),
                    event.getEntityId(), event.getActorUserId(), event.getBankId());
        } catch (RuntimeException e) {
            if (!independent) {
                // A SUCCESS event that cannot be written must fail the
                // surrounding change — no mutation commits unaudited.
                throw e;
            }
            log.error("audit write failed for action={} outcome={} (the refusal itself "
                            + "proceeds; investigate the audit store!)",
                    event.getAction(), event.getOutcome(), e);
        }
    }

    private void write(AuditEvent event, boolean independent) {
        if (TenantContext.currentBankId().isEmpty() && !TenantContext.isSystem()) {
            // Anonymous flow (login, refresh): bind the event's own bank for
            // the insert so the RLS WITH CHECK passes; restored right after.
            TenantContext.runAs(event.getBankId(), () -> dispatch(event, independent));
        } else {
            dispatch(event, independent);
        }
    }

    private void dispatch(AuditEvent event, boolean independent) {
        if (independent) {
            writer.appendInNewTransaction(event);
        } else {
            writer.append(event);
        }
    }

    private AuditEvent enrich(AuditEventSpec spec) {
        Integer actorUserId = spec.actorUserId();
        String actorEmail = spec.actorEmail();
        String actorRole = spec.actorRole();
        Integer bankId = spec.bankId();
        UUID sessionJti = spec.sessionJti();

        if (actorUserId == null) {
            actorUserId = quietly(currentUser::requireUserId);
        }
        if (actorRole == null) {
            Role role = quietly(currentUser::role);
            actorRole = role == null ? null : role.name();
        }
        if (bankId == null) {
            bankId = TenantContext.currentBankId()
                    .orElseGet(() -> quietly(currentUser::requireBankId));
        }
        if (sessionJti == null) {
            sessionJti = currentJwtJti();
        }
        return AuditEvent.builder(
                        Uuid7.generate(), Instant.now(),
                        bankId == null ? Bank.DEFAULT_BANK_ID : bankId,
                        truncate(spec.action(), 60), spec.outcome())
                .actor(actorUserId, truncate(actorEmail, 320), truncate(actorRole, 20))
                .sessionJti(sessionJti)
                .request(truncate(MDC.get(RequestIdFilter.MDC_REQUEST_ID), 64),
                        truncate(MDC.get(RequestIdFilter.MDC_CLIENT_IP), 64))
                .entity(truncate(spec.entityType(), 40), truncate(spec.entityId(), 64))
                .values(toJson(spec.oldValues()), toJson(spec.newValues()))
                .build();
    }

    /**
     * Serializes a snapshot to JSON; oversized payloads become the documented
     * truncation marker, unserializable ones a {@code {"_unserializable":...}}
     * marker — the audit row is written either way.
     */
    String toJson(Object value) {
        if (value == null) {
            return null;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("audit values of type {} are not serializable; recording a marker",
                    value.getClass().getName(), e);
            ObjectNode marker = objectMapper.createObjectNode();
            marker.put("_unserializable", value.getClass().getName());
            return marker.toString();
        }
        if (json.length() <= MAX_VALUES_CHARS) {
            return json;
        }
        ObjectNode marker = objectMapper.createObjectNode();
        marker.put("_truncated", true);
        marker.put("_originalLength", json.length());
        marker.put("_preview", json.substring(0, TRUNCATION_PREVIEW_CHARS));
        return marker.toString();
    }

    /** The jti of the authenticated caller's access token, when parseable. */
    private static UUID currentJwtJti() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && jwt.getId() != null) {
            try {
                return UUID.fromString(jwt.getId());
            } catch (IllegalArgumentException notAUuid) {
                return null;
            }
        }
        return null;
    }

    /** Anonymous/system callers make CurrentUserProvider throw; that is fine here. */
    private static <T> T quietly(ProviderCall<T> call) {
        try {
            return call.get();
        } catch (IllegalStateException anonymous) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        T get();
    }
}
