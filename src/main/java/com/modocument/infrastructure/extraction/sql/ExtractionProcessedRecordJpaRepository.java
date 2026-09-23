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
}
