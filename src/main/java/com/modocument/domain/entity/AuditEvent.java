package com.modocument.domain.entity;

import com.modocument.domain.model.AuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only audit trail row (C7): WHO (actor user/email/role + session
 * jti), WHAT (action + entity + old/new values), WHEN (OccurredAt), WHERE
 * FROM (request id + ip) and for WHICH tenant (BankId), with the outcome.
 *
 * <p>{@link Immutable @Immutable} and setter-less on purpose: Hibernate never
 * issues UPDATEs for this entity, and on PostgreSQL the V14 migration makes
 * tampering impossible twice over (UPDATE/DELETE/TRUNCATE revoked from the
 * runtime role, plus a trigger that raises even for the owner).
 * AuditAppendOnlyPgIT proves both. Rows are only ever created through
 * {@code AuditRecorder}; the {@link Builder} exists for its infrastructure
 * implementation.
 *
 * <p>SCHEMA PARITY: the test profile builds its H2 schema from this entity
 * (ddl-auto: create, Flyway disabled), so it must be kept in sync with
 * V14__audit_event.sql by hand. OldValues/NewValues map to jsonb on
 * PostgreSQL via {@code @JdbcTypeCode(SqlTypes.JSON)} (H2 gets a JSON
 * column); the recorder guarantees the strings are always valid JSON.
 */
@Entity
@Immutable
@Table(name = "AuditEvent", indexes = {
        @Index(name = "ix_auditevent_bankid_occurredat", columnList = "BankId, OccurredAt"),
        @Index(name = "ix_auditevent_entitytype_entityid", columnList = "EntityType, EntityId")})
public class AuditEvent {

    /** UUIDv7 (time-ordered), generated app-side by the recorder. */
    @Id
    @Column(name = "AuditEventId")
    private UUID auditEventId;

    @Column(name = "OccurredAt", nullable = false)
    private Instant occurredAt;

    /** Tenant the event belongs to; RLS substrate like every BankId column. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    /** The acting user, or null for system/anonymous actors (B1: the row still
     * names the person via ActorEmail where one was claimed, e.g. a failed
     * login for an unknown email). */
    @Column(name = "ActorUserId")
    private Integer actorUserId;

    @Column(name = "ActorEmail", length = 320)
    private String actorEmail;

    @Column(name = "ActorRole", length = 20)
    private String actorRole;

    /** jti of the access token the actor presented, when authenticated
     * (LOGIN_SUCCESS stamps the opening refresh-token jti instead, tying the
     * event to its RefreshToken row — no access token was presented yet). */
    @Column(name = "SessionJti")
    private UUID sessionJti;

    /** X-Request-Id of the request (minted or inbound) — E9 correlation key. */
    @Column(name = "RequestId", length = 64)
    private String requestId;

    @Column(name = "Ip", length = 64)
    private String ip;

    @Column(name = "Action", length = 60, nullable = false)
    private String action;

    @Column(name = "EntityType", length = 40)
    private String entityType;

    @Column(name = "EntityId", length = 64)
    private String entityId;

    /** JSON snapshot before the change (jsonb on PostgreSQL), or null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "OldValues")
    private String oldValues;

    /** JSON snapshot after the change (jsonb on PostgreSQL), or null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "NewValues")
    private String newValues;

    @Enumerated(EnumType.STRING)
    @Column(name = "Outcome", length = 10, nullable = false)
    private AuditOutcome outcome;

    /** For JPA only. */
    protected AuditEvent() {
    }

    private AuditEvent(Builder builder) {
        this.auditEventId = builder.auditEventId;
        this.occurredAt = builder.occurredAt;
        this.bankId = builder.bankId;
        this.actorUserId = builder.actorUserId;
        this.actorEmail = builder.actorEmail;
        this.actorRole = builder.actorRole;
        this.sessionJti = builder.sessionJti;
        this.requestId = builder.requestId;
        this.ip = builder.ip;
        this.action = builder.action;
        this.entityType = builder.entityType;
        this.entityId = builder.entityId;
        this.oldValues = builder.oldValues;
        this.newValues = builder.newValues;
        this.outcome = builder.outcome;
    }

    public static Builder builder(UUID auditEventId, Instant occurredAt, int bankId,
                                  String action, AuditOutcome outcome) {
        return new Builder(auditEventId, occurredAt, bankId, action, outcome);
    }

    public UUID getAuditEventId() {
        return auditEventId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public int getBankId() {
        return bankId;
    }

    public Integer getActorUserId() {
        return actorUserId;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public String getActorRole() {
        return actorRole;
    }

    public UUID getSessionJti() {
        return sessionJti;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIp() {
        return ip;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getOldValues() {
        return oldValues;
    }

    public String getNewValues() {
        return newValues;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    /** Builds the immutable row; used only by the AuditRecorder implementation. */
    public static final class Builder {

        private final UUID auditEventId;
        private final Instant occurredAt;
        private final int bankId;
        private final String action;
        private final AuditOutcome outcome;
        private Integer actorUserId;
        private String actorEmail;
        private String actorRole;
        private UUID sessionJti;
        private String requestId;
        private String ip;
        private String entityType;
        private String entityId;
        private String oldValues;
        private String newValues;

        private Builder(UUID auditEventId, Instant occurredAt, int bankId,
                        String action, AuditOutcome outcome) {
            this.auditEventId = auditEventId;
            this.occurredAt = occurredAt;
            this.bankId = bankId;
            this.action = action;
            this.outcome = outcome;
        }

        public Builder actor(Integer userId, String email, String role) {
            this.actorUserId = userId;
            this.actorEmail = email;
            this.actorRole = role;
            return this;
        }

        public Builder sessionJti(UUID sessionJti) {
            this.sessionJti = sessionJti;
            return this;
        }

        public Builder request(String requestId, String ip) {
            this.requestId = requestId;
            this.ip = ip;
            return this;
        }

        public Builder entity(String entityType, String entityId) {
            this.entityType = entityType;
            this.entityId = entityId;
            return this;
        }

        public Builder values(String oldValuesJson, String newValuesJson) {
            this.oldValues = oldValuesJson;
            this.newValues = newValuesJson;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }
}
