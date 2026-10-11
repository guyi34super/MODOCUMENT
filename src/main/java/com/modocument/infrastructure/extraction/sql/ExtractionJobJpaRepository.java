package com.modocument.infrastructure.extraction.sql;

import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExtractionJobJpaRepository extends JpaRepository<ExtractionJobEntity, Long> {

    Optional<ExtractionJobEntity> findByIdAndOwnerUserId(Long id, int ownerUserId);

    List<ExtractionJobEntity> findByOwnerUserIdOrderByIdDesc(int ownerUserId);

    List<ExtractionJobEntity> findAllByOrderByIdDesc();

    List<ExtractionJobEntity> findByStatusIn(Collection<ExtractionJobStatus> statuses);

    /** Admin dashboard (phase 2): rows of [ExtractionJobStatus, count (Long)] of one bank. */
    @Query("select j.status, count(j) from ExtractionJobEntity j"
            + " where j.bankId = :bankId and j.createdDate >= :since group by j.status")
    List<Object[]> countByStatusSince(@Param("bankId") int bankId,
                                      @Param("since") Instant since);

    /** ws28 (dashboard): rows of [ExtractionJobStatus, count (Long)] of one owner's jobs. */
    @Query("select j.status, count(j) from ExtractionJobEntity j"
            + " where j.ownerUserId = :ownerUserId group by j.status")
    List<Object[]> countByOwnerGroupedByStatus(@Param("ownerUserId") int ownerUserId);

    /** ws28 (dashboard): the owner's newest jobs. */
    List<ExtractionJobEntity> findByOwnerUserIdOrderByIdDesc(int ownerUserId,
                                                             org.springframework.data.domain.Pageable pageable);
}
