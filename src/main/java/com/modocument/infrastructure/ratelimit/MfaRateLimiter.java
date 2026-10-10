package com.modocument.infrastructure.ratelimit;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Throttles POST /api/auth/mfa/verify. A 6-digit TOTP space is only a
 * million codes and the ±1-step window accepts three of them at any moment;
 * without a hard cap the public verify endpoint would be a brute-forceable
 * oracle during the challenge's 5-minute life. Capped two ways (same
 * reasoning as {@link AuthRateLimiter}), backed by
 * {@link FixedWindowRateLimiter}; over the limit throws
 * {@link TooManyRequestsException} (→ 429) before any code is checked:
 * <ul>
 *   <li><b>per user</b> (the challenge subject) — 5 attempts per minute: THE
 *       brute-force control. One account's code can be guessed only through
 *       its own challenge, so this limit bounds every attack on it
 *       (≤ 25 guesses over a challenge's life, ~7.5e-5 success odds) however
 *       many addresses the attacker uses;</li>
 *   <li><b>per IP</b> — 30 attempts per minute (QA finding OBS-04): only a
 *       flood guard. A bank's staff sign in from behind one NAT address,
 *       and at 5/min colleagues finishing their sign-in in the same minute
 *       locked each other out. Each attempt also needs a challenge, which
 *       needs the account's password, so a higher per-address ceiling buys
 *       an attacker nothing the per-user limit does not already bound.</li>
 * </ul>
 *
 * <p><b>Editable limits (ws20):</b> both counts are PLATFORM settings
 * ({@code ratelimit.mfa.perIpMax} 1–120, {@code ratelimit.mfa.perUserMax}
 * 1–20) read per call — verify runs before a session (and so a bank)
 * exists. With nothing stored they are the
 * {@code modocument.security.rate-limit.mfa.*} values; window and key bound
 * stay yml-only.
 */
@Component
public class MfaRateLimiter {

    private final FixedWindowRateLimiter perIp;
    private final FixedWindowRateLimiter perUser;
    /** Null = the yml limits only (unit-test wiring). */
    private final SettingsReader settings;

    public MfaRateLimiter(int perIpMax, int perUserMax, long windowMillis, int maxKeys) {
        this(perIpMax, perUserMax, windowMillis, maxKeys, null);
    }

    @Autowired
    public MfaRateLimiter(
            @Value("${modocument.security.rate-limit.mfa.per-ip-max:30}") int perIpMax,
            @Value("${modocument.security.rate-limit.mfa.per-user-max:5}") int perUserMax,
            @Value("${modocument.security.rate-limit.mfa.window-millis:60000}") long windowMillis,
            @Value("${modocument.security.rate-limit.mfa.max-keys:50000}") int maxKeys,
            SettingsReader settings) {
        this.perIp = new FixedWindowRateLimiter(perIpMax, windowMillis, maxKeys);
        this.perUser = new FixedWindowRateLimiter(perUserMax, windowMillis, maxKeys);
        this.settings = settings;
    }

    /** Per-IP gate, cheap and first — runs before the challenge is parsed. */
    public void checkIp(String ip) {
        long now = System.currentTimeMillis();
        if (ip != null && !ip.isBlank() && !perIp.tryAcquire("ip:" + ip, now, perIpMax())) {
            throw new TooManyRequestsException(
                    "Too many attempts from this network. Please wait a minute and try again.",
                    perIp.retryAfterSeconds("ip:" + ip, now));
        }
    }

    /** Per-user gate — runs once the challenge named its subject. */
    public void checkUser(String userId) {
        long now = System.currentTimeMillis();
        if (userId != null && !userId.isBlank()
                && !perUser.tryAcquire("user:" + userId, now, perUserMax())) {
            throw new TooManyRequestsException(
                    "Too many attempts for this account. Please wait a minute and try again.",
                    perUser.retryAfterSeconds("user:" + userId, now));
        }
    }

    /** Verify attempts per network address and window, in force now. */
    public int perIpMax() {
        return settings == null ? perIp.maxPermits()
                : settings.getIntForPlatform(SettingKeys.RATELIMIT_MFA_PER_IP);
    }

    /** Verify attempts per user and window, in force now. */
    public int perUserMax() {
        return settings == null ? perUser.maxPermits()
                : settings.getIntForPlatform(SettingKeys.RATELIMIT_MFA_PER_USER);
    }
}
