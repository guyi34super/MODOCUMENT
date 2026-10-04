package com.modocument.infrastructure.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-key fixed-window counter: a key may spend up to {@code maxPermits}
 * permits within any {@code windowMillis} span; the count resets when the
 * window rolls over. In-memory and single-instance — a multi-instance
 * deployment would need a shared store (Redis) to enforce a global limit.
 *
 * <p>The map is bounded: when it grows past {@code maxKeys} the expired
 * windows are swept, so a flood of distinct keys (spoofed IPs, say) cannot
 * grow it without bound. Thread-safe: the window swap is a single atomic
 * {@code compute} and the counter is an {@link AtomicInteger}.
 */
public final class FixedWindowRateLimiter {

    private final int maxPermits;
    private final long windowMillis;
    private final int maxKeys;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int maxPermits, long windowMillis, int maxKeys) {
        if (maxPermits < 1 || windowMillis < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("maxPermits, windowMillis and maxKeys must be positive.");
        }
        this.maxPermits = maxPermits;
        this.windowMillis = windowMillis;
        this.maxKeys = maxKeys;
    }

    /**
     * Consumes one permit for {@code key} in the window covering {@code nowMillis}.
     *
     * @return true if the key was within its limit (permit granted), false if it
     *         has already spent its permits for the current window.
     */
    public boolean tryAcquire(String key, long nowMillis) {
        if (windows.size() > maxKeys) {
            windows.values().removeIf(w -> nowMillis - w.startMillis >= windowMillis);
        }
        Window window = windows.compute(key, (k, existing) ->
                existing == null || nowMillis - existing.startMillis >= windowMillis
                        ? new Window(nowMillis)
                        : existing);
        return window.count.incrementAndGet() <= maxPermits;
    }

    /**
     * Seconds until the window covering {@code nowMillis} rolls over for
     * {@code key} — what a 429 should advertise as {@code Retry-After}.
     * Rounded up and never below 1 (the header is a positive integer); a key
     * without a live window answers the full window, the conservative bound.
     */
    public long retryAfterSeconds(String key, long nowMillis) {
        Window window = windows.get(key);
        long remainingMillis = window == null
                ? windowMillis
                : window.startMillis + windowMillis - nowMillis;
        return Math.max(1, (remainingMillis + 999) / 1000);
    }

    private static final class Window {
        private final long startMillis;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
