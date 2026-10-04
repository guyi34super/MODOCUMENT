package com.modocument.infrastructure.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Throttles POST /api/auth/mfa/verify. A 6-digit TOTP space is only a
 * million codes and the ±1-step window accepts three of them at any moment;
 * without a hard cap the public verify endpoint would be a brute-forceable
 * oracle during the challenge's 5-minute life. Capped two ways (same
 * reasoning as {@link AuthRateLimiter}): per IP and per challenge subject
 * (user id), each 5 attempts per minute by default, backed by
 * {@link FixedWindowRateLimiter}. Over the limit throws
 * {@link TooManyRequestsException} (→ 429) before any code is checked.
 */
@Component
public class MfaRateLimiter {

    private final FixedWindowRateLimiter perIp;
    private final FixedWindowRateLimiter perUser;

    public MfaRateLimiter(
            @Value("${modocument.security.rate-limit.mfa.per-ip-max:5}") int perIpMax,
            @Value("${modocument.security.rate-limit.mfa.per-user-max:5}") int perUserMax,
            @Value("${modocument.security.rate-limit.mfa.window-millis:60000}") long windowMillis,
            @Value("${modocument.security.rate-limit.mfa.max-keys:50000}") int maxKeys) {
        this.perIp = new FixedWindowRateLimiter(perIpMax, windowMillis, maxKeys);
        this.perUser = new FixedWindowRateLimiter(perUserMax, windowMillis, maxKeys);
    }

    /** Per-IP gate, cheap and first — runs before the challenge is parsed. */
    public void checkIp(String ip) {
        long now = System.currentTimeMillis();
        if (ip != null && !ip.isBlank() && !perIp.tryAcquire("ip:" + ip, now)) {
            throw new TooManyRequestsException(
                    "Too many attempts from this network. Please wait a minute and try again.");
        }
    }

    /** Per-user gate — runs once the challenge named its subject. */
    public void checkUser(String userId) {
        long now = System.currentTimeMillis();
        if (userId != null && !userId.isBlank()
                && !perUser.tryAcquire("user:" + userId, now)) {
            throw new TooManyRequestsException(
                    "Too many attempts for this account. Please wait a minute and try again.");
        }
    }
}
