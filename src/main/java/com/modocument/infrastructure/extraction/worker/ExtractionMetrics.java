package com.modocument.infrastructure.extraction.worker;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** In-process counters backing the extraction monitoring endpoint. */
@Component
public class ExtractionMetrics {

    private final AtomicLong fetches = new AtomicLong();
    private final AtomicLong robotsBlocked = new AtomicLong();
    private final AtomicLong domainBlocked = new AtomicLong();
    private final AtomicLong notModified = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong dedupedRecords = new AtomicLong();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong tasksCompleted = new AtomicLong();
    private final AtomicLong tasksFailed = new AtomicLong();
    private final AtomicLong expiredLeasesRequeued = new AtomicLong();

    public void fetch() {
        fetches.incrementAndGet();
    }

    public void robotsBlocked() {
        robotsBlocked.incrementAndGet();
    }

    public void domainBlocked() {
        domainBlocked.incrementAndGet();
    }

    public void notModified() {
        notModified.incrementAndGet();
    }

    public void retry() {
        retries.incrementAndGet();
    }

    public void dedupedRecords(long count) {
        dedupedRecords.addAndGet(count);
    }

    public void llmCall() {
        llmCalls.incrementAndGet();
    }

    public void taskCompleted() {
        tasksCompleted.incrementAndGet();
    }

    public void taskFailed() {
        tasksFailed.incrementAndGet();
    }

    public void expiredLeasesRequeued(long count) {
        expiredLeasesRequeued.addAndGet(count);
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("fetches", fetches.get());
        values.put("robotsBlocked", robotsBlocked.get());
        values.put("domainBlocked", domainBlocked.get());
        values.put("notModified", notModified.get());
        values.put("retries", retries.get());
        values.put("dedupedRecords", dedupedRecords.get());
        values.put("llmCalls", llmCalls.get());
        values.put("tasksCompleted", tasksCompleted.get());
        values.put("tasksFailed", tasksFailed.get());
        values.put("expiredLeasesRequeued", expiredLeasesRequeued.get());
        return values;
    }
}
