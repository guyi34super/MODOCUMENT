package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.PendingChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingChangeJpaRepository extends JpaRepository<PendingChange, Integer> {

    List<PendingChange> findByBankIdOrderByRequestedAtDescPendingChangeIdDesc(int bankId);

    List<PendingChange> findByBankIdAndStatusOrderByRequestedAtDescPendingChangeIdDesc(
            int bankId, PendingChangeStatus status);

    /**
     * ws11 nightly SoD scan: APPLIED changes approved by their own maker —
     * the B7 invariant says there are none.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT c FROM PendingChange c
            WHERE c.bankId = :bankId AND c.status = com.modocument.domain.model.PendingChangeStatus.APPLIED
              AND c.checkerUserId = c.makerUserId
            ORDER BY c.pendingChangeId""")
    List<PendingChange> findAppliedSodViolations(
            @org.springframework.data.repository.query.Param("bankId") int bankId);
}
