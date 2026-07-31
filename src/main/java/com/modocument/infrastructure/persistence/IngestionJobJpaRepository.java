package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStep;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface IngestionJobJpaRepository extends JpaRepository<IngestionJob, Integer> {

    /**
     * Projection on purpose: a full-entity fetch would drag every job's large
     * *Json checkpoint blobs along just to read the mapping.
     */
    @Query("select j.mappingJson from IngestionJob j"
            + " where j.templateId = :templateId and j.ownerUserId = :ownerUserId"
            + " and j.currentStep in :steps"
            + " and j.mappingJson is not null order by j.updatedDate desc")
    List<String> findRecentMappingJson(@Param("templateId") int templateId,
                                       @Param("ownerUserId") int ownerUserId,
                                       @Param("steps") Collection<JobStep> steps,
                                       Pageable pageable);

    java.util.Optional<IngestionJob> findByIngestionJobIdAndOwnerUserId(int id, int ownerUserId);

    List<IngestionJob> findByOwnerUserId(int ownerUserId);

    long countByTemplateId(int templateId);
}
