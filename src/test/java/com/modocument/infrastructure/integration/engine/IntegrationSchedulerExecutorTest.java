package com.modocument.infrastructure.integration.engine;

import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import com.modocument.infrastructure.settings.IntegrationSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ws27: the scheduled integration tick runs on its own executor, never on
 * the shared {@code @Scheduled} pool (a slow bank endpoint must not delay
 * the settings poll, the alert sweep or the agent sweep), and one tick at a
 * time — a firing while the previous tick still runs is skipped.
 */
class IntegrationSchedulerExecutorTest {

    private final IntegrationFlowJpaRepository flows = mock(IntegrationFlowJpaRepository.class);
    private final IntegrationEngineProperties properties = new IntegrationEngineProperties();
    private final CountDownLatch release = new CountDownLatch(1);
    private IntegrationScheduler scheduler;

    @AfterEach
    void stop() {
        release.countDown();
        if (scheduler != null) {
            scheduler.shutdownTickExecutor();
        }
    }

    @Test
    void theScheduleHandsTheTickOffAndNeverOverlapsIt() throws Exception {
        CountDownLatch tickStarted = new CountDownLatch(1);
        AtomicInteger ticks = new AtomicInteger();
        AtomicReference<String> tickThread = new AtomicReference<>();
        // The bank scan blocks, standing in for a slow bank endpoint.
        when(flows.findBankIdsWithEnabledFlows()).thenAnswer(invocation -> {
            ticks.incrementAndGet();
            tickThread.set(Thread.currentThread().getName());
            tickStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return List.of();
        });
        properties.setSchedulerEnabled(true);
        scheduler = new IntegrationScheduler(flows, mock(IntegrationRunJpaRepository.class),
                mock(OutboundDeliveryJpaRepository.class), mock(IntegrationRuns.class),
                mock(IntegrationRunExecutor.class), mock(OutboundDispatcher.class),
                mock(IntegrationSettings.class), properties,
                mock(PlatformTransactionManager.class));

        long start = System.nanoTime();
        scheduler.scheduled();
        long handOffMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(tickStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handOffMillis).as("the scheduler thread is released at once").isLessThan(2000);
        assertThat(tickThread.get()).startsWith("integration-tick-")
                .isNotEqualTo(Thread.currentThread().getName());

        // Still running: the next firing is skipped, not queued.
        scheduler.scheduled();
        scheduler.scheduled();
        release.countDown();
        Thread.sleep(200);
        assertThat(ticks.get()).isEqualTo(1);
    }
}
