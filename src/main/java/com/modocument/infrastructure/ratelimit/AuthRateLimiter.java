package com.modocument.infrastructure.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Throttles the open authentication endpoints. Without this, {@code /login} is
 * an unmetered password-guessing oracle and {@code /register} an unmetered
 * account-enumeration oracle. Attempts are counted two ways so neither a single
 * host hammering many accounts nor many hosts hammering one account gets
 * through: a per-IP window and a per-email window, each backed by a
 * {@link FixedWindowRateLimiter}. Over the limit throws
 * {@link TooManyRequestsException} (→ 429) before any credential work runs.
 *
 * <p>Single-instance/in-memory; a horizontally-scaled deployment would move the
 * counters to a shared store.
 */
@Component
public class AuthRateLimiter {

    private final FixedWindowRateLimiter perIp;
    private final FixedWindowRateLimiter perEmail;

    public AuthRateLimiter(
            @Value("${modocument.security.rate-limit.auth.per-ip-max:30}") int perIpMax,
            @Value("${modocument.security.rate-limit.auth.per-email-max:10}") int perEmailMax,
            @Value("${modocument.security.rate-limit.auth.window-millis:900000}") long windowMillis,
            @Value("${modocument.security.rate-limit.auth.max-keys:50000}") int maxKeys) {
        this.perIp = new FixedWindowRateLimiter(perIpMax, windowMillis, maxKeys);
        this.perEmail = new FixedWindowRateLimiter(perEmailMax, windowMillis, maxKeys);
    }

    /**
     * @throws TooManyRequestsException if this IP or this email has made too
     *         many attempts in the current window.
     */
    public void check(String ip, String email) {
        long now = System.currentTimeMillis();
        if (ip != null && !ip.isBlank() && !perIp.tryAcquire("ip:" + ip, now)) {
            throw new TooManyRequestsException(
                    "Too many attempts from this network. Please wait a few minutes and try again.");
        }
        if (email != null && !email.isBlank()
                && !perEmail.tryAcquire("email:" + email.toLowerCase(Locale.ROOT), now)) {
            throw new TooManyRequestsException(
                    "Too many attempts for this account. Please wait a few minutes and try again.");
        }
    }
}
