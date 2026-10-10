package com.modocument.application.port;

import com.modocument.domain.model.AuditOutcome;

import java.util.UUID;

/**
 * What a call site wants audited; everything NOT set here is auto-enriched
 * by the {@link AuditRecorder} implementation (occurred-at, bank from
 * TenantContext, actor from the authenticated caller, session jti, request
 * id and client ip from the request MDC). Use the fluent
 * {@link AuditRecorder} one-liners for the common cases and this builder
 * when a field must be set explicitly — e.g. a failed login names the
 * attempted email even though nobody is authenticated:
 *
 * <pre>
 * audit.record(AuditEventSpec.builder("LOGIN_FAILED", AuditOutcome.FAILED)
 *         .actorEmail(attemptedEmail)
 *         .build());
 * </pre>
 *
 * <p>{@code oldValues}/{@code newValues} are arbitrary objects (DTOs, Maps);
 * the recorder serializes them to JSON with the application ObjectMapper and
 * truncates oversized payloads — pass snapshots, not entities with lazy
 * associations.
 */
public final class AuditEventSpec {

    private final String action;
    private final AuditOutcome outcome;
    private final String entityType;
    private final String entityId;
    private final Object oldValues;
    private final Object newValues;
    private final Integer actorUserId;
    private final String actorEmail;
    private final String actorRole;
    private final Integer bankId;
    private final UUID sessionJti;

    private AuditEventSpec(Builder builder) {
        this.action = builder.action;
        this.outcome = builder.outcome;
        this.entityType = builder.entityType;
        this.entityId = builder.entityId;
        this.oldValues = builder.oldValues;
        this.newValues = builder.newValues;
        this.actorUserId = builder.actorUserId;
        this.actorEmail = builder.actorEmail;
        this.actorRole = builder.actorRole;
        this.bankId = builder.bankId;
        this.sessionJti = builder.sessionJti;
    }

    public static Builder builder(String action, AuditOutcome outcome) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("An audit event needs an action.");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("An audit event needs an outcome.");
        }
        return new Builder(action, outcome);
    }

    public String action() {
        return action;
    }

    public AuditOutcome outcome() {
        return outcome;
    }

    public String entityType() {
        return entityType;
    }

    public String entityId() {
        return entityId;
    }

    public Object oldValues() {
        return oldValues;
    }

    public Object newValues() {
        return newValues;
    }

    public Integer actorUserId() {
        return actorUserId;
    }

    public String actorEmail() {
        return actorEmail;
    }

    public String actorRole() {
        return actorRole;
    }

    public Integer bankId() {
        return bankId;
    }

    public UUID sessionJti() {
        return sessionJti;
    }

    public static final class Builder {

        private final String action;
        private final AuditOutcome outcome;
        private String entityType;
        private String entityId;
        private Object oldValues;
        private Object newValues;
        private Integer actorUserId;
        private String actorEmail;
        private String actorRole;
        private Integer bankId;
        private UUID sessionJti;

        private Builder(String action, AuditOutcome outcome) {
            this.action = action;
            this.outcome = outcome;
        }

        public Builder entity(String entityType, String entityId) {
            this.entityType = entityType;
            this.entityId = entityId;
            return this;
        }

        public Builder oldValues(Object oldValues) {
            this.oldValues = oldValues;
            return this;
        }

        public Builder newValues(Object newValues) {
            this.newValues = newValues;
            return this;
        }

        /** Names the actor explicitly (anonymous flows: login, token refresh). */
        public Builder actorUserId(Integer actorUserId) {
            this.actorUserId = actorUserId;
            return this;
        }

        public Builder actorEmail(String actorEmail) {
            this.actorEmail = actorEmail;
            return this;
        }

        public Builder actorRole(String actorRole) {
            this.actorRole = actorRole;
            return this;
        }

        /**
         * Pins the event's tenant when no TenantContext is bound (anonymous
         * and SYSTEM flows). On PostgreSQL an explicit bank that differs from
         * a bound request tenant is refused by the RLS WITH CHECK — audit
         * rows cannot be forged into another tenant.
         */
        public Builder bankId(Integer bankId) {
            this.bankId = bankId;
            return this;
        }

        public Builder sessionJti(UUID sessionJti) {
            this.sessionJti = sessionJti;
            return this;
        }

        public AuditEventSpec build() {
            return new AuditEventSpec(this);
        }
    }
}
