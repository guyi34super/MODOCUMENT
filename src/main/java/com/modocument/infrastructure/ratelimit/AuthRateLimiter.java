package com.modocument.infrastructure.ratelimit;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p><b>Editable limits (ws20):</b> both permit counts are PLATFORM settings
 * ({@code ratelimit.auth.perIpMax}, {@code ratelimit.auth.perEmailMax}) read
 * per call — the caller is anonymous here, so no bank applies. With nothing
 * stored they are the {@code modocument.security.rate-limit.auth.*} values;
 * window and key bound stay yml-only.
 *
 * <p>Single-instance/in-memory; a horizontally-scaled deployment would move the
 * counters to a shared store.
 */
@Component
public class AuthRateLimiter {

    private final FixedWindowRateLimiter perIp;
    private final FixedWindowRateLimiter perEmail;
    /** Null = the yml limits only (unit-test wiring). */
    private final SettingsReader settings;

    public AuthRateLimiter(int perIpMax, int perEmailMax, long windowMillis, int maxKeys) {
        this(perIpMax, perEmailMax, windowMillis, maxKeys, null);
    }

    @Autowired
    public AuthRateLimiter(
            @Value("${modocument.security.rate-limit.auth.per-ip-max:30}") int perIpMax,
            @Value("${modocument.security.rate-limit.auth.per-email-max:10}") int perEmailMax,
            @Value("${modocument.security.rate-limit.auth.window-millis:900000}") long windowMillis,
            @Value("${modocument.security.rate-limit.auth.max-keys:50000}") int maxKeys,
            SettingsReader settings) {
        this.perIp = new FixedWindowRateLimiter(perIpMax, windowMillis, maxKeys);
        this.perEmail = new FixedWindowRateLimiter(perEmailMax, windowMillis, maxKeys);
        this.settings = settings;
    }

    /**
     * @throws TooManyRequestsException if this IP or this email has made too
     *         many attempts in the current window.
     */
    public void check(String ip, String email) {
        long now = System.currentTimeMillis();
        if (ip != null && !ip.isBlank() && !perIp.tryAcquire("ip:" + ip, now, perIpMax())) {
            throw new TooManyRequestsException(
                    "Too many attempts from this network. Please wait a few minutes and try again.",
                    perIp.retryAfterSeconds("ip:" + ip, now));
        }
        String emailKey = email == null ? null : "email:" + email.toLowerCase(Locale.ROOT);
        if (emailKey != null && !email.isBlank()
                && !perEmail.tryAcquire(emailKey, now, perEmailMax())) {
            throw new TooManyRequestsException(
                    "Too many attempts for this account. Please wait a few minutes and try again.",
                    perEmail.retryAfterSeconds(emailKey, now));
        }
    }

    /** Sign-in attempts per network address and window, in force now. */
    public int perIpMax() {
        return settings == null ? perIp.maxPermits()
                : settings.getIntForPlatform(SettingKeys.RATELIMIT_AUTH_PER_IP);
    }

    /** Sign-in attempts per e-mail address and window, in force now. */
    public int perEmailMax() {
        return settings == null ? perEmail.maxPermits()
                : settings.getIntForPlatform(SettingKeys.RATELIMIT_AUTH_PER_EMAIL);
    }
}
