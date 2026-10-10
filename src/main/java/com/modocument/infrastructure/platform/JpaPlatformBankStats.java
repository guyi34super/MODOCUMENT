package com.modocument.infrastructure.platform;

import com.modocument.application.dto.BankIntegrations;
import com.modocument.application.port.PlatformBankStats;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.RunStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JPQL aggregates behind {@link PlatformBankStats}: three grouped queries
 * (users + last sign-in, active MFA enrolments, ingestion jobs + failures),
 * each selecting only BankId and COUNT/MAX/SUM — no entity, and so no row
 * content, is ever materialized; plus, for one bank, five integration
 * aggregates (connections, enabled flows, 24 h runs, dead letters, offline
 * agents). Works unchanged on H2 and PostgreSQL; tenancy is the caller's
 * (SYSTEM for the platform console).
 */
@Component
public class JpaPlatformBankStats implements PlatformBankStats {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Map<Integer, BankCounts> countsByBank(Integer bankId, LocalDateTime jobsSince) {
        String oneBank = bankId == null ? "" : " and u.bankId = :bankId";
        String oneBankJobs = bankId == null ? "" : " and j.bankId = :bankId";

        List<Object[]> users = bind(entityManager.createQuery(
                "select u.bankId, count(u), max(u.lastLoginAt) from AppUser u"
                        + " where u.disabled = false" + oneBank
                        + " group by u.bankId", Object[].class), bankId).getResultList();
        List<Object[]> mfa = bind(entityManager.createQuery(
                "select u.bankId, count(u) from AppUser u, UserMfa m"
                        + " where m.userId = u.appUserId and m.enabledAt is not null"
                        + " and u.disabled = false" + oneBank
                        + " group by u.bankId", Object[].class), bankId).getResultList();
        List<Object[]> jobs = bind(entityManager.createQuery(
                "select j.bankId, count(j),"
                        + " sum(case when j.status = :failed then 1 else 0 end)"
                        + " from IngestionJob j where j.createdDate >= :since" + oneBankJobs
                        + " group by j.bankId", Object[].class), bankId)
                .setParameter("failed", JobStatus.FAILED)
                .setParameter("since", jobsSince)
                .getResultList();

        Map<Integer, Partial> partials = new HashMap<>();
        for (Object[] row : users) {
            Partial partial = partials.computeIfAbsent(bankIdOf(row), id -> new Partial());
            partial.activeUsers = count(row[1]);
            partial.lastSignInAt = (LocalDateTime) row[2];
        }
        for (Object[] row : mfa) {
            partials.computeIfAbsent(bankIdOf(row), id -> new Partial()).mfaEnrolled = count(row[1]);
        }
        for (Object[] row : jobs) {
            Partial partial = partials.computeIfAbsent(bankIdOf(row), id -> new Partial());
            partial.jobs = count(row[1]);
            partial.failedJobs = count(row[2]);
        }
        Map<Integer, BankCounts> result = new HashMap<>();
        partials.forEach((id, partial) -> result.put(id, new BankCounts(partial.activeUsers,
                partial.mfaEnrolled, partial.jobs, partial.failedJobs, partial.lastSignInAt)));
        return result;
    }

    @Override
    public BankIntegrations integrationCounts(int bankId, Instant runsSince,
                                              Instant heartbeatBefore) {
        Object[] connections = entityManager.createQuery(
                        "select count(c), sum(case when c.status = :failing then 1 else 0 end)"
                                + " from IntegrationConnection c where c.bankId = :bankId",
                        Object[].class)
                .setParameter("bankId", bankId)
                .setParameter("failing", IntegrationConnection.STATUS_FAILED)
                .getSingleResult();
        long flowsEnabled = entityManager.createQuery(
                        "select count(f) from IntegrationFlow f"
                                + " where f.bankId = :bankId and f.enabled = true", Long.class)
                .setParameter("bankId", bankId)
                .getSingleResult();
        Object[] runs = entityManager.createQuery(
                        "select sum(case when r.status = :ok then 1 else 0 end),"
                                + " sum(case when r.status = :failed then 1 else 0 end)"
                                + " from IntegrationRun r where r.bankId = :bankId"
                                + " and r.finishedAt >= :since", Object[].class)
                .setParameter("bankId", bankId)
                .setParameter("ok", RunStatus.SUCCEEDED)
                .setParameter("failed", RunStatus.FAILED)
                .setParameter("since", runsSince)
                .getSingleResult();
        long deadLetters = entityManager.createQuery(
                        "select count(d) from OutboundDelivery d"
                                + " where d.bankId = :bankId and d.status = :dead", Long.class)
                .setParameter("bankId", bankId)
                .setParameter("dead", DeliveryStatus.DEAD)
                .getSingleResult();
        long agentsOffline = entityManager.createQuery(
                        "select count(a) from IntegrationAgent a where a.bankId = :bankId"
                                + " and a.status = :online and (a.lastHeartbeatAt is null"
                                + " or a.lastHeartbeatAt < :before)", Long.class)
                .setParameter("bankId", bankId)
                .setParameter("online", AgentStatus.ONLINE)
                .setParameter("before", heartbeatBefore)
                .getSingleResult();
        return new BankIntegrations(count(connections[0]), count(connections[1]), flowsEnabled,
                new BankIntegrations.Runs(count(runs[0]), count(runs[1])), deadLetters,
                agentsOffline);
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> query, Integer bankId) {
        return bankId == null ? query : query.setParameter("bankId", bankId);
    }

    private static int bankIdOf(Object[] row) {
        return ((Number) row[0]).intValue();
    }

    private static long count(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static final class Partial {
        private long activeUsers;
        private long mfaEnrolled;
        private long jobs;
        private long failedJobs;
        private LocalDateTime lastSignInAt;
    }
}
