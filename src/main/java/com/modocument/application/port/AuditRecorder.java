package com.modocument.application.port;

import com.modocument.domain.model.AuditOutcome;

/**
 * THE audit seam (C7): use cases and controllers record security-relevant
 * actions through this port and the infrastructure implementation persists
 * them to the append-only AuditEvent table, auto-enriched with everything
 * the call site should not have to care about — occurred-at, tenant
 * (TenantContext), actor (the authenticated caller), session jti, request id
 * and client ip (request MDC).
 *
 * <p>One-liners for the common cases:
 * <pre>
 * audit.success("TEMPLATE_UPDATED", "Template", id, oldSnapshot, newSnapshot);
 * audit.denied("AUTHORIZATION_DENIED", "Route", path);
 * audit.failed("LOGIN_FAILED", "AppUser", null);
 * </pre>
 * and {@link AuditEventSpec} for full control (explicit actor/bank on
 * anonymous flows). Old/new values are serialized to JSON by the
 * implementation; pass small snapshots (name + the fields that changed).
 *
 * <p>Transaction semantics (implemented infrastructure-side):
 * {@link AuditOutcome#SUCCESS} joins the caller's transaction — a rolled-back
 * change leaves no audit of something that never happened, and a change
 * whose audit cannot be written does not commit; DENIED/FAILED write in
 * their own transaction so the row survives the refusal path's exception.
 */
public interface AuditRecorder {

    void record(AuditEventSpec spec);

    /** The action happened; entity reference only, no value snapshots. */
    default void success(String action, String entityType, String entityId) {
        success(action, entityType, entityId, null, null);
    }

    /** The action happened, with before/after snapshots (serialized to JSON). */
    default void success(String action, String entityType, String entityId,
                         Object oldValues, Object newValues) {
        record(AuditEventSpec.builder(action, AuditOutcome.SUCCESS)
                .entity(entityType, entityId)
                .oldValues(oldValues)
                .newValues(newValues)
                .build());
    }

    /** The caller was refused (authorization, revocation). */
    default void denied(String action, String entityType, String entityId) {
        record(AuditEventSpec.builder(action, AuditOutcome.DENIED)
                .entity(entityType, entityId)
                .build());
    }

    /** The action was attempted and failed (bad credentials, validation). */
    default void failed(String action, String entityType, String entityId) {
        record(AuditEventSpec.builder(action, AuditOutcome.FAILED)
                .entity(entityType, entityId)
                .build());
    }
}
