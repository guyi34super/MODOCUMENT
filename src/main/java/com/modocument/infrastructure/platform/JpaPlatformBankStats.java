package com.modocument.infrastructure.platform;

import com.modocument.application.port.PlatformBankStats;
import com.modocument.domain.model.JobStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JPQL aggregates behind {@link PlatformBankStats}: three grouped queries
 * (users + last sign-in, active MFA enrolments, ingestion jobs + failures),
 * each selecting only BankId and COUNT/MAX/SUM — no entity, and so no row
 * content, is ever materialized. Works unchanged on H2 and PostgreSQL;
 * tenancy is the caller's (SYSTEM for the platform console).
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
