package com.modocument.infrastructure.audit;

import com.modocument.domain.entity.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction boundary of the audit recorder, split out of
 * {@link JpaAuditRecorder} so the two propagation modes are real proxy
 * calls (self-invocation would silently skip {@code @Transactional}):
 * <ul>
 *   <li>{@link #append} (REQUIRED) — SUCCESS events join the caller's
 *       transaction: a rollback removes the audit of a change that never
 *       happened, and a change whose audit insert fails does not commit;</li>
 *   <li>{@link #appendInNewTransaction} (REQUIRES_NEW) — DENIED/FAILED
 *       events commit independently, surviving the refusal path's
 *       exception/rollback.</li>
 * </ul>
 * Uses {@code EntityManager.persist} directly: the id is assigned app-side
 * (UUIDv7), so a Spring Data {@code save()} would needlessly merge
 * (SELECT-then-INSERT) — and the entity is {@code @Immutable}; nothing but
 * an INSERT ever leaves this class.
 *
 * <p>ws18: after each insert an {@link AuditRecordedEvent} is published
 * INSIDE the writing transaction. Its listeners (immediate alert rules, the
 * SIEM push forwarder) are {@code AFTER_COMMIT} transactional listeners, so
 * they fire once the row is durable — for a SUCCESS event that is the
 * caller's commit, for DENIED/FAILED the REQUIRES_NEW commit — and they hand
 * their work to their own executors: nothing they do can slow down, block
 * or roll back the business transaction.
 */
@Component
public class AuditEventWriter {

    @PersistenceContext
    private EntityManager entityManager;

    private final ApplicationEventPublisher events;

    public AuditEventWriter(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Transactional
    public void append(AuditEvent event) {
        entityManager.persist(event);
        events.publishEvent(new AuditRecordedEvent(event));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendInNewTransaction(AuditEvent event) {
        entityManager.persist(event);
        events.publishEvent(new AuditRecordedEvent(event));
    }
}
