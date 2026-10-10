package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IntegrationAgent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ws26 Connector Agents (V261). Every query names the owning bank explicitly
 * on top of row level security, so the H2 suite — which has no RLS — filters
 * the same way PostgreSQL does. The only bank-less read is
 * {@link #findById} from the enrolment's registered runAsSystem call site.
 */
public interface IntegrationAgentJpaRepository extends JpaRepository<IntegrationAgent, Integer> {

    @Query("SELECT a FROM IntegrationAgent a WHERE a.bankId = :bankId "
            + "ORDER BY a.name, a.integrationAgentId")
    List<IntegrationAgent> findByBank(@Param("bankId") int bankId);

    Optional<IntegrationAgent> findByIntegrationAgentIdAndBankId(int integrationAgentId, int bankId);

    /** Row-locked read: enrolment, revocation and the maker's token read are single-shot. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM IntegrationAgent a WHERE a.integrationAgentId = :id AND a.bankId = :bankId")
    Optional<IntegrationAgent> findForUpdate(@Param("id") int id, @Param("bankId") int bankId);
}
