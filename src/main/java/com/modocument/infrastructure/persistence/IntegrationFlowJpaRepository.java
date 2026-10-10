package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ws25 flows (V250); every query names the bank (RLS + H2 parity). */
public interface IntegrationFlowJpaRepository extends JpaRepository<IntegrationFlow, Long> {

    List<IntegrationFlow> findByBankIdOrderByNameAsc(int bankId);

    Optional<IntegrationFlow> findByIdAndBankId(long id, int bankId);

    boolean existsByBankIdAndName(int bankId, String name);

    long countByBankIdAndConnectionId(int bankId, long connectionId);

    /** Enabled flows of one direction and trigger (the event fan-out). */
    List<IntegrationFlow> findByBankIdAndEnabledTrueAndDirectionAndTriggerType(
            int bankId, FlowDirection direction, FlowTrigger triggerType);

    /** SCHEDULE flows whose next slot is due (the scheduler's per-bank scan). */
    @Query("SELECT f FROM IntegrationFlow f WHERE f.bankId = :bankId AND f.enabled = true "
            + "AND f.triggerType = com.modocument.domain.model.integration.FlowTrigger.SCHEDULE "
            + "AND f.nextRunAt IS NOT NULL AND f.nextRunAt <= :now ORDER BY f.nextRunAt")
    List<IntegrationFlow> findDueScheduled(@Param("bankId") int bankId, @Param("now") Instant now);

    /** Banks with at least one enabled flow (the scheduler's cross-bank scan, as SYSTEM). */
    @Query("SELECT DISTINCT f.bankId FROM IntegrationFlow f WHERE f.enabled = true")
    List<Integer> findBankIdsWithEnabledFlows();
}
