package com.modocument.infrastructure.extraction.sql;

import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.document.RawRecord;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionProcessedRecordEntity;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionRawRecordEntity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ws28: the SQL record stores write a batch in ONE transaction (they used to
 * open one per record), and fall back to the per-record path — the exact old
 * dedup semantics — when a concurrent writer trips the unique constraint.
 */
class SqlRecordStoreBatchTransactionTest {

    /** Counts the transactions the store opens. */
    private static final class CountingTransactions implements PlatformTransactionManager {
        final AtomicInteger begun = new AtomicInteger();
        final AtomicInteger rolledBack = new AtomicInteger();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun.incrementAndGet();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack.incrementAndGet();
        }
    }

    private static List<ProcessedRecord> processed(int count) {
        List<ProcessedRecord> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ProcessedRecord record = new ProcessedRecord();
            record.setJobId("job-1");
            record.setDedupKey("key-" + i);
            record.setFields(new java.util.LinkedHashMap<>(Map.of("A", String.valueOf(i))));
            record.setProcessedAt(Instant.now());
            records.add(record);
        }
        return records;
    }

    @Test
    void fiveHundredProcessedRecordsAreOneTransaction() {
        ExtractionProcessedRecordJpaRepository repository = mock(ExtractionProcessedRecordJpaRepository.class);
        AtomicLong ids = new AtomicLong();
        when(repository.findExistingDedupKeys(anyString(), anyCollection())).thenReturn(List.of("key-3"));
        when(repository.saveAll(any())).thenAnswer(call -> {
            List<ExtractionProcessedRecordEntity> saved = new ArrayList<>();
            for (ExtractionProcessedRecordEntity entity
                    : (Iterable<ExtractionProcessedRecordEntity>) call.getArgument(0)) {
                entity.setId(ids.incrementAndGet());
                saved.add(entity);
            }
            return saved;
        });
        CountingTransactions transactions = new CountingTransactions();
        SqlProcessedRecordStore store = new SqlProcessedRecordStore(repository, transactions);

        List<ProcessedRecord> batch = processed(500);
        assertThat(store.saveAllIfNew(batch)).isEqualTo(499);

        assertThat(transactions.begun).hasValue(1);
        verify(repository, times(1)).saveAll(any());
        verify(repository, never()).saveAndFlush(any());
        assertThat(batch.get(3).getId()).isNull();
        assertThat(batch.get(4).getId()).isNotNull();
    }

    @Test
    void aConcurrentDuplicateRollsTheBatchBackAndReplaysItRecordByRecord() {
        ExtractionProcessedRecordJpaRepository repository = mock(ExtractionProcessedRecordJpaRepository.class);
        when(repository.findExistingDedupKeys(anyString(), anyCollection())).thenReturn(List.of());
        when(repository.saveAll(any())).thenThrow(new DataIntegrityViolationException("raced"));
        AtomicLong ids = new AtomicLong();
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            ExtractionProcessedRecordEntity entity = call.getArgument(0);
            if ("key-1".equals(entity.getDedupKey())) {
                throw new DataIntegrityViolationException("stored by the other worker");
            }
            entity.setId(ids.incrementAndGet());
            return entity;
        });
        CountingTransactions transactions = new CountingTransactions();
        SqlProcessedRecordStore store = new SqlProcessedRecordStore(repository, transactions);

        List<ProcessedRecord> batch = processed(3);
        assertThat(store.saveAllIfNew(batch)).isEqualTo(2);
        assertThat(transactions.rolledBack).hasValue(1);
        assertThat(batch.get(1).getId()).isNull();
        assertThat(batch.get(0).getId()).isNotNull();
    }

    @Test
    void aFetchResultsRawPayloadsAreOneTransaction() {
        ExtractionRawRecordJpaRepository repository = mock(ExtractionRawRecordJpaRepository.class);
        when(repository.findExistingHashes(anyString(), anyCollection())).thenReturn(List.of("h1"));
        AtomicLong ids = new AtomicLong();
        when(repository.saveAll(any())).thenAnswer(call -> {
            List<ExtractionRawRecordEntity> saved = new ArrayList<>();
            for (ExtractionRawRecordEntity entity : (Iterable<ExtractionRawRecordEntity>) call.getArgument(0)) {
                entity.setId(ids.incrementAndGet());
                saved.add(entity);
            }
            return saved;
        });
        CountingTransactions transactions = new CountingTransactions();
        SqlRawRecordStore store = new SqlRawRecordStore(repository, transactions);

        List<RawRecord> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            RawRecord record = new RawRecord();
            record.setSourceId("src");
            record.setContentHash("h" + (i % 10));
            record.setFetchedAt(Instant.now());
            batch.add(record);
        }
        boolean[] stored = store.saveAllIfNew(batch);

        assertThat(transactions.begun).hasValue(1);
        int count = 0;
        for (boolean b : stored) {
            count += b ? 1 : 0;
        }
        // h0..h9 once each, minus h1 already stored.
        assertThat(count).isEqualTo(9);
        assertThat(stored[1]).isFalse();
        assertThat(stored[10]).isFalse();
    }
}
