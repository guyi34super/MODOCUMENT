package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.model.AlertStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * AlertLog persistence (V160, ws18). Every query names its BankId (or, for
 * the sweep's retry scan, runs per bank under TenantContext.runAs) — the
 * explicit filter backs up the PostgreSQL RLS policy, and is the only
 * tenant boundary on the H2 test schema.
 */
public interface AlertLogJpaRepository extends JpaRepository<AlertLog, Integer> {

    /** The admin panel: a bank's newest alerts first. */
    List<AlertLog> findByBankIdOrderByCreatedAtDescAlertLogIdDesc(int bankId, Pageable page);

    /** Dedupe: has this burst already raised an alert inside the rule window? */
    boolean existsByBankIdAndDedupeKeyAndCreatedAtAfter(int bankId, String dedupeKey,
                                                        Instant after);

    /**
     * The sweep's retry scan (runs as SYSTEM, across banks): deliveries still
     * owed, created inside [from, to), oldest first.
     */
    List<AlertLog> findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(AlertStatus status,
                                                                      Instant from, Instant to,
                                                                      Pageable page);
}
