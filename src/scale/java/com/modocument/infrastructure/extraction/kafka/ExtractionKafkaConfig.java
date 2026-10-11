package com.modocument.infrastructure.extraction.kafka;

import com.modocument.infrastructure.extraction.ExtractionProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the extraction topics (auto-created by KafkaAdmin on startup).
 * Keyed by jobId; partition count is the scale-out unit for wake-up fan-out.
 */
@Configuration
@ConditionalOnProperty(name = "extraction.kafka.enabled", havingValue = "true")
public class ExtractionKafkaConfig {

    @Bean
    public NewTopic extractionTaskTopic(ExtractionProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTaskTopic())
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic extractionDeadLetterTopic(ExtractionProperties properties) {
        return TopicBuilder.name(properties.getKafka().getDeadLetterTopic())
                .partitions(1)
                .replicas(1)
                .build();
    }
}
