package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionProcessedRecordEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * SQL-backed normalized records. Per-record dedup rides on the
 * UNIQUE(JobId, DedupKey) constraint; saveAllIfNew reports how many rows were
 * actually inserted, matching the Mongo adapter's contract.
 */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlProcessedRecordStore implements ProcessedRecordStore {

    private final ExtractionProcessedRecordJpaRepository repository;

    public SqlProcessedRecordStore(ExtractionProcessedRecordJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public int saveAllIfNew(List<ProcessedRecord> records) {
        int stored = 0;
        for (ProcessedRecord record : records) {
            try {
                ExtractionProcessedRecordEntity saved = repository.saveAndFlush(toEntity(record));
                record.setId(String.valueOf(saved.getId()));
                stored++;
            } catch (DataIntegrityViolationException duplicate) {
                // Same (jobId, dedupKey) already stored — skip, like Mongo.
            }
        }
        return stored;
    }

    @Override
    public List<ProcessedRecord> findBatch(String jobId, String afterId, int limit) {
        Long after = JsonColumns.parseId(afterId);
        return repository.findBatch(jobId, after == null ? 0L : after, PageRequest.of(0, limit))
                .stream().map(SqlProcessedRecordStore::toDomain).toList();
    }

    @Override
    public long countByJob(String jobId) {
        return repository.countByJobId(jobId);
    }

    private static ExtractionProcessedRecordEntity toEntity(ProcessedRecord record) {
        ExtractionProcessedRecordEntity entity = new ExtractionProcessedRecordEntity();
        entity.setId(JsonColumns.parseId(record.getId()));
        entity.setJobId(record.getJobId());
        entity.setRawRecordId(record.getRawRecordId());
        entity.setFieldsJson(JsonColumns.toJson(record.getFields()));
        entity.setDedupKey(record.getDedupKey());
        entity.setProcessedAt(record.getProcessedAt());
        return entity;
    }

    private static ProcessedRecord toDomain(ExtractionProcessedRecordEntity entity) {
        ProcessedRecord record = new ProcessedRecord();
        record.setId(String.valueOf(entity.getId()));
        record.setJobId(entity.getJobId());
        record.setRawRecordId(entity.getRawRecordId());
        record.setFields(new LinkedHashMap<>(JsonColumns.fromJsonMap(entity.getFieldsJson())));
        record.setDedupKey(entity.getDedupKey());
        record.setProcessedAt(entity.getProcessedAt());
        return record;
    }
}
