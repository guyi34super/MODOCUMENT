package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocInstanceJpaRepository extends JpaRepository<DocInstance, Integer> {

    Optional<DocInstance> findByDocInstanceIdAndOwnerUserId(int id, int ownerUserId);

    /**
     * ws33 (field-validation-contract §4.1): the owner's document with its row
     * locked ({@code SELECT ... FOR UPDATE}) until the transaction ends, so a
     * validate and a content change cannot interleave.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DocInstance d where d.docInstanceId = :id and d.ownerUserId = :ownerUserId")
    Optional<DocInstance> findForUpdate(@Param("id") int id, @Param("ownerUserId") int ownerUserId);

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

    /** ws31 DSR scan: one bank's documents that hold values. RLS backs this on PostgreSQL. */
    List<DocInstance> findByBankIdAndValuesJsonIsNotNull(int bankId);

    /** ws31 legacy purge: every instance (any owner) of one blueprint in one bank. */
    List<DocInstance> findByBankIdAndBlueprintIdOrderByDocInstanceIdAsc(int bankId, int blueprintId);

    /** Retention sweep (ws14): instances idle past the cutoff, content not yet purged. */
    List<DocInstance> findByBankIdAndUpdatedDateBeforeAndContentPurgedAtIsNull(
            int bankId, java.time.LocalDateTime cutoff);

    /** ws28: rows of [BlueprintId, count (Long)]. */
    @org.springframework.data.jpa.repository.Query("select d.blueprintId, count(d) from DocInstance d"
            + " where d.blueprintId in :blueprintIds group by d.blueprintId")
    List<Object[]> countByBlueprintIds(
            @org.springframework.data.repository.query.Param("blueprintIds") java.util.Collection<Integer> blueprintIds);

    /** V291: rows of [BlueprintVersion, count (Long)] for one blueprint. */
    @org.springframework.data.jpa.repository.Query("select d.blueprintVersion, count(d) from DocInstance d"
            + " where d.blueprintId = :blueprintId group by d.blueprintVersion")
    List<Object[]> countByBlueprintVersion(
            @org.springframework.data.repository.query.Param("blueprintId") int blueprintId);
}
