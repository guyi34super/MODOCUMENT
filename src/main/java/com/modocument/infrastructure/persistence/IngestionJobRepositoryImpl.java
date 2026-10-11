package com.modocument.infrastructure.persistence;

import com.modocument.application.port.IngestionJobRepository;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.modocument.application.dto.IngestionJobSummaryRow;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class IngestionJobRepositoryImpl implements IngestionJobRepository {

    /** Not terminal (COMPLETED / FAILED / CANCELLED): the job may still run or be resumed. */
    private static final List<JobStatus> ACTIVE_STATUSES =
            List.of(JobStatus.PENDING, JobStatus.RUNNING);

    private final IngestionJobJpaRepository jpa;

    public IngestionJobRepositoryImpl(IngestionJobJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<IngestionJob> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public Optional<IngestionJob> getByIdAndOwner(int id, int ownerUserId) {
        return jpa.findByIngestionJobIdAndOwnerUserId(id, ownerUserId);
    }

    @Override
    public List<IngestionJob> list() {
        return jpa.findAll();
    }

    @Override
    public List<IngestionJob> listByOwner(int ownerUserId) {
        return jpa.findByOwnerUserId(ownerUserId);
    }

    @Override
    public IngestionJob save(IngestionJob job) {
        if (job.getCreatedDate() == null) {
            job.setCreatedDate(LocalDateTime.now());
        }
        job.setUpdatedDate(LocalDateTime.now());
        return jpa.save(job);
    }

    @Override
    public int countByTemplateId(int templateId) {
        return (int) jpa.countByTemplateId(templateId);
    }

    @Override
    public int countActiveByTemplateIdOfOtherUsers(int templateId, int ownerUserId) {
        return (int) jpa.countByTemplateIdAndOwnerUserIdNotAndStatusIn(templateId, ownerUserId,
                ACTIVE_STATUSES);
    }

    @Override
    public void delete(IngestionJob job) {
        jpa.delete(job);
    }

    @Override
    public int countByFileNameAndOwner(String fileName, int ownerUserId) {
        return (int) jpa.countByFileNameAndOwnerUserId(fileName, ownerUserId);
    }

    @Override
    public List<String> listRecentMappingJson(int templateId, int ownerUserId, int limit) {
        return jpa.findRecentMappingJson(templateId, ownerUserId,
                List.of(JobStep.VALIDATED, JobStep.EXPORTED), PageRequest.of(0, limit));
    }

    @Override
    public List<IngestionJobSummaryRow> listSummariesByOwner(int ownerUserId, int page, int size) {
        return jpa.findSummariesByOwner(ownerUserId, PageRequest.of(page, size));
    }

    @Override
    public long countByOwner(int ownerUserId) {
        return jpa.countByOwnerUserId(ownerUserId);
    }

    @Override
    public Map<JobStatus, Long> countByOwnerGroupedByStatus(int ownerUserId) {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (Object[] row : jpa.countByOwnerGroupedByStatus(ownerUserId)) {
            if (row[0] instanceof JobStatus status) {
                counts.put(status, ((Number) row[1]).longValue());
            }
        }
        return counts;
    }

    @Override
    public List<IngestionJob> listNewestByOwner(int ownerUserId, int limit) {
        return jpa.findNewestByOwner(ownerUserId, PageRequest.of(0, limit));
    }
}
