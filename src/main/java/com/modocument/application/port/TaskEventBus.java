package com.modocument.application.port;

/**
 * Wake-up signal that extraction work is available. Kafka in production (any
 * instance in the consumer group reacts); an in-process bus locally. The bus
 * carries no task state — workers always claim from the ExtractionTaskQueue,
 * so duplicate or lost signals are harmless.
 */
public interface TaskEventBus {

    void publishTaskAvailable(String jobId);

    /** Registers a listener invoked when work may be available. */
    void onTaskAvailable(Runnable listener);

    /** Announces a task that exhausted its retries (ops visibility). */
    default void publishDeadLetter(String taskId, String jobId) {
    }
}
