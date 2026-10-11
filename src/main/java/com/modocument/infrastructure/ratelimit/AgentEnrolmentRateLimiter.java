package com.modocument.infrastructure.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Throttles the anonymous {@code POST /api/agent/v1/enroll} (ws26) per
 * client IP. The enrolment token carries 256 random bits, so guessing is
 * hopeless anyway; the limit keeps the endpoint from being a free
 * CPU/database load generator and makes probing visible as 429s. A real
 * agent enrols once; a retry loop after a network error stays far below
 * the default (10 per 15 minutes).
 *
 * <p>Single-instance/in-memory like {@link AuthRateLimiter}; every ECS task
 * counts on its own.
 */
@Component
public class AgentEnrolmentRateLimiter {

    private final FixedWindowRateLimiter perIp;

    public AgentEnrolmentRateLimiter(
            @Value("${modocument.agent.enrol-rate-limit.per-ip-max:10}") int perIpMax,
            @Value("${modocument.agent.enrol-rate-limit.window-millis:900000}") long windowMillis,
            @Value("${modocument.agent.enrol-rate-limit.max-keys:10000}") int maxKeys) {
        this.perIp = new FixedWindowRateLimiter(perIpMax, windowMillis, maxKeys);
    }

    /** @throws TooManyRequestsException when this address used up its window */
    public void check(String ip) {
        String key = "agent-enrol:" + (ip == null || ip.isBlank() ? "unknown" : ip);
        long now = System.currentTimeMillis();
        if (!perIp.tryAcquire(key, now)) {
            throw new TooManyRequestsException(
                    "Too many enrolment attempts from this network. Please wait and try again.",
                    perIp.retryAfterSeconds(key, now));
        }
    }
}
