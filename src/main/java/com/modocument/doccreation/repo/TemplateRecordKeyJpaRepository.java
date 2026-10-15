package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.TemplateRecordKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface TemplateRecordKeyJpaRepository
        extends JpaRepository<TemplateRecordKey, TemplateRecordKey.Pk> {

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from TemplateRecordKey k where k.bankId = :bankId and k.templateRecordId in"
            + " (select r.templateRecordId from TemplateRecord r where r.bankId = :bankId"
            + " and r.ingestionJobId = :jobId)")
    int deleteJob(@Param("bankId") int bankId, @Param("jobId") int jobId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from TemplateRecordKey k where k.bankId = :bankId and k.templateRecordId in"
            + " (select r.templateRecordId from TemplateRecord r where r.bankId = :bankId"
            + " and r.ingestionJobId in :jobIds)")
    int deleteJobs(@Param("bankId") int bankId, @Param("jobIds") Collection<Integer> jobIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from TemplateRecordKey k where k.bankId = :bankId and k.templateRecordId in :ids")
    int deleteRecords(@Param("bankId") int bankId, @Param("ids") Collection<Long> ids);
}
