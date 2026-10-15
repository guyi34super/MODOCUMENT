package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.TemplateRecordIndex;
import com.modocument.application.usecase.ValidatedRows;
import com.modocument.doccreation.entity.TemplateRecord;
import com.modocument.doccreation.entity.TemplateRecordKey;
import com.modocument.doccreation.repo.TemplateRecordJpaRepository;
import com.modocument.doccreation.repo.TemplateRecordKeyJpaRepository;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the record index (V310, docs/doccreation/flow-records-contract.md §3)
 * in step with the ingestion jobs: a job that reaches VALIDATED or EXPORTED
 * has its VALID rows ({@link ValidatedRows}) indexed — one
 * {@link TemplateRecord} per row plus a {@link TemplateRecordKey} per
 * non-blank field — and every change to a job's rows (remap, row edit,
 * re-validation) deletes its records and inserts them again.
 *
 * <p>Indexing runs AFTER the caller's transaction commits (at once when the
 * caller has none: the ingestion pipeline commits per step), in a
 * transaction of its own, on the caller's thread and therefore under the
 * caller's tenant (RLS). A failure is logged and never reaches the pipeline;
 * the next change of the job, or the startup backfill for a job left without
 * records, indexes it again.
 */
@Service
public class TemplateRecordIndexer implements TemplateRecordIndex {

    private static final Logger log = LoggerFactory.getLogger(TemplateRecordIndexer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Records flushed and detached together while indexing a large job. */
    private static final int CHUNK = 200;

    private final IngestionJobJpaRepository jobs;
    private final TemplateRecordJpaRepository records;
    private final TemplateRecordKeyJpaRepository keys;
    private final TransactionTemplate ownTransaction;

    @PersistenceContext
    private EntityManager entityManager;

    public TemplateRecordIndexer(IngestionJobJpaRepository jobs,
                                 TemplateRecordJpaRepository records,
                                 TemplateRecordKeyJpaRepository keys,
                                 PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.records = records;
        this.keys = keys;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------ the port

    @Override
    public void jobChanged(IngestionJob job) {
        if (job == null || job.getIngestionJobId() <= 0) {
            return;
        }
        int bankId = job.getBankId();
        int jobId = job.getIngestionJobId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reindexQuietly(bankId, jobId);
                }
            });
        } else {
            reindexQuietly(bankId, jobId);
        }
    }

    @Override
    public void jobDeleted(int bankId, int jobId) {
        keys.deleteJob(bankId, jobId);
        records.deleteJob(bankId, jobId);
    }

    // ------------------------------------------------------- maintenance

    /** Retention purge: the records of the purged jobs (in the caller's transaction). */
    public int purgeJobs(int bankId, Collection<Integer> jobIds) {
        if (jobIds.isEmpty()) {
            return 0;
        }
        keys.deleteJobs(bankId, jobIds);
        return records.deleteJobs(bankId, jobIds);
    }

    /** DSR erase: the given records (in the caller's transaction). */
    public int eraseRecords(int bankId, Collection<Long> recordIds) {
        if (recordIds.isEmpty()) {
            return 0;
        }
        keys.deleteRecords(bankId, recordIds);
        return records.deleteRecords(bankId, recordIds);
    }

    /**
     * Deletes the job's records and, when it is VALIDATED/EXPORTED with row
     * data, inserts its valid rows again — in a transaction of its own.
     *
     * @return the number of records written
     */
    public int reindex(int bankId, int jobId) {
        Integer written = ownTransaction.execute(status -> {
            keys.deleteJob(bankId, jobId);
            records.deleteJob(bankId, jobId);
            IngestionJob job = jobs.findById(jobId).filter(j -> j.getBankId() == bankId).orElse(null);
            if (job == null || !indexable(job)) {
                return 0;
            }
            return insert(job);
        });
        return written == null ? 0 : written;
    }

    private void reindexQuietly(int bankId, int jobId) {
        try {
            int written = reindex(bankId, jobId);
            log.debug("Record index: job {} has {} record(s)", jobId, written);
        } catch (RuntimeException e) {
            log.warn("Record index: job {} could not be indexed ({}); it is indexed again on its next "
                    + "change or at the next startup", jobId, e.getClass().getSimpleName());
        }
    }

    /** VALIDATED or EXPORTED, not cancelled, rows still held (not purged). */
    static boolean indexable(IngestionJob job) {
        return (job.getCurrentStep() == JobStep.VALIDATED || job.getCurrentStep() == JobStep.EXPORTED)
                && job.getStatus() != JobStatus.CANCELLED
                && job.getPurgedAt() == null
                && job.getMappedRowsJson() != null;
    }

    private int insert(IngestionJob job) {
        ValidatedRows.Split split = ValidatedRows.of(job);
        if (!split.validated()) {
            return 0;
        }
        LocalDateTime createdAt = job.getUpdatedDate() != null ? job.getUpdatedDate() : LocalDateTime.now();
        List<Map<String, String>> valid = split.valid();
        for (int i = 0; i < valid.size(); i++) {
            Map<String, String> values = cleanValues(valid.get(i));
            TemplateRecord record = new TemplateRecord();
            record.setBankId(job.getBankId());
            record.setTemplateId(job.getTemplateId());
            record.setIngestionJobId(job.getIngestionJobId());
            record.setRowIndex(split.validRowIndexes().get(i));
            record.setOwnerUserId(job.getOwnerUserId());
            record.setValuesJson(toJson(values));
            record.setSearchText(RecordText.searchText(values));
            record.setCreatedAt(createdAt);
            entityManager.persist(record);
            for (Map.Entry<String, String> value : values.entrySet()) {
                String key = RecordText.key(value.getValue());
                if (!key.isEmpty() && value.getKey().length() <= 255) {
                    entityManager.persist(new TemplateRecordKey(record.getTemplateRecordId(), job.getBankId(),
                            value.getKey(), key));
                }
            }
            if ((i + 1) % CHUNK == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        entityManager.flush();
        entityManager.clear();
        return valid.size();
    }

    /** Field name to value; null values become "" and blank field names are dropped. */
    private static Map<String, String> cleanValues(Map<String, String> row) {
        Map<String, String> values = new LinkedHashMap<>();
        if (row != null) {
            row.forEach((field, value) -> {
                if (field != null && !field.isBlank()) {
                    values.put(field, value == null ? "" : value);
                }
            });
        }
        return values;
    }

    private static String toJson(Map<String, String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a record's values.", e);
        }
    }
}
