package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ImportDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ImportDetailsJpaRepository extends JpaRepository<ImportDetails, Integer> {

    /** DSR scan (ws14): rows of one bank that still carry a payload. */
    List<ImportDetails> findByBankIdAndRawJsonIsNotNull(int bankId);

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
