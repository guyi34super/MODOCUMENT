package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ImportDetails;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ws28: ImportDetails' {@code importHistory} and {@code processStep}
 * associations are LAZY. The API-facing finders fetch them (and the
 * import's template) in the same statement; internal scans (DSR, retention)
 * read only the detail row.
 */
public interface ImportDetailsJpaRepository extends JpaRepository<ImportDetails, Integer> {

    /** DSR scan (ws14): rows of one bank that still carry a payload. */
    List<ImportDetails> findByBankIdAndRawJsonIsNotNull(int bankId);

    @Override
    @EntityGraph(attributePaths = {"importHistory", "importHistory.template", "processStep"})
    Optional<ImportDetails> findById(Integer id);

    /**
     * ws28 ({@code /ImportDetails/GetAll}): the details of the imports ONE
     * owner holds in their bank, with the parent import, its template and the
     * process step in the same statement — instead of loading every detail
     * of the bank (each dragging its parent eagerly) and filtering in memory.
     */
    @Query("select d from ImportDetails d"
            + " join fetch d.importHistory h"
            + " left join fetch h.template"
            + " left join fetch d.processStep"
            + " where d.bankId = :bankId and h.ownerUserId = :ownerUserId"
            + " order by d.importDetailsId")
    List<ImportDetails> findByBankAndImportOwner(@Param("bankId") int bankId,
                                                 @Param("ownerUserId") int ownerUserId);

    /**
     * Retention sweep (ws14): nulls the RawJson payload of every detail row
     * whose owning import is older than the cutoff. ImportDetails carries no
     * timestamp of its own, so age is the parent ImportHistory's ImportDate.
     * The row itself (step, ids) is kept as the empty report structure.
     */
    @Modifying
    @Query("update ImportDetails d set d.rawJson = null"
            + " where d.bankId = :bankId and d.rawJson is not null"
            + " and d.importHistoryId in (select h.importHistoryId from ImportHistory h"
            + " where h.bankId = :bankId and h.importDate < :cutoff)")
    int purgeRawJsonOlderThan(@Param("bankId") int bankId,
                              @Param("cutoff") LocalDateTime cutoff);
}
