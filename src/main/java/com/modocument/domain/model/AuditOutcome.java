package com.modocument.domain.model;

/**
 * How the audited action ended. Stored by enum name in AuditEvent.Outcome
 * (varchar(10) — keep names within that) and chooses the write's transaction
 * semantics in the recorder:
 * <ul>
 *   <li>{@link #SUCCESS} — the action happened; the audit row joins the
 *       caller's transaction, so a rollback removes the audit of a change
 *       that never committed.</li>
 *   <li>{@link #DENIED} — the caller was refused (authorization, revoked
 *       token); written in its own transaction so the row survives the
 *       refusal path's rollback/exception.</li>
 *   <li>{@link #FAILED} — the action was attempted and failed (bad
 *       credentials, validation); written like {@link #DENIED}.</li>
 * </ul>
 */
public enum AuditOutcome {
    SUCCESS,
    DENIED,
    FAILED
}
