package com.modocument.infrastructure.extraction.sql;

import com.modocument.infrastructure.extraction.sql.entity.ExtractionProcessedRecordEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExtractionProcessedRecordJpaRepository
        extends JpaRepository<ExtractionProcessedRecordEntity, Long> {

    @Query("select r from ExtractionProcessedRecordEntity r "
            + "where r.jobId = :jobId and r.id > :afterId order by r.id")
    List<ExtractionProcessedRecordEntity> findBatch(@Param("jobId") String jobId,
                                                    @Param("afterId") long afterId, Pageable page);

    long countByJobId(String jobId);

    /** DSR scan (ws14): every processed record of one bank. RLS backs this on PostgreSQL. */
    List<ExtractionProcessedRecordEntity> findByBankId(int bankId);

    /**
     * Retention sweep (ws14): nulls the extracted FieldsJson of processed
     * records older than the cutoff; the record row (job, dedup key) stays.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update ExtractionProcessedRecordEntity r set r.fieldsJson = null"
            + " where r.bankId = :bankId and r.fieldsJson is not null and r.processedAt < :cutoff")
    int purgeFieldsOlderThan(@Param("bankId") int bankId,
                             @Param("cutoff") java.time.Instant cutoff);
}
