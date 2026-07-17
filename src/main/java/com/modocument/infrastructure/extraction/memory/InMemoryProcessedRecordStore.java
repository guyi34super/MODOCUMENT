package com.modocument.infrastructure.extraction.memory;

import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.domain.document.ProcessedRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryProcessedRecordStore implements ProcessedRecordStore {

    private final Map<String, ProcessedRecord> records = new ConcurrentHashMap<>();
    private final Set<String> dedupKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public int saveAllIfNew(List<ProcessedRecord> batch) {
        int saved = 0;
        for (ProcessedRecord record : batch) {
            if (record.getDedupKey() != null
                    && !dedupKeys.add(record.getJobId() + "|" + record.getDedupKey())) {
                continue;
            }
            record.setId(String.format("%016d", sequence.incrementAndGet()));
            if (record.getProcessedAt() == null) {
                record.setProcessedAt(Instant.now());
            }
            records.put(record.getId(), record);
            saved++;
        }
        return saved;
    }

    @Override
    public List<ProcessedRecord> findBatch(String jobId, String afterId, int limit) {
        return records.values().stream()
                .filter(r -> r.getJobId().equals(jobId))
                .filter(r -> afterId == null || r.getId().compareTo(afterId) > 0)
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .limit(limit)
                .toList();
    }

    @Override
    public long countByJob(String jobId) {
        return records.values().stream().filter(r -> r.getJobId().equals(jobId)).count();
    }
}
