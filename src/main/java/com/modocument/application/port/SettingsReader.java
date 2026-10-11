package com.modocument.application.port;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Typed, per-bank read access to the editable settings (ws20) for the code
 * that consumes them. Keys are {@code domain.model.settings.SettingKeys};
 * the full catalogue is docs/settings/catalogue.md.
 *
 * <p>Resolution: the bank's own override, else the platform owner's value,
 * else the shipped default (the application.yml property the setting maps
 * to), always inside the hard limits and the platform's bank bounds. Reads
 * hit an in-memory snapshot — never the database — so they are cheap enough
 * for every request. A PLATFORM-scope key resolves the same for every bank.
 *
 * <p>Asking for an unknown key, or with the wrong type, is a programming
 * error ({@link IllegalArgumentException}).
 *
 * <p><b>Whose bank?</b> A consumer that knows the bank it acts for (the
 * token owner on refresh, the user on a failed login, the bank a retention
 * sweep is purging) passes it explicitly. Code that serves the CALLER's
 * request uses the {@code ...ForCurrentBank} variants: the bank bound to this
 * thread ({@link TenantContext} — the JWT's bank on a request thread, the
 * job's bank on a worker), else the platform layer (startup/bootstrap work,
 * SYSTEM maintenance, anonymous requests).
 */
public interface SettingsReader {

    int getInt(String key, int bankId);

    boolean getBool(String key, int bankId);

    Duration getDuration(String key, int bankId);

    /** An ENUM setting's constant name. */
    String getEnum(String key, int bankId);

    BigDecimal getDecimal(String key, int bankId);

    /** The platform layer's value (platform value, else the shipped default). */
    int getIntForPlatform(String key);

    boolean getBoolForPlatform(String key);

    Duration getDurationForPlatform(String key);

    String getEnumForPlatform(String key);

    BigDecimal getDecimalForPlatform(String key);

    // ---- The caller's bank (TenantContext), else the platform layer -------

    /** {@link #getInt} for the bank bound to this thread, else the platform layer. */
    default int getIntForCurrentBank(String key) {
        Optional<Integer> bank = TenantContext.currentBankId();
        return bank.isPresent() ? getInt(key, bank.get()) : getIntForPlatform(key);
    }

    /** {@link #getBool} for the bank bound to this thread, else the platform layer. */
    default boolean getBoolForCurrentBank(String key) {
        Optional<Integer> bank = TenantContext.currentBankId();
        return bank.isPresent() ? getBool(key, bank.get()) : getBoolForPlatform(key);
    }

    /** {@link #getDuration} for the bank bound to this thread, else the platform layer. */
    default Duration getDurationForCurrentBank(String key) {
        Optional<Integer> bank = TenantContext.currentBankId();
        return bank.isPresent() ? getDuration(key, bank.get()) : getDurationForPlatform(key);
    }

    /** {@link #getEnum} for the bank bound to this thread, else the platform layer. */
    default String getEnumForCurrentBank(String key) {
        Optional<Integer> bank = TenantContext.currentBankId();
        return bank.isPresent() ? getEnum(key, bank.get()) : getEnumForPlatform(key);
    }

    /** {@link #getDecimal} for the bank bound to this thread, else the platform layer. */
    default BigDecimal getDecimalForCurrentBank(String key) {
        Optional<Integer> bank = TenantContext.currentBankId();
        return bank.isPresent() ? getDecimal(key, bank.get()) : getDecimalForPlatform(key);
    }
}
