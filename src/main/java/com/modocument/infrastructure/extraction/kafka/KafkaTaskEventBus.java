package com.modocument.infrastructure.extraction.kafka;

import com.modocument.application.port.TaskEventBus;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kafka-backed wake-up bus: publishing a jobId to the task topic causes some
 * instance in the consumer group to poll the store queue. Events carry no
 * task state (the store is the source of truth), so duplicate delivery and
 * lost signals are both harmless — workers also poll on an interval.
 */
@Component
@ConditionalOnProperty(name = "extraction.kafka.enabled", havingValue = "true")
public class KafkaTaskEventBus implements TaskEventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaTaskEventBus.class);

    private final KafkaTemplate<String, String> kafka;
    private final ExtractionProperties properties;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public KafkaTaskEventBus(KafkaTemplate<String, String> kafka, ExtractionProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    @Override
    public void publishTaskAvailable(String jobId) {
        try {
            kafka.send(properties.getKafka().getTaskTopic(), jobId, jobId);
        } catch (Exception e) {
            // Workers poll the store anyway; a lost wake-up only adds latency.
            log.warn("Could not publish task-available event: {}", e.getMessage());
        }
    }

    @Override
    public void onTaskAvailable(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public void publishDeadLetter(String taskId, String jobId) {
        try {
            kafka.send(properties.getKafka().getDeadLetterTopic(), jobId, taskId);
        } catch (Exception e) {
            log.warn("Could not publish dead-letter event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "${extraction.kafka.task-topic:extraction.task-events}",
            groupId = "${extraction.kafka.group-id:modocument-extraction-workers}")
    public void onTaskEvent(String jobId) {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
