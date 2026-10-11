package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.integration.AgentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ws26 Connector Agents (V261). Every query names the owning bank explicitly
 * on top of row level security, so the H2 suite — which has no RLS — filters
 * the same way PostgreSQL does. The bank-less reads are {@link #findById}
 * from the enrolment's registered runAsSystem call site and
 * {@link #findBankIdsWithStatus} from the offline sweep's (bank ids only).
 */
public interface IntegrationAgentJpaRepository extends JpaRepository<IntegrationAgent, Integer> {

    @Query("SELECT a FROM IntegrationAgent a WHERE a.bankId = :bankId "
            + "ORDER BY a.name, a.integrationAgentId")
    List<IntegrationAgent> findByBank(@Param("bankId") int bankId);

    Optional<IntegrationAgent> findByIntegrationAgentIdAndBankId(int integrationAgentId, int bankId);

    /**
     * Row-locked read: enrolment, revocation, the maker's token read, the
     * heartbeat and the offline sweep's status flip are single-shot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM IntegrationAgent a WHERE a.integrationAgentId = :id AND a.bankId = :bankId")
    Optional<IntegrationAgent> findForUpdate(@Param("id") int id, @Param("bankId") int bankId);

    /**
     * Ids of the banks with an agent in {@code status} — the offline
     * sweep's only cross-bank read (registered runAsSystem call site
     * {@code AgentOfflineSweep#sweep}); bank ids only, no row content.
     */
    @Query("SELECT DISTINCT a.bankId FROM IntegrationAgent a WHERE a.status = :status ORDER BY a.bankId")
    List<Integer> findBankIdsWithStatus(@Param("status") AgentStatus status);

    /** One bank's agents in {@code status} silent since before {@code cutoff} (or never heard from). */
    @Query("SELECT a.integrationAgentId FROM IntegrationAgent a WHERE a.bankId = :bankId "
            + "AND a.status = :status AND (a.lastHeartbeatAt IS NULL OR a.lastHeartbeatAt < :cutoff) "
            + "ORDER BY a.integrationAgentId")
    List<Integer> findSilentSince(@Param("bankId") int bankId, @Param("status") AgentStatus status,
                                  @Param("cutoff") Instant cutoff);
}
