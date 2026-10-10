package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.model.integration.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** ws25 outbox (V250); every query names the bank (RLS + H2 parity). */
public interface OutboundDeliveryJpaRepository extends JpaRepository<OutboundDelivery, Long> {

    Optional<OutboundDelivery> findByIdAndBankId(long id, int bankId);

    List<OutboundDelivery> findByBankIdAndRunIdOrderByIdAsc(int bankId, Long runId);

    List<OutboundDelivery> findByBankIdAndRunIdAndStatusOrderByIdAsc(int bankId, Long runId,
                                                                     DeliveryStatus status);

    List<OutboundDelivery> findByBankIdAndFlowId(int bankId, long flowId);

    long countByBankIdAndRunIdAndStatus(int bankId, Long runId, DeliveryStatus status);

    @Modifying
    @Query("DELETE FROM OutboundDelivery d WHERE d.bankId = :bankId AND d.flowId = :flowId")
    int deleteByFlow(@Param("bankId") int bankId, @Param("flowId") long flowId);

    /** Banks with a delivery still owed (the dispatcher's cross-bank scan, as SYSTEM). */
    @Query("SELECT DISTINCT d.bankId FROM OutboundDelivery d WHERE d.status = "
            + "com.modocument.domain.model.integration.DeliveryStatus.PENDING "
            + "AND d.nextAttemptAt IS NOT NULL")
    List<Integer> findBankIdsWithPending();
}
