package com.modocument.application.port;

import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.model.ExtractionTaskStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The work queue: task documents with atomic, time-boxed lease claims. The
 * store (Mongo in production, memory locally) is the source of truth for
 * lease/attempt state in every distribution mode — Kafka only signals that
 * work is available.
 */
public interface ExtractionTaskQueue {

    /**
     * Adds the task if no task with the same (jobId, dedupKey) exists yet.
     *
     * @return true if enqueued, false if it was a duplicate.
     */
    boolean enqueue(ExtractionTask task);

    /**
     * Atomically claims up to max ready tasks (PENDING, nextAttemptAt due,
     * matching node affinity if set) for the given owner.
     */
    List<ExtractionTask> claim(String owner, String node, int max, Duration lease);

    /** Extends the lease of a task this owner is still working on. */
    void renew(String taskId, String owner, Duration extension);

    /** Marks a leased task terminal (COMPLETED or SKIPPED). */
    void complete(String taskId, String owner, ExtractionTaskStatus terminal);

    /**
     * Returns a leased task to PENDING without consuming an attempt (e.g. the
     * job was paused after the claim).
     */
    void release(String taskId, String owner);

    /**
     * Records a failure: schedules a retry with exponential backoff, or marks
     * the task DEAD once attempts are exhausted.
     *
     * @return true if a retry was scheduled, false if the task is DEAD.
     */
    boolean fail(String taskId, String owner, String error, Duration baseBackoff);

    /** Returns expired LEASED tasks to PENDING (crash recovery). */
    int requeueExpired(Instant now);

    Map<ExtractionTaskStatus, Long> countByStatus(String jobId);

    /** Tasks ready to claim right now (queue depth across jobs). */
    long depth();

    List<ExtractionTask> listByJob(String jobId, int limit);

    Optional<ExtractionTask> get(String taskId);
}
