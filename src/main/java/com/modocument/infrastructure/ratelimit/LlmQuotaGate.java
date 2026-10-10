package com.modocument.infrastructure.ratelimit;

import org.springframework.stereotype.Component;

/**
 * Non-throwing access to the per-user LLM quota for background AI work
 * (ws23): the ingestion parse stage and the legacy transform spend one AI
 * request per document from the same {@link LlmRateLimiter} buckets the
 * metered endpoints use, and fall back to code (reason QUOTA) instead of
 * failing when the quota is spent.
 *
 * <p>Merge note: ws20 adds a per-bank {@code LlmRateLimiter.tryCheck(userId,
 * bankId)}; this adapter is the one place to switch over to it.
 */
@Component
public class LlmQuotaGate {

    private final LlmRateLimiter limiter;

    public LlmQuotaGate(LlmRateLimiter limiter) {
        this.limiter = limiter;
    }

    /**
     * Spends one AI request from the user's quota.
     *
     * @param bankId the user's bank (for the per-bank quota setting; may be null)
     * @return false — nothing thrown — when the quota for the window is spent
     */
    public boolean tryAcquire(int userId, Integer bankId) {
        try {
            limiter.check(userId);
            return true;
        } catch (TooManyRequestsException exhausted) {
            return false;
        }
    }
}
