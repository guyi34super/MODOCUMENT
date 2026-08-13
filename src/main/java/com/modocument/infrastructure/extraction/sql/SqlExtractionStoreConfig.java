package com.modocument.infrastructure.extraction.sql;

import com.modocument.infrastructure.extraction.memory.InMemoryExtractionTaskQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * With extraction.store=sql the durable data (sources, jobs, records, audit,
 * connector state) lives in the primary database, but the in-flight task
 * queue deliberately stays in memory: leases and claims are transient work
 * coordination, and a restart is handled by SqlExtractionStartupRecovery
 * marking interrupted jobs FAILED so they can be re-run.
 */
@Configuration
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlExtractionStoreConfig {

    @Bean
    public InMemoryExtractionTaskQueue extractionTaskQueue() {
        return new InMemoryExtractionTaskQueue();
    }
}
