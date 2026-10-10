package com.modocument.application.port;

import java.math.BigDecimal;
import java.time.Duration;

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
}
