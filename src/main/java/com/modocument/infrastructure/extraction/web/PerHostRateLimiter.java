package com.modocument.infrastructure.extraction.web;

import com.modocument.application.port.HostRateLimiter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Politeness limiter: serializes requests per host and enforces a minimum
 * interval between them (the max of robots crawl-delay, the configured
 * default, and any per-source delay override).
 */
@Component
public class PerHostRateLimiter implements HostRateLimiter {

    private static class HostSlot {
        long nextAllowedAt;
    }

    private final Map<String, HostSlot> slots = new ConcurrentHashMap<>();

    @Override
    public void acquire(String host, long minIntervalMillis) {
        HostSlot slot = slots.computeIfAbsent(host, h -> new HostSlot());
        long waitMillis;
        synchronized (slot) {
            long now = System.currentTimeMillis();
            long start = Math.max(now, slot.nextAllowedAt);
            slot.nextAllowedAt = start + Math.max(0, minIntervalMillis);
            waitMillis = start - now;
        }
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while rate limiting.", e);
            }
        }
    }
}
