package com.modocument.infrastructure.integration.agent;

import com.modocument.application.dto.AgentProtocol;
import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.AgentRunPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The long poll behind {@code GET /api/agent/v1/tasks?wait=N} (ws26).
 *
 * <p>No request thread waits: the first claim runs on the request thread
 * and, when it finds nothing, the request turns into a
 * {@link DeferredResult} and the servlet thread goes back to the pool. One
 * scheduler thread re-tries the claim of every waiting agent each
 * {@code modocument.agent.long-poll.interval} (default 2 s) and answers as
 * soon as tasks exist or the wait is over. Bounds:
 * <ul>
 *   <li>{@code wait} is clamped to 0-30 s;</li>
 *   <li>one waiter per agent — a new poll answers the previous one empty;</li>
 *   <li>at most {@code modocument.agent.long-poll.max-waiters} (default
 *       500) waiting polls per instance; beyond that a poll answers at once
 *       (empty) and the agent simply polls again;</li>
 *   <li>the DeferredResult times out 5 s after the wait, as a backstop.</li>
 * </ul>
 * Claims run as the agent's bank ({@code TenantContext.runAs}), never as
 * system. A claim error answers empty (the agent backs off and retries);
 * claimed-but-undeliverable tasks (the agent disconnected at that instant)
 * are failed by the flow engine's run timeout.
 */
@Component
public class AgentTaskLongPoll implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskLongPoll.class);

    /** Contract maximum for {@code wait}. */
    public static final int MAX_WAIT_SECONDS = 30;

    private final AgentRunPort runs;
    private final int maxWaiters;
    private final int maxTasks;
    private final ScheduledExecutorService scheduler;
    private final Map<Integer, Waiter> waiters = new ConcurrentHashMap<>();

    public AgentTaskLongPoll(AgentRunPort runs,
                             @Value("${modocument.agent.long-poll.interval-millis:2000}") long intervalMillis,
                             @Value("${modocument.agent.long-poll.max-waiters:500}") int maxWaiters,
                             @Value("${modocument.agent.long-poll.max-tasks:10}") int maxTasks) {
        if (intervalMillis < 50 || maxWaiters < 1 || maxTasks < 1) {
            throw new IllegalStateException("modocument.agent.long-poll.* out of range");
        }
        this.runs = runs;
        this.maxWaiters = maxWaiters;
        this.maxTasks = maxTasks;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "agent-long-poll");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::tick, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /** Answers with the agent's claimable tasks, waiting up to {@code waitSeconds} for some. */
    public DeferredResult<AgentProtocol.TasksResponse> poll(int bankId, int agentId, int waitSeconds) {
        int wait = Math.max(0, Math.min(MAX_WAIT_SECONDS, waitSeconds));
        AgentProtocol.TasksResponse empty = new AgentProtocol.TasksResponse(List.of());
        DeferredResult<AgentProtocol.TasksResponse> result =
                new DeferredResult<>((wait + 5) * 1000L, empty);

        List<AgentRunPort.AgentTask> now = claim(bankId, agentId);
        if (!now.isEmpty() || wait == 0 || waiters.size() >= maxWaiters) {
            result.setResult(new AgentProtocol.TasksResponse(now));
            return result;
        }
        Waiter waiter = new Waiter(bankId, agentId, result, System.nanoTime() + wait * 1_000_000_000L);
        result.onCompletion(() -> waiters.remove(agentId, waiter));
        Waiter previous = waiters.put(agentId, waiter);
        if (previous != null) {
            previous.result().setResult(empty);
        }
        return result;
    }

    /** Waiting polls right now (metrics/tests). */
    public int waiting() {
        return waiters.size();
    }

    /** One pass over the waiting polls (the scheduler's job; public so tests can drive it). */
    public void tick() {
        for (Waiter waiter : waiters.values()) {
            try {
                if (waiter.result().isSetOrExpired()) {
                    waiters.remove(waiter.agentId(), waiter);
                    continue;
                }
                if (System.nanoTime() - waiter.deadlineNanos() >= 0) {
                    waiter.result().setResult(new AgentProtocol.TasksResponse(List.of()));
                    waiters.remove(waiter.agentId(), waiter);
                    continue;
                }
                List<AgentRunPort.AgentTask> tasks = claim(waiter.bankId(), waiter.agentId());
                if (!tasks.isEmpty()) {
                    if (!waiter.result().setResult(new AgentProtocol.TasksResponse(tasks))) {
                        log.warn("Agent {}: {} claimed task(s) could not be delivered (poll ended); "
                                + "the run timeout will fail them", waiter.agentId(), tasks.size());
                    }
                    waiters.remove(waiter.agentId(), waiter);
                }
            } catch (RuntimeException e) {
                log.warn("Agent long poll tick failed for agent {} ({})", waiter.agentId(),
                        e.getClass().getSimpleName());
            }
        }
    }

    private List<AgentRunPort.AgentTask> claim(int bankId, int agentId) {
        try {
            List<AgentRunPort.AgentTask> tasks = TenantContext.runAs(bankId,
                    () -> runs.claimTasks(bankId, agentId, maxTasks));
            return tasks == null ? List.of() : tasks;
        } catch (Exception e) {
            log.warn("Agent {}: claiming tasks failed ({})", agentId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
        for (Waiter waiter : waiters.values()) {
            waiter.result().setResult(new AgentProtocol.TasksResponse(List.of()));
        }
        waiters.clear();
    }

    private record Waiter(int bankId, int agentId,
                          DeferredResult<AgentProtocol.TasksResponse> result, long deadlineNanos) {
    }
}
