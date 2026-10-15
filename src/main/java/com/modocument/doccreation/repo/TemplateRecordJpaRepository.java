package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.TemplateRecord;
import com.modocument.domain.model.JobStep;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TemplateRecordJpaRepository extends JpaRepository<TemplateRecord, Long>,
        JpaSpecificationExecutor<TemplateRecord> {

    Optional<TemplateRecord> findByTemplateRecordIdAndBankId(Long id, int bankId);

    List<TemplateRecord> findByBankId(int bankId);

    List<TemplateRecord> findByBankIdAndIngestionJobId(int bankId, int ingestionJobId);

    long countByBankIdAndIngestionJobId(int bankId, int ingestionJobId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from TemplateRecord r where r.bankId = :bankId and r.ingestionJobId = :jobId")
    int deleteJob(@Param("bankId") int bankId, @Param("jobId") int jobId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from TemplateRecord r where r.bankId = :bankId and r.ingestionJobId in :jobIds")
    int deleteJobs(@Param("bankId") int bankId, @Param("jobIds") Collection<Integer> jobIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from TemplateRecord r where r.bankId = :bankId and r.templateRecordId in :ids")
    int deleteRecords(@Param("bankId") int bankId, @Param("ids") Collection<Long> ids);

    /**
     * The records of {@code templateId} whose {@code fieldName} key equals
     * {@code keyValue}, newest first, restricted to what the caller may see
     * (own jobs, or every job when the template is shared with the bank).
     */
    @Query("select r from TemplateRecord r where r.bankId = :bankId and r.templateId = :templateId"
            + " and (r.ownerUserId = :userId or :shared = true)"
            + " and r.templateRecordId in (select k.templateRecordId from TemplateRecordKey k"
            + " where k.bankId = :bankId and k.fieldName = :fieldName and k.keyValue = :keyValue)"
            + " order by r.createdAt desc, r.templateRecordId desc")
    List<TemplateRecord> findByKey(@Param("bankId") int bankId, @Param("templateId") int templateId,
                                   @Param("userId") int userId, @Param("shared") boolean shared,
                                   @Param("fieldName") String fieldName, @Param("keyValue") String keyValue,
                                   Pageable pageable);

    /**
     * Backfill: the VALIDATED/EXPORTED jobs of one bank that still hold row
     * data and have no record yet (ids only — no checkpoint blobs).
     */
    @Query("select j.ingestionJobId from IngestionJob j where j.bankId = :bankId"
            + " and j.currentStep in :steps and j.mappedRowsJson is not null and j.purgedAt is null"
            + " and not exists (select r.templateRecordId from TemplateRecord r"
            + " where r.ingestionJobId = j.ingestionJobId)"
            + " order by j.ingestionJobId")
    List<Integer> findUnindexedJobIds(@Param("bankId") int bankId, @Param("steps") Collection<JobStep> steps);
}
