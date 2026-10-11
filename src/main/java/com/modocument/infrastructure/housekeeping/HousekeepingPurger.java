package com.modocument.infrastructure.housekeeping;

import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.RunStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The delete rules of the ws27 {@link HousekeepingJob}. Every rule deletes
 * in BATCHES: one statement selects at most {@code batch-size} ids, a second
 * deletes exactly those, each pair in its own short transaction; the rule
 * loops until a batch comes back short or {@code max-batches} is reached.
 * So a run never holds long locks or a huge transaction, and a backlog is
 * worked off over several days instead of in one go.
 *
 * <p>The bank-scoped rules must run inside {@code TenantContext.runAs(bank)}
 * (the job does that) and still name the bank in every statement, so the
 * PostgreSQL RLS policies and the H2 suite agree. Ids are selected with JPQL
 * and deleted by id with JPQL, which keeps it portable (PostgreSQL has no
 * {@code DELETE … LIMIT}) and keeps Hibernate's own timestamp binding.
 */
@Component
public class HousekeepingPurger {

    static final List<DeliveryStatus> TERMINAL_DELIVERIES =
            List.of(DeliveryStatus.DELIVERED, DeliveryStatus.DEAD);
    static final List<RunStatus> TERMINAL_RUNS =
            List.copyOf(EnumSet.of(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED));

    @PersistenceContext
    private EntityManager entityManager;

    private final HousekeepingProperties properties;
    private final TransactionTemplate transaction;

