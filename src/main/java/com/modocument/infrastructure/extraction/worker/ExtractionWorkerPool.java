package com.modocument.infrastructure.extraction.worker;

import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.application.port.ExtractionTaskRunner;
import com.modocument.application.port.TaskEventBus;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The extraction execution engine of one application instance: a dispatcher
 * thread claims ready tasks by lease and hands them to a bounded executor.
 * Scale-out = run more instances; leases guarantee no double work. Shutdown
 * is graceful — in-flight tasks get a grace period, anything unfinished is
 * reclaimed by lease expiry on another node.
 */
@Component
@ConditionalOnProperty(name = "extraction.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ExtractionWorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorkerPool.class);

    private final ExtractionTaskQueue queue;
    private final ExtractionTaskRunner runner;
    private final TaskEventBus eventBus;
    private final NodeIdentity node;
    private final ExtractionProperties properties;

    private final String workerId = "worker-" + UUID.randomUUID();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeCount = new AtomicInteger();
    private final Object wakeLock = new Object();

    private volatile boolean running;
    private ExecutorService executor;
    private Thread dispatcher;
    private volatile Instant lastRenewal = Instant.EPOCH;

    public ExtractionWorkerPool(ExtractionTaskQueue queue,
                                ExtractionTaskRunner runner,
                                TaskEventBus eventBus,
                                NodeIdentity node,
                                ExtractionProperties properties) {
        this.queue = queue;
        this.runner = runner;
        this.eventBus = eventBus;
        this.node = node;
        this.properties = properties;
    }

    @Override
    public void start() {
        int threads = properties.getWorker().getThreads();
        executor = Executors.newFixedThreadPool(threads);
        running = true;
        eventBus.onTaskAvailable(this::wake);
        dispatcher = new Thread(this::dispatchLoop, "extraction-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
        log.info("Extraction worker pool started ({} threads, {})", threads, workerId);
    }

    private void dispatchLoop() {
        Duration lease = Duration.ofSeconds(properties.getWorker().getLeaseSeconds());
        while (running) {
            try {
                renewLeasesIfDue(lease);
                int free = properties.getWorker().getThreads() - activeCount.get();
                List<ExtractionTask> tasks = free > 0
                        ? queue.claim(workerId, node.id(), free, lease)
                        : List.of();
                if (tasks.isEmpty()) {
                    synchronized (wakeLock) {
                        wakeLock.wait(properties.getWorker().getPollMillis());
                    }
                    continue;
                }
                for (ExtractionTask task : tasks) {
                    submit(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Extraction dispatcher error: {}", e.getMessage());
            }
        }
    }

    private void submit(ExtractionTask task) {
        inFlight.add(task.getId());
        activeCount.incrementAndGet();
        executor.execute(() -> {
            try {
                runner.run(task, workerId);
            } catch (Exception e) {
                log.warn("Extraction task {} runner error: {}", task.getId(), e.getMessage());
            } finally {
                inFlight.remove(task.getId());
                activeCount.decrementAndGet();
                wake();
            }
        });
    }

    /** Extends leases of in-flight tasks every third of the lease duration. */
    private void renewLeasesIfDue(Duration lease) {
        Instant now = Instant.now();
        if (now.isBefore(lastRenewal.plus(lease.dividedBy(3)))) {
            return;
        }
        lastRenewal = now;
        for (String taskId : inFlight) {
            queue.renew(taskId, workerId, lease);
        }
    }

    public void wake() {
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    @Override
    public void stop() {
        running = false;
        wake();
        try {
            if (dispatcher != null) {
                dispatcher.join(2000);
            }
            if (executor != null) {
                executor.shutdown();
                if (!executor.awaitTermination(
                        properties.getWorker().getShutdownGraceSeconds(), TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Extraction worker pool stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> stats() {
        return Map.of(
                "workerId", workerId,
                "nodeId", node.id(),
                "threads", properties.getWorker().getThreads(),
                "active", activeCount.get(),
                "inFlight", inFlight.size(),
                "running", running);
    }
}
