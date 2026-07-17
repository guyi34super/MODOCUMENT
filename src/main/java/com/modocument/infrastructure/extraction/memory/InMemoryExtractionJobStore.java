package com.modocument.infrastructure.extraction.memory;

import com.modocument.application.port.ExtractionJobStore;
import com.modocument.domain.document.ExtractionJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryExtractionJobStore implements ExtractionJobStore {

    private final Map<String, ExtractionJob> jobs = new ConcurrentHashMap<>();

    @Override
    public ExtractionJob save(ExtractionJob job) {
        if (job.getId() == null) {
            job.setId(UUID.randomUUID().toString());
            job.setCreatedDate(Instant.now());
        }
        job.setUpdatedDate(Instant.now());
        jobs.put(job.getId(), job);
        return job;
    }

    @Override
    public Optional<ExtractionJob> getById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<ExtractionJob> list() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(ExtractionJob::getCreatedDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public void increment(String jobId, String counter, long delta) {
        ExtractionJob job = jobs.get(jobId);
        if (job != null) {
            job.getCounters().merge(counter, delta, Long::sum);
        }
    }
}
