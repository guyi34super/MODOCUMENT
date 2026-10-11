package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
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

    /**
     * ws22: jobs of OTHER users (not {@code ownerUserId}) on a template that
     * are still in one of {@code statuses} — a bank-shared template in active
     * use by colleagues is not deleted or made private under them.
     */
    long countByTemplateIdAndOwnerUserIdNotAndStatusIn(int templateId, int ownerUserId,
                                                       Collection<JobStatus> statuses);

    /** DSR scan (ws14): every job of one bank. RLS backs this on PostgreSQL. */
    List<IngestionJob> findByBankId(int bankId);

    /** Retention sweep (ws14): jobs past the cutoff not yet purged. */
    List<IngestionJob> findByBankIdAndUpdatedDateBeforeAndPurgedAtIsNull(
            int bankId, java.time.LocalDateTime cutoff);

    /** Shared-upload guard for job deletion: other jobs on the same stored file. */
    long countByFileNameAndOwnerUserId(String fileName, int ownerUserId);

    /**
     * Admin dashboard (phase 2, ws22): rows of [JobStatus, count (Long)] of
     * every job of ONE bank created at or after {@code since} — bank-wide,
     * not owner-scoped, and counts only. RLS backs the filter on PostgreSQL.
     */
    @Query("select j.status, count(j) from IngestionJob j"
            + " where j.bankId = :bankId and j.createdDate >= :since group by j.status")
    List<Object[]> countByStatusSince(@Param("bankId") int bankId,
                                      @Param("since") java.time.LocalDateTime since);

    /**
     * Admin dashboard (ws23 parse provenance): rows of [ParsedBy (String),
     * count (Long)] of ONE bank's jobs created at or after {@code since}
     * that were parsed (ParsedBy set).
     */
    @Query("select j.parsedBy, count(j) from IngestionJob j"
            + " where j.bankId = :bankId and j.createdDate >= :since"
            + " and j.parsedBy is not null group by j.parsedBy")
    List<Object[]> countByParsedBySince(@Param("bankId") int bankId,
                                        @Param("since") java.time.LocalDateTime since);
}
