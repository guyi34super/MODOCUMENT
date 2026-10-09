package com.modocument.infrastructure.audit;

import com.modocument.domain.entity.AuditEvent;

/**
 * In-process notification that one AuditEvent row was written (ws18): the
 * audit trail is the SINGLE event source for detection, and this is how its
 * consumers hear about a row without a second event pipeline. Published by
 * {@link AuditEventWriter} inside the writing transaction; consumers listen
 * with {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so they only
 * ever see rows that actually committed (a rolled-back SUCCESS event never
 * alerts or reaches the SIEM — it never happened):
 * <ul>
 *   <li>{@code ImmediateAlertListener} — evaluates the IMMEDIATE alert
 *       rules (ACCOUNT_LOCKED, TOKEN_REFRESH_REUSE_DETECTED, EXPORT_BLOCKED,
 *       ...);</li>
 *   <li>{@code SiemWebhookForwarder} — the optional push feed.</li>
 * </ul>
 * The windowed rules and the pull feed read the AuditEvent table itself.
 *
 * <p>The payload is the persisted entity: {@code @Immutable}, setter-less and
 * association-free, so it is safe to hand to another thread after commit.
 */
public record AuditRecordedEvent(AuditEvent event) {
}
