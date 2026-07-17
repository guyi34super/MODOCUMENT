package com.modocument.infrastructure.extraction.memory;

import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.model.ExtractionTaskStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Store-backed lease queue for local/test runs. Implements the exact port
 * contract the Mongo adapter provides (atomic claims, lease expiry, backoff),
 * so the worker pipeline behaves identically in every profile.
 */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryExtractionTaskQueue implements ExtractionTaskQueue {

    private final Map<String, ExtractionTask> tasks = new ConcurrentHashMap<>();
    private final Set<String> dedupKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public synchronized boolean enqueue(ExtractionTask task) {
        if (task.getDedupKey() != null
                && !dedupKeys.add(task.getJobId() + "|" + task.getDedupKey())) {
            return false;
        }
        if (task.getId() == null) {
            task.setId(String.format("%016d", sequence.incrementAndGet()));
            task.setCreatedDate(Instant.now());
        }
        task.setStatus(ExtractionTaskStatus.PENDING);
        task.setUpdatedDate(Instant.now());
        tasks.put(task.getId(), task);
        return true;
    }

    @Override
    public synchronized List<ExtractionTask> claim(String owner, String node, int max, Duration lease) {
        Instant now = Instant.now();
        List<ExtractionTask> claimed = new ArrayList<>();
        List<String> ids = tasks.keySet().stream().sorted().toList();
        for (String id : ids) {
            if (claimed.size() >= max) {
                break;
            }
            ExtractionTask task = tasks.get(id);
            if (isReady(task, node, now)) {
                task.setStatus(ExtractionTaskStatus.LEASED);
                task.setLeaseOwner(owner);
                task.setLeaseExpiresAt(now.plus(lease));
                task.setAttempts(task.getAttempts() + 1);
                task.setUpdatedDate(now);
                claimed.add(task);
            }
        }
        return claimed;
    }

    private boolean isReady(ExtractionTask task, String node, Instant now) {
        return task.getStatus() == ExtractionTaskStatus.PENDING
                && !task.getNextAttemptAt().isAfter(now)
                && (task.getNodeAffinity() == null || task.getNodeAffinity().equals(node));
    }

    @Override
    public synchronized void renew(String taskId, String owner, Duration extension) {
        ExtractionTask task = tasks.get(taskId);
        if (task != null && task.getStatus() == ExtractionTaskStatus.LEASED
                && owner.equals(task.getLeaseOwner())) {
            task.setLeaseExpiresAt(Instant.now().plus(extension));
        }
    }

    @Override
    public synchronized void complete(String taskId, String owner, ExtractionTaskStatus terminal) {
        ExtractionTask task = requireLeasedBy(taskId, owner);
        task.setStatus(terminal);
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(null);
        task.setUpdatedDate(Instant.now());
    }

    @Override
    public synchronized boolean fail(String taskId, String owner, String error, Duration baseBackoff) {
        ExtractionTask task = requireLeasedBy(taskId, owner);
        task.setLastError(error);
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(null);
        task.setUpdatedDate(Instant.now());
        if (task.getAttempts() >= task.getMaxAttempts()) {
            task.setStatus(ExtractionTaskStatus.DEAD);
            return false;
        }
        long backoffMillis = baseBackoff.toMillis() * (1L << Math.min(task.getAttempts() - 1, 16));
        task.setNextAttemptAt(Instant.now().plusMillis(backoffMillis));
        task.setStatus(ExtractionTaskStatus.PENDING);
        return true;
    }

    private ExtractionTask requireLeasedBy(String taskId, String owner) {
        ExtractionTask task = tasks.get(taskId);
        if (task == null || task.getStatus() != ExtractionTaskStatus.LEASED
                || !owner.equals(task.getLeaseOwner())) {
            throw new IllegalStateException(
                    "Task " + taskId + " is not leased by " + owner + ".");
        }
        return task;
    }

    @Override
    public synchronized int requeueExpired(Instant now) {
        int requeued = 0;
        for (ExtractionTask task : tasks.values()) {
            if (task.getStatus() == ExtractionTaskStatus.LEASED
                    && task.getLeaseExpiresAt() != null
                    && task.getLeaseExpiresAt().isBefore(now)) {
                task.setStatus(ExtractionTaskStatus.PENDING);
                task.setLeaseOwner(null);
                task.setLeaseExpiresAt(null);
                task.setUpdatedDate(now);
                requeued++;
            }
        }
        return requeued;
    }

    @Override
    public Map<ExtractionTaskStatus, Long> countByStatus(String jobId) {
        Map<ExtractionTaskStatus, Long> counts = new EnumMap<>(ExtractionTaskStatus.class);
        for (ExtractionTask task : tasks.values()) {
            if (task.getJobId().equals(jobId)) {
                counts.merge(task.getStatus(), 1L, Long::sum);
            }
        }
        return counts;
    }

    @Override
    public long depth() {
        Instant now = Instant.now();
        return tasks.values().stream()
                .filter(t -> t.getStatus() == ExtractionTaskStatus.PENDING
                        && !t.getNextAttemptAt().isAfter(now))
                .count();
    }

    @Override
    public List<ExtractionTask> listByJob(String jobId, int limit) {
        return tasks.values().stream()
                .filter(t -> t.getJobId().equals(jobId))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<ExtractionTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }
}
