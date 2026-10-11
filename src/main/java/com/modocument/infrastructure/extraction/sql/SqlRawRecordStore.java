package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.RawRecordStore;
import com.modocument.domain.document.RawRecord;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionRawRecordEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL-backed raw payloads. Dedup rides on the UNIQUE(SourceId, ContentHash)
 * constraint: an insert that violates it means the exact same content was
 * already stored for this source.
 */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlRawRecordStore implements RawRecordStore {

    private static final char KEY_SEPARATOR = '\u0000';

    private final ExtractionRawRecordJpaRepository repository;
    private final TransactionTemplate tx;

    public SqlRawRecordStore(ExtractionRawRecordJpaRepository repository,
                             PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
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

    /**
     * ws28: a whole fetch result in ONE transaction (instead of one per
     * record). The (sourceId, contentHash) pairs already stored are read with
     * one query per source and repeats inside the list are skipped in memory;
     * when a concurrent worker stores one of them in between, the batch rolls
     * back and is replayed through {@link #saveIfNew} one record at a time —
     * the dedup semantics stay exactly those of the constraint.
     */
    @Override
    public boolean[] saveAllIfNew(List<RawRecord> records) {
        boolean[] stored = new boolean[records == null ? 0 : records.size()];
        if (stored.length == 0) {
            return stored;
        }
        Map<Integer, Long> ids;
        try {
            ids = tx.execute(status -> insertNew(records));
        } catch (DataIntegrityViolationException race) {
            for (int i = 0; i < records.size(); i++) {
                stored[i] = saveIfNew(records.get(i));
            }
            return stored;
        }
        if (ids != null) {
            ids.forEach((index, id) -> {
                records.get(index).setId(String.valueOf(id));
                stored[index] = true;
            });
        }
        return stored;
    }

    /** One transaction: inserts the records not stored yet; index → new id. */
    private Map<Integer, Long> insertNew(List<RawRecord> records) {
        Map<String, Set<String>> hashesBySource = new LinkedHashMap<>();
        for (RawRecord record : records) {
            if (record.getContentHash() != null) {
                hashesBySource.computeIfAbsent(record.getSourceId(), source -> new LinkedHashSet<>())
                        .add(record.getContentHash());
            }
        }
        Set<String> seen = new HashSet<>();
        hashesBySource.forEach((sourceId, hashes) -> {
            for (String hash : repository.findExistingHashes(sourceId, hashes)) {
                seen.add(sourceId + KEY_SEPARATOR + hash);
            }
        });
        List<Integer> indexes = new ArrayList<>();
        List<ExtractionRawRecordEntity> entities = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            RawRecord record = records.get(i);
            if (record.getContentHash() != null
                    && !seen.add(record.getSourceId() + KEY_SEPARATOR + record.getContentHash())) {
                continue;
            }
            indexes.add(i);
            entities.add(toEntity(record));
        }
        List<ExtractionRawRecordEntity> saved = repository.saveAll(entities);
        repository.flush();
        Map<Integer, Long> ids = new LinkedHashMap<>();
        for (int i = 0; i < indexes.size(); i++) {
            ids.put(indexes.get(i), saved.get(i).getId());
        }
        return ids;
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
        entity.setBankId(BankStamp.current());
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
