package com.modocument.infrastructure.integration.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code modocument.integration.engine.*} (ws25): wiring of the flow engine —
 * not editable settings (those are {@code integration.*} in the settings
 * catalogue: max rows per run, run timeout, minimum schedule interval,
 * webhook retry attempts). Every value has a safe default.
 */
@ConfigurationProperties(prefix = "modocument.integration.engine")
public class IntegrationEngineProperties {

    /** Gates only the {@code @Scheduled} tick; tests drive {@code IntegrationScheduler#tick} directly. */
    private boolean schedulerEnabled = true;
    /** Deliveries claimed per bank per tick. */
    private int dispatchBatchSize = 50;
    /** How long a claimed delivery is hidden from other tasks while it is being sent. */
    private Duration dispatchLease = Duration.ofMinutes(2);
    /** First retry delay; doubles per attempt up to {@link #maxBackoff}. */
    private Duration baseBackoff = Duration.ofSeconds(30);
    private Duration maxBackoff = Duration.ofHours(1);
    /** Records read per connector call (one page). */
    private int readPageSize = 1000;
    /** Worker threads executing runs (each run holds one connection to a bank system). */
    private int workerThreads = 4;

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public int getDispatchBatchSize() {
        return dispatchBatchSize;
    }

    public void setDispatchBatchSize(int dispatchBatchSize) {
        this.dispatchBatchSize = dispatchBatchSize;
    }

    public Duration getDispatchLease() {
        return dispatchLease;
    }

    public void setDispatchLease(Duration dispatchLease) {
        this.dispatchLease = dispatchLease;
    }

    public Duration getBaseBackoff() {
        return baseBackoff;
    }

    public void setBaseBackoff(Duration baseBackoff) {
        this.baseBackoff = baseBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public int getReadPageSize() {
        return readPageSize;
    }

    public void setReadPageSize(int readPageSize) {
        this.readPageSize = readPageSize;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }
}
