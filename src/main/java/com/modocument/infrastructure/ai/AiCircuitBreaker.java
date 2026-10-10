package com.modocument.infrastructure.ai;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.settings.SettingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Circuit breaker in front of the AI endpoint (ws23, plan §4): one per
 * application instance, used by {@link AiGateway} for every model call.
 *
 * <ul>
 *   <li><b>CLOSED</b> — calls pass. Each transport failure, timeout or 5xx
 *       ({@link AiFailureClassifier#countsForCircuit}) adds one to a run of
 *       consecutive failures; any answer from the endpoint (success, 4xx,
 *       unusable reply) resets it. At {@code ai.circuitBreaker.failureThreshold}
 *       (platform setting, default 5) the circuit opens.</li>
 *   <li><b>OPEN</b> — calls are refused at once ({@link AiUnavailableException},
 *       503 at the API; the ingestion parse stage falls back to code with
 *       CIRCUIT_OPEN) for {@code ai.circuitBreaker.openDuration} (default
 *       120 s).</li>
 *   <li><b>HALF_OPEN</b> — after that, exactly one trial call is let through;
 *       success closes the circuit, a counted failure re-opens it for another
 *       full duration, any other outcome closes it (the endpoint answered).</li>
 * </ul>
 *
 * <p>Opening from CLOSED is audited as {@code AI_CIRCUIT_OPENED} under the
 * platform bank (skipped, with a WARN, while no platform bank exists) and
 * feeds the {@code ai-circuit-opened} alert rule. The event carries counts
 * and the failure type only. Re-opening after a failed trial is logged, not
 * audited, so a long outage does not flood the audit trail.
 */
@Component
public class AiCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** What opening the circuit reports: no prompt or completion content. */
    public record Opened(int consecutiveFailures, int threshold, Duration openFor,
                         String lastFailureType) {
    }

    private static final Logger log = LoggerFactory.getLogger(AiCircuitBreaker.class);

    static final int DEFAULT_FAILURE_THRESHOLD = 5;
    static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(120);

    private final IntSupplier failureThreshold;
    private final Supplier<Duration> openDuration;
    private final Clock clock;
    private final Consumer<Opened> onOpened;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private Duration currentOpenDuration = DEFAULT_OPEN_DURATION;
    private boolean probeInFlight;

    @Autowired
    public AiCircuitBreaker(SettingsReader settings, AuditRecorder audit,
                            PlatformBankLookup platformBank) {
        this(() -> settings.getIntForPlatform(SettingKeys.AI_CIRCUIT_FAILURE_THRESHOLD),
                () -> settings.getDurationForPlatform(SettingKeys.AI_CIRCUIT_OPEN_DURATION),
                Clock.systemUTC(),
                opened -> auditOpened(opened, audit, platformBank));
    }

    public AiCircuitBreaker(IntSupplier failureThreshold, Supplier<Duration> openDuration,
                            Clock clock, Consumer<Opened> onOpened) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
        this.onOpened = onOpened;
    }

    /** A breaker that never opens — for callers built without one (unit tests). */
    public static AiCircuitBreaker disabled() {
        return new AiCircuitBreaker(() -> Integer.MAX_VALUE, () -> DEFAULT_OPEN_DURATION,
                Clock.systemUTC(), opened -> {
                });
    }

    /**
     * Whether a call would be let through now, without taking the half-open
     * trial slot — the parse stage's pre-check before it builds any prompt.
     */
    public synchronized boolean allow() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> !clock.instant().isBefore(openedAt.plus(currentOpenDuration));
            case HALF_OPEN -> !probeInFlight;
        };
    }

    /**
     * Takes permission for one call: true in CLOSED, true for the single
     * trial call once the open duration has passed, false otherwise. Every
     * granted call must report back through {@link #onSuccess()} or
     * {@link #onFailure(Throwable)}.
     */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.instant().isBefore(openedAt.plus(currentOpenDuration))) {
                    return false;
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                log.info("AI circuit half-open: letting one trial call through");
                return true;
            case HALF_OPEN:
            default:
                if (probeInFlight) {
                    return false;
                }
                probeInFlight = true;
                return true;
        }
    }

    /** The endpoint answered (successfully, or with a reply that does not count). */
    public synchronized void onSuccess() {
        if (state != State.CLOSED) {
            log.info("AI circuit closed: the trial call succeeded");
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
        openedAt = null;
    }

    /** A granted call failed with {@code error}; only counted failures move the breaker. */
    public void onFailure(Throwable error) {
        if (!AiFailureClassifier.countsForCircuit(error)) {
            onSuccess();
            return;
        }
        Opened opened = null;
        synchronized (this) {
            consecutiveFailures++;
            if (state == State.HALF_OPEN) {
                open();
                log.warn("AI circuit re-opened for {}: the trial call failed ({})",
                        currentOpenDuration, error.getClass().getSimpleName());
            } else if (state == State.CLOSED && consecutiveFailures >= threshold()) {
                open();
                opened = new Opened(consecutiveFailures, threshold(), currentOpenDuration,
                        error.getClass().getSimpleName());
                log.warn("AI circuit OPENED after {} consecutive transport/server failures "
                                + "(last: {}); AI calls are refused for {}",
                        consecutiveFailures, error.getClass().getSimpleName(), currentOpenDuration);
            }
        }
        if (opened != null) {
            try {
                onOpened.accept(opened);
            } catch (RuntimeException e) {
                log.warn("Could not record the AI circuit opening: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** Seconds until the open circuit admits its trial call (0 when it would now). */
    public synchronized long secondsUntilRetry() {
        if (state != State.OPEN || openedAt == null) {
            return 0;
        }
        Duration left = Duration.between(clock.instant(), openedAt.plus(currentOpenDuration));
        return left.isNegative() ? 0 : Math.max(1, (left.toMillis() + 999) / 1000);
    }

    public synchronized State state() {
        return state;
    }

    /** Back to CLOSED with no failures (tests and operators). */
    public synchronized void reset() {
        onSuccess();
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
        currentOpenDuration = duration();
        probeInFlight = false;
    }

    private int threshold() {
        try {
            return Math.max(1, failureThreshold.getAsInt());
        } catch (RuntimeException e) {
            return DEFAULT_FAILURE_THRESHOLD;
        }
    }

    private Duration duration() {
        try {
            Duration value = openDuration.get();
            return value == null || value.isNegative() || value.isZero() ? DEFAULT_OPEN_DURATION : value;
        } catch (RuntimeException e) {
            return DEFAULT_OPEN_DURATION;
        }
    }

    /**
     * {@code AI_CIRCUIT_OPENED} under the platform bank. The caller's own
     * tenant and identity are set aside for the write: the event belongs to
     * the platform (the endpoint is shared), and a customer bank's user id
     * must not end up on a platform-bank row.
     */
    static void auditOpened(Opened opened, AuditRecorder audit, PlatformBankLookup platformBank) {
        OptionalInt platform;
        try {
            platform = platformBank.platformBankId();
        } catch (RuntimeException e) {
            platform = OptionalInt.empty();
        }
        if (platform.isEmpty()) {
            log.warn("AI circuit opened; no platform bank exists yet, so AI_CIRCUIT_OPENED is "
                    + "not audited");
            return;
        }
        int bankId = platform.getAsInt();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("consecutiveFailures", opened.consecutiveFailures());
        values.put("threshold", opened.threshold());
        values.put("openSeconds", opened.openFor().toSeconds());
        values.put("lastFailure", opened.lastFailureType());
        AuditEventSpec spec = AuditEventSpec.builder("AI_CIRCUIT_OPENED", AuditOutcome.FAILED)
                .entity("AiCircuit", "gateway")
                .bankId(bankId)
                .newValues(values)
                .build();
        SecurityContext caller = SecurityContextHolder.getContext();
        try {
            SecurityContextHolder.clearContext();
            TenantContext.runAs(bankId, () -> audit.record(spec));
        } finally {
            SecurityContextHolder.setContext(caller);
        }
    }
}
