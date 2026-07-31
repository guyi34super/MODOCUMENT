package com.modocument.infrastructure.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-user quota for the LLM-backed endpoints. Each provider call costs money,
 * so an authenticated user must not be able to drain the account by looping a
 * request. Counts calls per user in a fixed window and throws
 * {@link TooManyRequestsException} (→ 429) once the quota is spent.
 *
 * <p>Single-instance/in-memory; a horizontally-scaled deployment would move the
 * counters to a shared store.
 */
@Component
public class LlmRateLimiter {

    private final FixedWindowRateLimiter perUser;

    public LlmRateLimiter(
            @Value("${modocument.security.rate-limit.llm.per-user-max:60}") int perUserMax,
            @Value("${modocument.security.rate-limit.llm.window-millis:3600000}") long windowMillis,
            @Value("${modocument.security.rate-limit.llm.max-keys:100000}") int maxKeys) {
        this.perUser = new FixedWindowRateLimiter(perUserMax, windowMillis, maxKeys);
    }

    /**
     * @throws TooManyRequestsException if the user has exhausted their AI quota
     *         for the current window.
     */
    public void check(int userId) {
        if (!perUser.tryAcquire("user:" + userId, System.currentTimeMillis())) {
            throw new TooManyRequestsException(
                    "You have reached the AI request limit for now. Please try again later.");
        }
    }
}
