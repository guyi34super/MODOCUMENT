package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.RawRecordStore;
import com.modocument.domain.document.RawRecord;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionRawRecordEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQL-backed raw payloads. Dedup rides on the UNIQUE(SourceId, ContentHash)
 * constraint: an insert that violates it means the exact same content was
 * already stored for this source.
 */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlRawRecordStore implements RawRecordStore {

    private final ExtractionRawRecordJpaRepository repository;

    public SqlRawRecordStore(ExtractionRawRecordJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean saveIfNew(RawRecord record) {
        try {
            ExtractionRawRecordEntity saved = repository.saveAndFlush(toEntity(record));
            record.setId(String.valueOf(saved.getId()));
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }

    @Override
    public List<RawRecord> findBatch(String jobId, String afterId, int limit) {
        Long after = JsonColumns.parseId(afterId);
        return repository.findBatch(jobId, after == null ? 0L : after, PageRequest.of(0, limit))
                .stream().map(SqlRawRecordStore::toDomain).toList();
    }

    @Override
    public long countByJob(String jobId) {
        return repository.countByJobId(jobId);
    }

    private static ExtractionRawRecordEntity toEntity(RawRecord record) {
        ExtractionRawRecordEntity entity = new ExtractionRawRecordEntity();
        entity.setId(JsonColumns.parseId(record.getId()));
        entity.setJobId(record.getJobId());
        entity.setSourceId(record.getSourceId());
        entity.setUri(record.getUri());
        entity.setContentType(record.getContentType());
        entity.setBody(record.getBody());
        entity.setTruncated(record.isTruncated());
        entity.setContentHash(record.getContentHash());
        entity.setHttpStatus(record.getHttpStatus());
        entity.setEtag(record.getEtag());
        entity.setLastModified(record.getLastModified());
        entity.setFetchedAt(record.getFetchedAt());
        return entity;
    }

    private static RawRecord toDomain(ExtractionRawRecordEntity entity) {
        RawRecord record = new RawRecord();
        record.setId(String.valueOf(entity.getId()));
        record.setJobId(entity.getJobId());
        record.setSourceId(entity.getSourceId());
        record.setUri(entity.getUri());
        record.setContentType(entity.getContentType());
        record.setBody(entity.getBody());
        record.setTruncated(entity.isTruncated());
        record.setContentHash(entity.getContentHash());
        record.setHttpStatus(entity.getHttpStatus());
        record.setEtag(entity.getEtag());
        record.setLastModified(entity.getLastModified());
        record.setFetchedAt(entity.getFetchedAt());
        return record;
    }
}