    public HousekeepingPurger(HousekeepingProperties properties,
                              PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------ non-tenant

    /**
     * RevokedToken rows whose access token expired before {@code cutoff}: the
     * token fails its own {@code exp} check by then, so the denylist entry
     * is dead weight. The table has no BankId (V15): run as SYSTEM.
     */
    public int revokedTokens(Instant cutoff) {
        return drain(() -> {
            List<UUID> ids = entityManager.createQuery(
                            "select r.jti from RevokedToken r where r.expiresAt < :cutoff", UUID.class)
                    .setParameter("cutoff", cutoff)
                    .setMaxResults(properties.getBatchSize())
                    .getResultList();
            return ids.isEmpty() ? 0 : entityManager.createQuery(
                            "delete from RevokedToken r where r.jti in :ids")
                    .setParameter("ids", ids)
                    .executeUpdate();
        });
    }

    // ------------------------------------------------------------ per bank

    /**
     * Refresh tokens of the bank's users that expired, or were revoked, before
     * {@code cutoff} (now − 30 days). RefreshToken carries no BankId; the bank
     * is resolved through AppUsers, as the bank-wide revocation does.
     */
    public int refreshTokens(int bankId, Instant cutoff) {
        return drain(() -> {
            List<UUID> ids = entityManager.createQuery(
                            "select t.jti from RefreshToken t"
                                    + " where (t.expiresAt < :cutoff or t.revokedAt < :cutoff)"
                                    + " and t.userId in (select u.appUserId from AppUser u"
                                    + " where u.bankId = :bankId)", UUID.class)
                    .setParameter("cutoff", cutoff)
                    .setParameter("bankId", bankId)
                    .setMaxResults(properties.getBatchSize())
                    .getResultList();
            return ids.isEmpty() ? 0 : entityManager.createQuery(
                            "delete from RefreshToken t where t.jti in :ids")
                    .setParameter("ids", ids)
                    .executeUpdate();
        });
    }

    /** DELIVERED and DEAD outbox rows created before {@code cutoff}; PENDING ones are never touched. */
    public int terminalDeliveries(int bankId, Instant cutoff) {
        return drain(() -> {
            List<Long> ids = entityManager.createQuery(
                            "select d.id from OutboundDelivery d where d.bankId = :bankId"
                                    + " and d.status in :terminal and d.createdAt < :cutoff", Long.class)
                    .setParameter("bankId", bankId)
                    .setParameter("terminal", TERMINAL_DELIVERIES)
                    .setParameter("cutoff", cutoff)
                    .setMaxResults(properties.getBatchSize())
                    .getResultList();
            return deleteByIds("OutboundDelivery", bankId, ids);
        });
    }

    /**
     * Finished runs (SUCCEEDED/FAILED/CANCELLED) that ended before
     * {@code cutoff}, with their run log. A run that any outbox row still
     * points at (a PENDING retry, or a terminal row younger than the window)
     * is kept, so no foreign key is ever violated.
     *
     * @return {runs deleted, run-log events deleted}
     */
    public int[] finishedRuns(int bankId, Instant cutoff) {
        int[] totals = new int[2];
        drain(() -> {
            List<Long> ids = entityManager.createQuery(
                            "select r.id from IntegrationRun r where r.bankId = :bankId"
                                    + " and r.status in :terminal"
                                    + " and (r.finishedAt < :cutoff"
                                    + " or (r.finishedAt is null and r.createdAt < :cutoff))"
                                    + " and not exists (select 1 from OutboundDelivery d"
                                    + " where d.bankId = r.bankId and d.runId = r.id)", Long.class)
                    .setParameter("bankId", bankId)
                    .setParameter("terminal", TERMINAL_RUNS)
                    .setParameter("cutoff", cutoff)
                    .setMaxResults(properties.getBatchSize())
                    .getResultList();
            if (ids.isEmpty()) {
                return 0;
            }
            totals[1] += entityManager.createQuery(
                            "delete from IntegrationRunEvent e where e.bankId = :bankId"
                                    + " and e.runId in :ids")
                    .setParameter("bankId", bankId)
                    .setParameter("ids", ids)
                    .executeUpdate();
            int runs = deleteByIds("IntegrationRun", bankId, ids);
            totals[0] += runs;
            return runs;
        });
        return totals;
    }

    /**
     * Integration events that occurred before {@code cutoff}, unless an
     * undelivered (PENDING) outbox row still references one — the dispatcher
     * builds that delivery's payload from it.
     */
    public int integrationEvents(int bankId, Instant cutoff) {
        return drain(() -> {
            List<Long> ids = entityManager.createQuery(
                            "select e.id from IntegrationEvent e where e.bankId = :bankId"
                                    + " and e.occurredAt < :cutoff"
                                    + " and not exists (select 1 from OutboundDelivery d"
                                    + " where d.bankId = e.bankId and d.eventId = e.id"
                                    + " and d.status = :pending)", Long.class)
                    .setParameter("bankId", bankId)
                    .setParameter("cutoff", cutoff)
                    .setParameter("pending", DeliveryStatus.PENDING)
                    .setMaxResults(properties.getBatchSize())
                    .getResultList();
            return deleteByIds("IntegrationEvent", bankId, ids);
        });
    }

    /** Idempotency-Key rows past their TTL (the integration API treats them as unused anyway). */
    public int expiredIdempotencyKeys(int bankId, Instant now) {
        return drain(() -> {
            List<Long> ids = entityManager.createQuery(
                            "select k.idempotencyKeyId from IdempotencyKey k"
                                    + " where k.bankId = :bankId and k.expiresAt <= :now", Long.class)
                    .setParameter("bankId", bankId)
                    .setParameter("now", now)
                    .setMaxResults(properties.getBatchSize())
                    .getResultList();
            return ids.isEmpty() ? 0 : entityManager.createQuery(
                            "delete from IdempotencyKey k where k.bankId = :bankId"
                                    + " and k.idempotencyKeyId in :ids")
                    .setParameter("bankId", bankId)
                    .setParameter("ids", ids)
                    .executeUpdate();
        });
    }

    // ------------------------------------------------------------ helpers

    private int deleteByIds(String entity, int bankId, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return entityManager.createQuery("delete from " + entity
                        + " x where x.bankId = :bankId and x.id in :ids")
                .setParameter("bankId", bankId)
                .setParameter("ids", ids)
                .executeUpdate();
    }

    /** Runs {@code batch} (one transaction each) until it deletes less than a full batch. */
    private int drain(Supplier<Integer> batch) {
        int total = 0;
        for (int i = 0; i < properties.getMaxBatches(); i++) {
            Integer deleted = transaction.execute(status -> batch.get());
            int count = deleted == null ? 0 : deleted;
            total += count;
            if (count < properties.getBatchSize()) {
                break;
            }
        }
        return total;
    }
}
