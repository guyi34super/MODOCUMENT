package com.modocument.agent.runtime;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential back-off with jitter for the poll loop: 1 s, 2 s, 4 s … up to
 * {@code max}, each delay drawn in [50 %, 100 %] of the step so a fleet of
 * agents does not reconnect in lockstep after an outage. {@link #reset()} on
 * the first success.
 */
public class Backoff {

    private final long maxMillis;
    private int failures;

    public Backoff(Duration max) {
        this.maxMillis = Math.max(1000, max.toMillis());
    }

    public synchronized long nextDelayMillis() {
        int exponent = Math.min(failures, 20);
        failures++;
        long step = Math.min(maxMillis, 1000L << exponent);
        return step / 2 + ThreadLocalRandom.current().nextLong(step / 2 + 1);
    }

    public synchronized void reset() {
        failures = 0;
    }

    public synchronized int failures() {
        return failures;
    }
}
