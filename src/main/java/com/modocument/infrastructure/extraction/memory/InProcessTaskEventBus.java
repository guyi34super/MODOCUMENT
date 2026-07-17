package com.modocument.infrastructure.extraction.memory;

import com.modocument.application.port.TaskEventBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single-node event bus: publishing simply wakes the local listeners. Used
 * whenever Kafka is not enabled; workers also poll, so nothing is lost if no
 * listener is registered yet.
 */
@Component
@ConditionalOnProperty(name = "extraction.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class InProcessTaskEventBus implements TaskEventBus {

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publishTaskAvailable(String jobId) {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    @Override
    public void onTaskAvailable(Runnable listener) {
        listeners.add(listener);
    }
}
