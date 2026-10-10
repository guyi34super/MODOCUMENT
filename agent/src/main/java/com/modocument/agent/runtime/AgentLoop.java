package com.modocument.agent.runtime;

import com.modocument.agent.cloud.CloudClient;
import com.modocument.agent.cloud.CloudException;
import com.modocument.agent.cloud.Protocol;
import com.modocument.agent.exec.TaskExecutor;
import com.modocument.agent.state.CredentialStore;
import com.modocument.agent.state.CredentialStore.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The agent's life:
 * <ol>
 *   <li>credentials from the state directory, or — first start — enrol
 *       with the one-time token and store what MoDocument issued (0600);</li>
 *   <li>a heartbeat every 30 s (version, health, per-connection status);</li>
 *   <li>long-poll {@code /tasks}, execute each task, repeat — exponential
 *       back-off with jitter on any error, reset on the next success.</li>
 * </ol>
 * The individual steps are public so tests can drive them one at a time.
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final CloudClient cloud;
    private final CredentialStore store;
    private final TaskExecutor executor;
    private final Path stateDir;
    private final String version;
    private final String name;
    private final String enrolmentToken;
    private final int pollWaitSeconds;
    private final Duration heartbeat;
    private final Backoff backoff;
    private volatile boolean running = true;
    private volatile String announcedLatest;
    private ScheduledExecutorService heartbeats;

    public AgentLoop(CloudClient cloud, CredentialStore store, TaskExecutor executor, Path stateDir,
                     String version, String name, String enrolmentToken, int pollWaitSeconds,
                     Duration heartbeat, Duration maxBackoff) {
        this.cloud = cloud;
        this.store = store;
        this.executor = executor;
        this.stateDir = stateDir;
        this.version = version;
        this.name = name;
        this.enrolmentToken = enrolmentToken;
        this.pollWaitSeconds = pollWaitSeconds;
        this.heartbeat = heartbeat;
        this.backoff = new Backoff(maxBackoff);
    }

    /** Loads the stored credentials or enrols; throws when neither is possible. */
    public Credentials ensureEnrolled() {
        Optional<Credentials> stored = store.load();
        if (stored.isPresent()) {
            cloud.useCredentials(stored.get());
            if (enrolmentToken != null && !enrolmentToken.isBlank()) {
                log.info("Agent {} is already enrolled; MODOCUMENT_ENROLMENT_TOKEN is ignored "
                        + "and can be removed.", stored.get().agentId());
            }
            return stored.get();
        }
        if (enrolmentToken == null || enrolmentToken.isBlank()) {
            throw new IllegalStateException("Not enrolled yet: start the agent once with "
                    + "MODOCUMENT_ENROLMENT_TOKEN (from the dashboard's agent registration).");
        }
        Protocol.EnrollResponse enrolled = cloud.enroll(new Protocol.EnrollRequest(
                enrolmentToken.strip(), name, version, "OAUTH2_CLIENT_CREDENTIALS", null));
        Credentials credentials = new Credentials(enrolled.agentId(), enrolled.clientId(),
                enrolled.secret(), cloud.tokenUrlFor(enrolled.tokenUrl()));
        store.save(credentials);
        cloud.useCredentials(credentials);
        log.info("Enrolled as agent {} (client {}); credentials stored in {}", enrolled.agentId(),
                enrolled.clientId(), stateDir);
        return credentials;
    }

    /** One long poll and the execution of every task it returned. */
    public int pollOnce(int waitSeconds) {
        Protocol.TasksResponse response = cloud.tasks(waitSeconds);
        HealthFile.touch(stateDir);
        for (Protocol.AgentTask task : response.tasks()) {
            executor.execute(task);
        }
        return response.tasks().size();
    }

    /** One heartbeat; logs (once per version) when MoDocument runs a newer release. */
    public Protocol.HeartbeatResponse heartbeatOnce() {
        Protocol.HeartbeatResponse response = cloud.heartbeat(new Protocol.HeartbeatRequest(
                version, "OK", executor.health()));
        HealthFile.touch(stateDir);
        String latest = response.latestVersion();
        if (latest != null && !latest.equals(version) && !latest.equals(announcedLatest)) {
            announcedLatest = latest;
            log.info("MoDocument runs version {} (this agent: {}). Upgrade by pulling the "
                    + "matching agent image and restarting the container.", latest, version);
        }
        return response;
    }

    /** Runs until {@link #stop()}. */
    public void run() {
        while (running) {
            try {
                ensureEnrolled();
                break;
            } catch (CloudException | IllegalStateException e) {
                long delay = backoff.nextDelayMillis();
                log.error("Enrolment failed: {} — retrying in {} s", e.getMessage(), delay / 1000);
                sleep(delay);
            }
        }
        backoff.reset();
        heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "agent-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeats.scheduleWithFixedDelay(() -> {
            try {
                heartbeatOnce();
            } catch (RuntimeException e) {
                log.warn("Heartbeat failed: {}", e.getMessage());
            }
        }, 0, heartbeat.toMillis(), TimeUnit.MILLISECONDS);

        while (running) {
            try {
                pollOnce(pollWaitSeconds);
                backoff.reset();
            } catch (RuntimeException e) {
                long delay = backoff.nextDelayMillis();
                if (e instanceof CloudException cloudError && cloudError.isUnauthorized()) {
                    log.error("MoDocument refused the agent's credentials (revoked?): {} — retrying "
                            + "in {} s", e.getMessage(), delay / 1000);
                } else {
                    log.warn("Poll failed: {} — retrying in {} s", e.getMessage(), delay / 1000);
                }
                sleep(delay);
            }
        }
    }

    public void stop() {
        running = false;
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
