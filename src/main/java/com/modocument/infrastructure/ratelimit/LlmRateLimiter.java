package com.modocument.infrastructure.ratelimit;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-user quota for the LLM-backed endpoints. Each provider call costs money,
 * so an authenticated user must not be able to drain the account by looping a
 * request. Counts calls per user in a fixed window and throws
 * {@link TooManyRequestsException} (→ 429) once the quota is spent.
 *
 * <p><b>Per-bank quota (ws20):</b> the permit count is the setting
 * {@code ratelimit.llm.perUserMax} of the user's bank, read per call (with
 * nothing stored: {@code modocument.security.rate-limit.llm.per-user-max}).
 * Window and key bound stay yml-only. {@link #check(int)} uses the bank
 * bound to the thread ({@code TenantContext}, else the platform layer);
 * background work that knows the job's bank calls {@link #check(int, int)}
 * or the non-throwing {@link #tryCheck(int, int)} (code fallback instead of
 * a 429).
 *
 * <p>Single-instance/in-memory; a horizontally-scaled deployment would move the
 * counters to a shared store.
 */
@Component
public class LlmRateLimiter {

    private static final String QUOTA_SPENT =
            "You have reached the AI request limit for now. Please try again later.";

    private final FixedWindowRateLimiter perUser;
    /** Null = the yml quota only (unit-test wiring). */
    private final SettingsReader settings;

    public LlmRateLimiter(int perUserMax, long windowMillis, int maxKeys) {
        this(perUserMax, windowMillis, maxKeys, null);
    }

    @Autowired
    public LlmRateLimiter(
            @Value("${modocument.security.rate-limit.llm.per-user-max:60}") int perUserMax,
            @Value("${modocument.security.rate-limit.llm.window-millis:3600000}") long windowMillis,
            @Value("${modocument.security.rate-limit.llm.max-keys:100000}") int maxKeys,
            SettingsReader settings) {
        this.perUser = new FixedWindowRateLimiter(perUserMax, windowMillis, maxKeys);
        this.settings = settings;
    }

    /**
     * Spends one AI call of the user's quota, for the bank bound to this
     * thread (the platform layer when none is).
     *
     * @throws TooManyRequestsException if the user has exhausted their AI quota
     *         for the current window.
     */
    public void check(int userId) {
        if (!tryCheck(userId)) {
            throw quotaSpent(userId);
        }
    }

    /**
     * {@link #check(int)} against an explicit bank's quota.
     *
     * @throws TooManyRequestsException if the quota is spent
     */
    public void check(int userId, int bankId) {
        if (!tryCheck(userId, bankId)) {
            throw quotaSpent(userId);
        }
    }

    /** Spends one AI call if the quota allows it; false (nothing thrown) when spent. */
    public boolean tryCheck(int userId) {
        return perUser.tryAcquire(key(userId), System.currentTimeMillis(), currentLimit());
    }

    /** {@link #tryCheck(int)} against an explicit bank's quota. */
    public boolean tryCheck(int userId, int bankId) {
        return perUser.tryAcquire(key(userId), System.currentTimeMillis(), limitFor(bankId));
    }

    /** The AI calls per user and window in force for a bank's users. */
    public int limitFor(int bankId) {
        return settings == null ? perUser.maxPermits()
                : settings.getInt(SettingKeys.RATELIMIT_LLM_PER_USER, bankId);
    }

    private int currentLimit() {
        return settings == null ? perUser.maxPermits()
                : settings.getIntForCurrentBank(SettingKeys.RATELIMIT_LLM_PER_USER);
    }

    private TooManyRequestsException quotaSpent(int userId) {
        return new TooManyRequestsException(QUOTA_SPENT,
                perUser.retryAfterSeconds(key(userId), System.currentTimeMillis()));
    }

    private static String key(int userId) {
        return "user:" + userId;
    }
}
