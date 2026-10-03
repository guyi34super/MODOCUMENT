package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.ExtractionJobStore;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionJobCounterEntity;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionJobEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SQL-backed extraction jobs. The job row never carries counters — they live
 * in ExtractionJobCounter and are only ever changed through {@link #increment},
 * so a concurrent save cannot clobber a worker's progress.
 */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlExtractionJobStore implements ExtractionJobStore {

    private final ExtractionJobJpaRepository jobs;
    private final ExtractionJobCounterJpaRepository counters;

    public SqlExtractionJobStore(ExtractionJobJpaRepository jobs,
                                 ExtractionJobCounterJpaRepository counters) {
        this.jobs = jobs;
        this.counters = counters;
    }

    @Override
    public ExtractionJob save(ExtractionJob job) {
        if (job.getId() == null) {
            job.setCreatedDate(Instant.now());
        }
        job.setUpdatedDate(Instant.now());
        ExtractionJobEntity saved = jobs.save(toEntity(job));
        job.setId(String.valueOf(saved.getId()));
        return job;
    }

    @Override
    public Optional<ExtractionJob> getById(String id) {
        Long entityId = JsonColumns.parseId(id);
        if (entityId == null) {
            return Optional.empty();
        }
        return jobs.findById(entityId).map(this::toDomain);
    }

    @Override
    public Optional<ExtractionJob> getByIdAndOwner(String id, int ownerUserId) {
        Long entityId = JsonColumns.parseId(id);
        if (entityId == null) {
            return Optional.empty();
        }
        return jobs.findByIdAndOwnerUserId(entityId, ownerUserId).map(this::toDomain);
    }

    @Override
    public List<ExtractionJob> list() {
        return toDomainList(jobs.findAllByOrderByIdDesc());
    }

    @Override
    public List<ExtractionJob> listByOwner(int ownerUserId) {
        return toDomainList(jobs.findByOwnerUserIdOrderByIdDesc(ownerUserId));
    }

    @Override
    public void increment(String jobId, String counter, long delta) {
        // Deliberately NOT one wrapping transaction: each repository call
        // commits on its own, so the insert-race retry below can see the
        // racing winner's committed row instead of a rollback-only session.
        Long entityId = JsonColumns.parseId(jobId);
        if (entityId == null) {
            return;
        }
        if (counters.increment(entityId, counter, delta) > 0) {
            return;
        }
        // First increment of this counter: the row inherits the job's bank
        // (one extra read, only ever on this rare insert path).
        int bankId = jobs.findById(entityId)
                .map(ExtractionJobEntity::getBankId)
                .filter(bank -> bank > 0)
                .orElseGet(BankStamp::current);
        try {
            counters.saveAndFlush(new ExtractionJobCounterEntity(entityId, counter, delta, bankId));
        } catch (DataIntegrityViolationException raced) {
            // Another worker inserted the row between our UPDATE and INSERT.
            counters.increment(entityId, counter, delta);
        }
    }

    private List<ExtractionJob> toDomainList(List<ExtractionJobEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<String, Long>> countersByJob = counters
                .findByJobIdIn(entities.stream().map(ExtractionJobEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ExtractionJobCounterEntity::getJobId,
                        Collectors.toMap(ExtractionJobCounterEntity::getName,
                                ExtractionJobCounterEntity::getValue)));
        return entities.stream()
                .map(entity -> toDomain(entity,
                        countersByJob.getOrDefault(entity.getId(), Map.of())))
                .toList();
    }

    private ExtractionJob toDomain(ExtractionJobEntity entity) {
        Map<String, Long> counterValues = counters.findByJobId(entity.getId()).stream()
                .collect(Collectors.toMap(ExtractionJobCounterEntity::getName,
                        ExtractionJobCounterEntity::getValue));
        return toDomain(entity, counterValues);
    }

    private static ExtractionJob toDomain(ExtractionJobEntity entity,
                                          Map<String, Long> counterValues) {
        ExtractionJob job = new ExtractionJob();
        job.setId(String.valueOf(entity.getId()));
        job.setOwnerUserId(entity.getOwnerUserId());
        job.setBankId(entity.getBankId());
        job.setSourceId(entity.getSourceId());
        job.setTemplateId(entity.getTemplateId());
        job.setDataSpec(entity.getDataSpec());
        job.setStatus(entity.getStatus());
        job.setIncremental(entity.isIncremental());
        job.setCounters(new ConcurrentHashMap<>(counterValues));
        job.setErrorSummary(entity.getErrorSummary());
        job.setCreatedDate(entity.getCreatedDate());
        job.setUpdatedDate(entity.getUpdatedDate());
        return job;
    }

    private static ExtractionJobEntity toEntity(ExtractionJob job) {
        ExtractionJobEntity entity = new ExtractionJobEntity();
        entity.setId(JsonColumns.parseId(job.getId()));
        entity.setOwnerUserId(job.getOwnerUserId());
        entity.setBankId(BankStamp.effective(job.getBankId()));
        entity.setSourceId(job.getSourceId());
        entity.setTemplateId(job.getTemplateId());
        entity.setDataSpec(job.getDataSpec());
        entity.setStatus(job.getStatus());
        entity.setIncremental(job.isIncremental());
        entity.setErrorSummary(job.getErrorSummary());
        entity.setCreatedDate(job.getCreatedDate());
        entity.setUpdatedDate(job.getUpdatedDate());
        return entity;
    }
}
