package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionProcessedRecordEntity;
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
 * SQL-backed normalized records. Per-record dedup rides on the
 * UNIQUE(JobId, DedupKey) constraint; saveAllIfNew reports how many rows were
 * actually inserted, matching the Mongo adapter's contract.
 *
 * <p>ws28: a batch is written in ONE transaction (it used to be one per
 * record). The keys already stored are read with one query and duplicates
 * inside the batch are skipped in memory, so the inserts never trip the
 * constraint in the normal case; when a concurrent worker stores one of the
 * same keys in between, the batch transaction rolls back and the batch is
 * replayed record by record — the old path — so the dedup semantics are
 * exactly what they were.
 */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlProcessedRecordStore implements ProcessedRecordStore {

    private static final char KEY_SEPARATOR = '\u0000';

    private final ExtractionProcessedRecordJpaRepository repository;
    private final TransactionTemplate tx;

    public SqlProcessedRecordStore(ExtractionProcessedRecordJpaRepository repository,
                                   PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public int saveAllIfNew(List<ProcessedRecord> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        Map<ProcessedRecord, Long> stored;
        try {
            stored = tx.execute(status -> insertNew(records));
        } catch (DataIntegrityViolationException race) {
            // A concurrent writer stored one of these keys after the check;
            // the whole batch rolled back, so replay it one record at a time.
            return saveOneByOne(records);
        }
        if (stored == null) {
            return 0;
        }
        // Ids only after the commit: a rolled-back batch leaves none behind.
        stored.forEach((record, id) -> record.setId(String.valueOf(id)));
        return stored.size();
    }

    /** One transaction: the new (not yet stored, not repeated) records, inserted. */
    private Map<ProcessedRecord, Long> insertNew(List<ProcessedRecord> records) {
        Set<String> seen = existingKeys(records);
        List<ProcessedRecord> fresh = new ArrayList<>(records.size());
        List<ExtractionProcessedRecordEntity> entities = new ArrayList<>(records.size());
        for (ProcessedRecord record : records) {
            if (record.getDedupKey() != null && !seen.add(compositeKey(record))) {
                continue; // same (jobId, dedupKey) stored already or earlier in this batch
            }
            fresh.add(record);
            entities.add(toEntity(record));
        }
        List<ExtractionProcessedRecordEntity> saved = repository.saveAll(entities);
        repository.flush();
        Map<ProcessedRecord, Long> ids = new LinkedHashMap<>();
        for (int i = 0; i < fresh.size(); i++) {
            ids.put(fresh.get(i), saved.get(i).getId());
        }
        return ids;
    }

    /** The (jobId, dedupKey) pairs of this batch that are already stored. */
    private Set<String> existingKeys(List<ProcessedRecord> records) {
        Map<String, Set<String>> keysByJob = new LinkedHashMap<>();
        for (ProcessedRecord record : records) {
            if (record.getDedupKey() != null) {
                keysByJob.computeIfAbsent(record.getJobId(), job -> new LinkedHashSet<>())
                        .add(record.getDedupKey());
            }
        }
        Set<String> existing = new HashSet<>();
        keysByJob.forEach((jobId, keys) -> {
            for (String key : repository.findExistingDedupKeys(jobId, keys)) {
                existing.add(jobId + KEY_SEPARATOR + key);
            }
        });
        return existing;
    }

    private static String compositeKey(ProcessedRecord record) {
        return record.getJobId() + KEY_SEPARATOR + record.getDedupKey();
    }

    /** The pre-ws28 path: one insert per record, a constraint violation = duplicate. */
    private int saveOneByOne(List<ProcessedRecord> records) {
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
        entity.setBankId(BankStamp.current());
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
