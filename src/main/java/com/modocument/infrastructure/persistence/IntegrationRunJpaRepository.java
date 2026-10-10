package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.model.integration.RunStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** ws25 runs (V250); every query names the bank (RLS + H2 parity). */
public interface IntegrationRunJpaRepository extends JpaRepository<IntegrationRun, Long> {

    Optional<IntegrationRun> findByIdAndBankId(long id, int bankId);

    @Query("SELECT r FROM IntegrationRun r WHERE r.bankId = :bankId "
            + "ORDER BY r.createdAt DESC, r.id DESC")
    List<IntegrationRun> listAll(@Param("bankId") int bankId, Pageable page);

    @Query("SELECT r FROM IntegrationRun r WHERE r.bankId = :bankId AND r.flowId = :flowId "
            + "ORDER BY r.createdAt DESC, r.id DESC")
    List<IntegrationRun> listByFlow(@Param("bankId") int bankId, @Param("flowId") long flowId,
                                    Pageable page);

    @Query("SELECT r FROM IntegrationRun r WHERE r.bankId = :bankId AND r.status = :status "
            + "ORDER BY r.createdAt DESC, r.id DESC")
    List<IntegrationRun> listByStatus(@Param("bankId") int bankId,
                                      @Param("status") RunStatus status, Pageable page);

    /** The newest run of a flow (FlowView.lastRun). */
    Optional<IntegrationRun> findFirstByBankIdAndFlowIdOrderByCreatedAtDescIdDesc(int bankId,
                                                                                  Long flowId);

    /** Non-terminal runs started (or created) before the cut-off: the run-timeout sweep. */
    @Query("SELECT r FROM IntegrationRun r WHERE r.bankId = :bankId AND r.status IN :statuses "
            + "AND COALESCE(r.startedAt, r.createdAt) < :cutoff")
    List<IntegrationRun> findStale(@Param("bankId") int bankId,
                                   @Param("statuses") Collection<RunStatus> statuses,
                                   @Param("cutoff") Instant cutoff);

    /** AWAITING_AGENT runs on connections of one agent, oldest first (claimTasks). */
    @Query("SELECT r FROM IntegrationRun r, IntegrationConnection c "
            + "WHERE r.connectionId = c.id AND r.bankId = :bankId AND c.bankId = :bankId "
            + "AND c.agentId = :agentId "
            + "AND r.status = com.modocument.domain.model.integration.RunStatus.AWAITING_AGENT "
            + "ORDER BY r.createdAt, r.id")
    List<IntegrationRun> findAwaitingAgent(@Param("bankId") int bankId,
                                           @Param("agentId") int agentId, Pageable page);

    List<IntegrationRun> findByBankIdAndFlowId(int bankId, Long flowId);

    List<IntegrationRun> findByBankIdAndConnectionId(int bankId, long connectionId);

    /** Admin dashboard: runs of one bank that ended in {@code status} at or after {@code since}. */
    long countByBankIdAndStatusAndFinishedAtGreaterThanEqual(int bankId, RunStatus status,
                                                              Instant since);

    /** Banks with an unfinished run (the timeout sweep's cross-bank scan, as SYSTEM). */
    @Query("SELECT DISTINCT r.bankId FROM IntegrationRun r WHERE r.status IN :statuses")
    List<Integer> findBankIdsWithRunsIn(@Param("statuses") Collection<RunStatus> statuses);
}
