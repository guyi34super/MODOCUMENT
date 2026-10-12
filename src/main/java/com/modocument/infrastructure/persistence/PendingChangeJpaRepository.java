package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.PendingChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingChangeJpaRepository extends JpaRepository<PendingChange, Integer> {

    List<PendingChange> findByBankIdOrderByRequestedAtDescPendingChangeIdDesc(int bankId);

    List<PendingChange> findByBankIdAndStatusOrderByRequestedAtDescPendingChangeIdDesc(
            int bankId, PendingChangeStatus status);

    /** Admin dashboard (phase 2): the bank's queue size in one status. */
    long countByBankIdAndStatus(int bankId, PendingChangeStatus status);

    /** Admin dashboard (phase 2): the queue size of one change type. */
    long countByBankIdAndStatusAndChangeType(int bankId, PendingChangeStatus status,
                                             com.modocument.domain.model.ChangeType changeType);

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

    // ---- ws28: the paged queue (SE1 adds the (BankId, RequestedAt) index) ----

    /** The caller's own changes plus those of a decidable type, any status, newest first. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT c FROM PendingChange c
            WHERE c.bankId = :bankId
              AND (c.makerUserId = :makerUserId OR c.changeType IN :types)
            ORDER BY c.requestedAt DESC, c.pendingChangeId DESC""")
    org.springframework.data.domain.Page<PendingChange> findVisible(
            @org.springframework.data.repository.query.Param("bankId") int bankId,
            @org.springframework.data.repository.query.Param("makerUserId") int makerUserId,
            @org.springframework.data.repository.query.Param("types")
            java.util.Collection<com.modocument.domain.model.ChangeType> types,
            org.springframework.data.domain.Pageable pageable);

    /** As {@link #findVisible}, in one status. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT c FROM PendingChange c
            WHERE c.bankId = :bankId AND c.status = :status
              AND (c.makerUserId = :makerUserId OR c.changeType IN :types)
            ORDER BY c.requestedAt DESC, c.pendingChangeId DESC""")
    org.springframework.data.domain.Page<PendingChange> findVisibleInStatus(
            @org.springframework.data.repository.query.Param("bankId") int bankId,
            @org.springframework.data.repository.query.Param("status") PendingChangeStatus status,
            @org.springframework.data.repository.query.Param("makerUserId") int makerUserId,
            @org.springframework.data.repository.query.Param("types")
            java.util.Collection<com.modocument.domain.model.ChangeType> types,
            org.springframework.data.domain.Pageable pageable);

    /** Only the caller's own changes (no decidable type), any status. */
    org.springframework.data.domain.Page<PendingChange> findByBankIdAndMakerUserIdOrderByRequestedAtDescPendingChangeIdDesc(
            int bankId, int makerUserId, org.springframework.data.domain.Pageable pageable);

    /** Only the caller's own changes, in one status. */
    org.springframework.data.domain.Page<PendingChange> findByBankIdAndStatusAndMakerUserIdOrderByRequestedAtDescPendingChangeIdDesc(
            int bankId, PendingChangeStatus status, int makerUserId,
            org.springframework.data.domain.Pageable pageable);
}
