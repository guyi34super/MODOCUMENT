package com.modocument.infrastructure.extraction.memory;

import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.model.ExtractionTaskStatus;
import com.modocument.domain.model.ExtractionTaskType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for the lease queue semantics the worker pipeline relies on
 * (the Mongo adapter implements the same port contract with findAndModify).
 */
class InMemoryExtractionTaskQueueTest {

    private final InMemoryExtractionTaskQueue queue = new InMemoryExtractionTaskQueue();

    private ExtractionTask newTask(String jobId, String dedupKey) {
        ExtractionTask task = new ExtractionTask();
        task.setJobId(jobId);
        task.setSourceId("s1");
        task.setType(ExtractionTaskType.FETCH_URL);
        task.setDedupKey(dedupKey);
        task.setMaxAttempts(3);
        return task;
    }

    @Test
    void enqueueDeduplicatesPerJob() {
        assertThat(queue.enqueue(newTask("job1", "url:a"))).isTrue();
        assertThat(queue.enqueue(newTask("job1", "url:a"))).isFalse();
        // Same key on another job is fine.
        assertThat(queue.enqueue(newTask("job2", "url:a"))).isTrue();
    }

    @Test
    void claimLeasesTasksExclusively() {
        queue.enqueue(newTask("job1", "url:a"));
        queue.enqueue(newTask("job1", "url:b"));

        List<ExtractionTask> first = queue.claim("w1", "node1", 10, Duration.ofMinutes(1));
        List<ExtractionTask> second = queue.claim("w2", "node1", 10, Duration.ofMinutes(1));

        assertThat(first).hasSize(2);
        assertThat(second).isEmpty();
        assertThat(first).allSatisfy(task -> {
            assertThat(task.getStatus()).isEqualTo(ExtractionTaskStatus.LEASED);
            assertThat(task.getLeaseOwner()).isEqualTo("w1");
            assertThat(task.getAttempts()).isEqualTo(1);
        });
    }

    @Test
    void nodeAffinityRestrictsClaims() {
        ExtractionTask task = newTask("job1", "db:0");
        task.setNodeAffinity("node-A");
        queue.enqueue(task);

        assertThat(queue.claim("w1", "node-B", 10, Duration.ofMinutes(1))).isEmpty();
        assertThat(queue.claim("w1", "node-A", 10, Duration.ofMinutes(1))).hasSize(1);
    }

    @Test
    void completeIsOwnerGuarded() {
        queue.enqueue(newTask("job1", "url:a"));
        ExtractionTask task = queue.claim("w1", "n", 1, Duration.ofMinutes(1)).get(0);

        assertThatThrownBy(() -> queue.complete(task.getId(), "intruder",
                ExtractionTaskStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);

        queue.complete(task.getId(), "w1", ExtractionTaskStatus.COMPLETED);
        assertThat(queue.get(task.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionTaskStatus.COMPLETED);
    }

    @Test
    void failSchedulesBackoffThenDies() {
        queue.enqueue(newTask("job1", "url:a"));
        ExtractionTask task = queue.claim("w1", "n", 1, Duration.ofMinutes(1)).get(0);

        assertThat(queue.fail(task.getId(), "w1", "boom", Duration.ofMillis(10))).isTrue();
        ExtractionTask afterFail = queue.get(task.getId()).orElseThrow();
        assertThat(afterFail.getStatus()).isEqualTo(ExtractionTaskStatus.PENDING);
        assertThat(afterFail.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));

        // Exhaust the remaining attempts (max 3).
        for (int attempt = 2; attempt <= 3; attempt++) {
            awaitBackoff(afterFail);
            ExtractionTask claimed = queue.claim("w1", "n", 1, Duration.ofMinutes(1)).get(0);
            queue.fail(claimed.getId(), "w1", "boom", Duration.ofMillis(1));
            afterFail = queue.get(task.getId()).orElseThrow();
        }
        assertThat(afterFail.getStatus()).isEqualTo(ExtractionTaskStatus.DEAD);
    }

    private void awaitBackoff(ExtractionTask task) {
        while (Instant.now().isBefore(task.getNextAttemptAt())) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void backedOffTaskIsNotImmediatelyClaimable() {
        queue.enqueue(newTask("job1", "url:a"));
        ExtractionTask task = queue.claim("w1", "n", 1, Duration.ofMinutes(1)).get(0);
        queue.fail(task.getId(), "w1", "boom", Duration.ofMinutes(5));

        assertThat(queue.claim("w1", "n", 1, Duration.ofMinutes(1))).isEmpty();
        assertThat(queue.depth()).isZero();
    }

    @Test
    void releaseReturnsTaskWithoutConsumingAttempt() {
        queue.enqueue(newTask("job1", "url:a"));
        ExtractionTask task = queue.claim("w1", "n", 1, Duration.ofMinutes(1)).get(0);

        queue.release(task.getId(), "w1");

        ExtractionTask released = queue.get(task.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(ExtractionTaskStatus.PENDING);
        assertThat(released.getAttempts()).isZero();
    }

    @Test
    void requeueExpiredReclaimsCrashedLeases() {
        queue.enqueue(newTask("job1", "url:a"));
        queue.claim("w1", "n", 1, Duration.ofMinutes(1));

        // Nothing expired yet.
        assertThat(queue.requeueExpired(Instant.now())).isZero();
        // Pretend the lease deadline passed (sweep from the future).
        assertThat(queue.requeueExpired(Instant.now().plus(Duration.ofMinutes(2)))).isEqualTo(1);
        assertThat(queue.claim("w2", "n", 1, Duration.ofMinutes(1))).hasSize(1);
    }

    @Test
    void countByStatusAndDepthReflectQueueState() {
        queue.enqueue(newTask("job1", "url:a"));
        queue.enqueue(newTask("job1", "url:b"));
        queue.claim("w1", "n", 1, Duration.ofMinutes(1));

        Map<ExtractionTaskStatus, Long> counts = queue.countByStatus("job1");
        assertThat(counts.get(ExtractionTaskStatus.LEASED)).isEqualTo(1);
        assertThat(counts.get(ExtractionTaskStatus.PENDING)).isEqualTo(1);
        assertThat(queue.depth()).isEqualTo(1);
    }
}
