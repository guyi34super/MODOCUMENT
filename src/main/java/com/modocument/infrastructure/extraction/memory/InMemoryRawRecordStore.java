package com.modocument.infrastructure.extraction.memory;

import com.modocument.application.port.RawRecordStore;
import com.modocument.domain.document.RawRecord;
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
public class InMemoryRawRecordStore implements RawRecordStore {

    private final Map<String, RawRecord> records = new ConcurrentHashMap<>();
    private final Set<String> hashes = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public boolean saveIfNew(RawRecord record) {
        if (record.getContentHash() != null
                && !hashes.add(record.getSourceId() + "|" + record.getContentHash())) {
            return false;
        }
        record.setId(String.format("%016d", sequence.incrementAndGet()));
        if (record.getFetchedAt() == null) {
            record.setFetchedAt(Instant.now());
        }
        records.put(record.getId(), record);
        return true;
    }

    @Override
    public List<RawRecord> findBatch(String jobId, String afterId, int limit) {
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
