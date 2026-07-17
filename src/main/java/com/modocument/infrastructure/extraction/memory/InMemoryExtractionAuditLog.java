package com.modocument.infrastructure.extraction.memory;

import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.domain.document.ExtractionAuditEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryExtractionAuditLog implements ExtractionAuditLog {

    private final Map<String, ExtractionAuditEvent> events = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public void add(ExtractionAuditEvent event) {
        event.setId(String.format("%016d", sequence.incrementAndGet()));
        events.put(event.getId(), event);
    }

    @Override
    public List<ExtractionAuditEvent> findByJob(String jobId, String afterId, int limit) {
        return events.values().stream()
                .filter(e -> jobId.equals(e.getJobId()))
                .filter(e -> afterId == null || e.getId().compareTo(afterId) > 0)
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .limit(limit)
                .toList();
    }
}
