package com.modocument.infrastructure.integration.engine;

import com.modocument.application.port.TenantContext;
import com.modocument.infrastructure.async.TenantTaskDecorator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Where runs execute (ws25): a small dedicated pool, so a slow bank system
 * never blocks the shared scheduler thread (alert sweep, retention, ...).
 * Every task runs inside {@code TenantContext.runAs(bank)} of the run's bank.
 * {@code modocument.integration.engine.synchronous=true} (tests) runs the
 * work in the caller's thread instead.
 */
@Component
public class IntegrationRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(IntegrationRunExecutor.class);

    private final FlowRunner runner;
    private final boolean synchronous;
    private final ThreadPoolTaskExecutor pool;

    public IntegrationRunExecutor(FlowRunner runner, IntegrationEngineProperties properties,
                                  @Value("${modocument.integration.engine.synchronous:false}")
                                  boolean synchronous) {
        this.runner = runner;
        this.synchronous = synchronous;
        this.pool = new ThreadPoolTaskExecutor();
        int threads = Math.max(1, properties.getWorkerThreads());
        pool.setCorePoolSize(threads);
        pool.setMaxPoolSize(threads);
        pool.setQueueCapacity(500);
        pool.setThreadNamePrefix("integration-run-");
        pool.setTaskDecorator(new TenantTaskDecorator());
        pool.initialize();
    }

    /** Executes the run in its bank's tenant context (async unless synchronous). */
    public void submit(int bankId, long runId) {
        Runnable work = () -> TenantContext.runAs(bankId, () -> {
            try {
                runner.execute(bankId, runId);
            } catch (RuntimeException e) {
                log.error("Integration run {} crashed", runId, e);
            }
        });
        if (synchronous) {
            work.run();
        } else {
            pool.execute(work);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
}
