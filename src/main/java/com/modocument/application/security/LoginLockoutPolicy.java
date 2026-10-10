package com.modocument.application.security;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Account lockout with exponential backoff (gate B4). The counters live on
 * AppUsers (V40): FailedLoginCount increments on every wrong password; the
 * attempt after the configured allowance (default 5, so the 6th consecutive
 * failure) sets LockedUntil. The lock duration starts at the base (1 minute)
 * and doubles with every additional lockout since the last successful login
 * (LockoutCount), capped at 30 minutes — a slow drip for a human typo, an
 * exponential wall for an online guesser. A successful login resets all
 * three columns.
 *
 * <p>This class only owns the arithmetic and the counter mutations; the
 * caller ({@code AuthUseCase.login}) persists the user, burns the
 * timing-equalizer hash and keeps the response the same generic 401 whether
 * the account is locked, unknown or the password wrong — lockout must not
 * become a user-enumeration oracle.
 *
 * <p><b>Per-bank values (ws20):</b> allowance, base and cap are the settings
 * {@code security.lockout.*} of the USER's bank, read on every failure
 * (allowance 3–10, base 1–60 min, cap 30–1440 min; a base above the cap
 * simply locks for the cap). With nothing stored they are the
 * {@code modocument.security.lockout.*} values.
 */
@Component
public class LoginLockoutPolicy {

    private final int allowedAttempts;
    private final long baseLockMinutes;
    private final long capLockMinutes;
    /** Null = the fixed yml values (unit-test wiring). */
    private final SettingsReader settings;

    public LoginLockoutPolicy(int allowedAttempts, long baseLockMinutes, long capLockMinutes) {
        this(allowedAttempts, baseLockMinutes, capLockMinutes, null);
    }

    @Autowired
    public LoginLockoutPolicy(
            @Value("${modocument.security.lockout.allowed-attempts:5}") int allowedAttempts,
            @Value("${modocument.security.lockout.base-minutes:1}") long baseLockMinutes,
            @Value("${modocument.security.lockout.cap-minutes:30}") long capLockMinutes,
            SettingsReader settings) {
        if (allowedAttempts < 1 || baseLockMinutes < 1 || capLockMinutes < baseLockMinutes) {
            throw new IllegalStateException(
                    "Lockout settings must be positive and cap-minutes >= base-minutes.");
        }
        this.allowedAttempts = allowedAttempts;
        this.baseLockMinutes = baseLockMinutes;
        this.capLockMinutes = capLockMinutes;
        this.settings = settings;
    }

    /** Wrong passwords a bank's users may enter before the next one locks. */
    public int allowedAttempts(int bankId) {
        return settings == null ? allowedAttempts
                : settings.getInt(SettingKeys.LOCKOUT_ALLOWED_ATTEMPTS, bankId);
    }

    /** First lock duration for a bank's users, in minutes. */
    public long baseLockMinutes(int bankId) {
        return settings == null ? baseLockMinutes
                : settings.getInt(SettingKeys.LOCKOUT_BASE_MINUTES, bankId);
    }

    /** Longest lock duration for a bank's users, in minutes. */
    public long capLockMinutes(int bankId) {
        return settings == null ? capLockMinutes
                : settings.getInt(SettingKeys.LOCKOUT_CAP_MINUTES, bankId);
    }

    /** Checked BEFORE the password is verified. */
    public boolean isLocked(AppUser user, LocalDateTime now) {
        return user.getLockedUntil() != null && now.isBefore(user.getLockedUntil());
    }

    /**
     * Registers one wrong-password attempt, mutating the user's counters
     * (the caller saves the entity).
     *
     * @return true when THIS failure crossed the threshold and locked the
     *     account (the "first lock moment" — the caller audits ACCOUNT_LOCKED)
     */
    public boolean registerFailure(AppUser user, LocalDateTime now) {
        int bankId = user.getBankId();
        int failures = user.getFailedLoginCount() + 1;
        if (failures <= allowedAttempts(bankId)) {
            user.setFailedLoginCount(failures);
            return false;
        }
        // Threshold crossed: lock, escalate, and restart the failure count so
        // the next lock again takes a full run of fresh failures.
        long backoffMinutes = backoffMinutes(user.getLockoutCount(),
                baseLockMinutes(bankId), capLockMinutes(bankId));
        user.setLockedUntil(now.plusMinutes(backoffMinutes));
        user.setLockoutCount(user.getLockoutCount() + 1);
        user.setFailedLoginCount(0);
        return true;
    }

    /** A correct password (with no lock in force) clears every counter. */
    public void registerSuccess(AppUser user) {
        user.setFailedLoginCount(0);
        user.setLockoutCount(0);
        user.setLockedUntil(null);
    }

    /** base * 2^priorLockouts, capped — 1, 2, 4, 8, 16, 30, 30, ... minutes (yml values). */
    long backoffMinutes(int priorLockouts) {
        return backoffMinutes(priorLockouts, baseLockMinutes, capLockMinutes);
    }

    static long backoffMinutes(int priorLockouts, long baseLockMinutes, long capLockMinutes) {
        long minutes = baseLockMinutes;
        for (int i = 0; i < priorLockouts && minutes < capLockMinutes; i++) {
            minutes *= 2;
        }
        return Math.min(minutes, capLockMinutes);
    }
}
