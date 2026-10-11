package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocInstanceJpaRepository extends JpaRepository<DocInstance, Integer> {

    Optional<DocInstance> findByDocInstanceIdAndOwnerUserId(int id, int ownerUserId);

    /** ws11 release workflow: bank-scoped (not owner-scoped) lookup for approvers. */
    Optional<DocInstance> findByDocInstanceIdAndBankId(int id, int bankId);

    /** ws11: the per-bank release queue / released listing. */
    List<DocInstance> findByBankIdAndStatusOrderByDocInstanceIdDesc(int bankId, String status);

    /** Admin dashboard (phase 2): the size of the bank's release queue. */
    long countByBankIdAndStatus(int bankId, String status);

    /**
     * ws11 nightly SoD scan: RELEASED instances released by their own author
     * or submitter — the invariant says there are none.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT d FROM DocInstance d
            WHERE d.bankId = :bankId AND d.status = 'RELEASED'
              AND (d.releasedByUserId = d.ownerUserId
                   OR d.releasedByUserId = d.submittedByUserId)
            ORDER BY d.docInstanceId""")
    List<DocInstance> findReleasedSodViolations(
            @org.springframework.data.repository.query.Param("bankId") int bankId);

    List<DocInstance> findByOwnerUserIdOrderByDocInstanceIdDesc(int ownerUserId);

    List<DocInstance> findByOwnerUserIdAndBlueprintIdOrderByDocInstanceIdDesc(int ownerUserId, int blueprintId);

    int countByBlueprintId(int blueprintId);

    /** Retention sweep (ws14): instances idle past the cutoff, content not yet purged. */
    List<DocInstance> findByBankIdAndUpdatedDateBeforeAndContentPurgedAtIsNull(
            int bankId, java.time.LocalDateTime cutoff);
}
