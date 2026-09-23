package com.modocument.infrastructure.extraction.sql;

import com.modocument.infrastructure.extraction.sql.entity.ExtractionAuditEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExtractionAuditEventJpaRepository
        extends JpaRepository<ExtractionAuditEventEntity, Long> {

    @Query("select e from ExtractionAuditEventEntity e "
            + "where e.jobId = :jobId and e.id > :afterId order by e.id")
    List<ExtractionAuditEventEntity> findBatch(@Param("jobId") String jobId,
                                               @Param("afterId") long afterId, Pageable page);
}
