package com.modocument.infrastructure.extraction.sql;

import com.modocument.infrastructure.extraction.sql.entity.ExtractionRawRecordEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExtractionRawRecordJpaRepository
        extends JpaRepository<ExtractionRawRecordEntity, Long> {

    @Query("select r from ExtractionRawRecordEntity r "
            + "where r.jobId = :jobId and r.id > :afterId order by r.id")
    List<ExtractionRawRecordEntity> findBatch(@Param("jobId") String jobId,
                                              @Param("afterId") long afterId, Pageable page);

    long countByJobId(String jobId);

    /** DSR scan (ws14): every raw record of one bank. RLS backs this on PostgreSQL. */
    List<ExtractionRawRecordEntity> findByBankId(int bankId);

    /**
     * Retention sweep (ws14): nulls the fetched Body of raw records older
     * than the cutoff; the fetch metadata row (uri, status, hash) stays.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update ExtractionRawRecordEntity r set r.body = null"
            + " where r.bankId = :bankId and r.body is not null and r.fetchedAt < :cutoff")
    int purgeBodiesOlderThan(@Param("bankId") int bankId,
                             @Param("cutoff") java.time.Instant cutoff);

    /** ws28: which of {@code hashes} the source already stored (the batch dedup pre-check). */
    @Query("select r.contentHash from ExtractionRawRecordEntity r"
            + " where r.sourceId = :sourceId and r.contentHash in :hashes")
    List<String> findExistingHashes(@Param("sourceId") String sourceId,
                                    @Param("hashes") java.util.Collection<String> hashes);
}
