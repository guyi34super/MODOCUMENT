package com.modocument.infrastructure.settings;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingScope;
import com.modocument.domain.model.settings.SettingSource;
import com.modocument.domain.model.settings.SettingType;
import com.modocument.domain.model.settings.SettingValues;
import com.modocument.infrastructure.settings.SettingsSnapshot.StoredSetting;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Effective setting values (ws20) — the {@link SettingsReader} the consumers
 * call, and the per-setting {@link Resolution} the settings API shows.
 *
 * <p><b>Bank layer</b> (a BANK-scope setting for a customer bank):
 * <ol>
 *   <li>the bank's own override, else</li>
 *   <li>the platform value (the platform bank's row), else</li>
 *   <li>the shipped default ({@link SettingsCatalogue#defaultValue}).</li>
 * </ol>
 * A stored value (1 or 2) is clamped into the effective range
 * {@code [max(hardMin, platform bankMin), min(hardMax, platform bankMax)]}.
 * The shipped default is clamped into the platform's bank bounds only: its
 * hard-limit check belongs to {@link SettingsStartupCheck} (WARN, or a
 * refused boot with guardrails enforced), so a dev/test profile that
 * deliberately configures outside the limits keeps its exact behaviour.
 * {@link Resolution#clamped()} reports any correction.
 *
 * <p><b>Platform layer</b> (PLATFORM-scope settings for everyone, and the
 * platform bank itself): the platform value clamped into the hard limits,
 * else the shipped default.
 *
 * <p>Everything reads the immutable {@link SettingsSnapshot} of
 * {@link SettingsCache} — no database access per read.
 */
@Component
public class SettingsResolver implements SettingsReader {

    private final SettingsCatalogue catalogue;
    private final SettingsCache cache;

    public SettingsResolver(SettingsCatalogue catalogue, SettingsCache cache) {
        this.catalogue = catalogue;
        this.cache = cache;
    }

    /** One setting as seen by one bank (the platform bank sees the platform layer). */
    public Resolution resolve(SettingDefinition definition, int bankId) {
        return resolve(definition, bankId, cache.current());
    }

    /** One setting at the platform layer. */
    public Resolution resolveForPlatform(SettingDefinition definition) {
        return resolveForPlatform(definition, cache.current());
    }

    /** {@link #resolve(SettingDefinition, int)} against an explicit snapshot. */
    public Resolution resolve(SettingDefinition definition, int bankId, SettingsSnapshot snapshot) {
        if (definition.scope() == SettingScope.PLATFORM || snapshot.isPlatformBank(bankId)) {
            return resolveForPlatform(definition, snapshot);
        }
        SettingType type = definition.type();
        StoredSetting platform = snapshot.platformRow(definition.key());
        StoredSetting own = snapshot.row(bankId, definition.key());
        int version = own == null ? 0 : own.version();

        Object bankMin = platform == null ? null : platform.bankMin();
        Object bankMax = platform == null ? null : platform.bankMax();
        Object lo = null;
        Object hi = null;
        if (type.isRanged()) {
            lo = SettingValues.maxOf(type, definition.hardMin(), bankMin);
            hi = SettingValues.minOf(type, definition.hardMax(), bankMax);
            if (SettingValues.compare(type, lo, hi) > 0) {
                // Bounds that contradict the (possibly tightened) hard limits
                // are ignored rather than producing an empty range.
                lo = definition.hardMin();
                hi = definition.hardMax();
                bankMin = null;
                bankMax = null;
            }
        }

        if (own != null && own.value() != null) {
            return clamped(definition, own.value(), lo, hi, SettingSource.BANK, version,
                    bankMin, bankMax);
        }
        if (platform != null && platform.value() != null) {
            return clamped(definition, platform.value(), lo, hi, SettingSource.PLATFORM, version,
                    bankMin, bankMax);
        }
        Object shipped = catalogue.defaultValue(definition);
        Object effective = SettingValues.clamp(type, shipped, bankMin, bankMax);
        return new Resolution(effective, SettingSource.DEFAULT, lo, hi,
                !SettingValues.same(type, shipped, effective), version, bankMin, bankMax);
    }

    /** {@link #resolveForPlatform(SettingDefinition)} against an explicit snapshot. */
    public Resolution resolveForPlatform(SettingDefinition definition, SettingsSnapshot snapshot) {
        StoredSetting platform = snapshot.platformRow(definition.key());
        int version = platform == null ? 0 : platform.version();
        Object bankMin = platform == null ? null : platform.bankMin();
        Object bankMax = platform == null ? null : platform.bankMax();
        if (platform != null && platform.value() != null) {
            return clamped(definition, platform.value(), definition.hardMin(),
                    definition.hardMax(), SettingSource.PLATFORM, version, bankMin, bankMax);
        }
        return new Resolution(catalogue.defaultValue(definition), SettingSource.DEFAULT,
                definition.hardMin(), definition.hardMax(), false, version, bankMin, bankMax);
    }

    // ------------------------------------------------------------ SettingsReader

    @Override
    public int getInt(String key, int bankId) {
        return (Integer) typed(key, SettingType.INT, bankId);
    }

    @Override
    public boolean getBool(String key, int bankId) {
        return (Boolean) typed(key, SettingType.BOOLEAN, bankId);
    }

    @Override
    public Duration getDuration(String key, int bankId) {
        return (Duration) typed(key, SettingType.DURATION, bankId);
    }

    @Override
    public String getEnum(String key, int bankId) {
        return (String) typed(key, SettingType.ENUM, bankId);
    }

    @Override
    public BigDecimal getDecimal(String key, int bankId) {
        return (BigDecimal) typed(key, SettingType.DECIMAL, bankId);
    }

    @Override
    public int getIntForPlatform(String key) {
        return (Integer) typed(key, SettingType.INT, null);
    }

    @Override
    public boolean getBoolForPlatform(String key) {
        return (Boolean) typed(key, SettingType.BOOLEAN, null);
    }

    @Override
    public Duration getDurationForPlatform(String key) {
        return (Duration) typed(key, SettingType.DURATION, null);
    }

    @Override
    public String getEnumForPlatform(String key) {
        return (String) typed(key, SettingType.ENUM, null);
    }

    @Override
    public BigDecimal getDecimalForPlatform(String key) {
        return (BigDecimal) typed(key, SettingType.DECIMAL, null);
    }

    private Object typed(String key, SettingType expected, Integer bankId) {
        SettingDefinition definition = catalogue.require(key);
        if (definition.type() != expected) {
            throw new IllegalArgumentException("Setting " + key + " is " + definition.type()
                    + ", not " + expected + ".");
        }
        return bankId == null
                ? resolveForPlatform(definition).value()
                : resolve(definition, bankId).value();
    }

    private static Resolution clamped(SettingDefinition definition, Object stored, Object lo,
                                      Object hi, SettingSource source, int version,
                                      Object bankMin, Object bankMax) {
        Object effective = SettingValues.clamp(definition.type(), stored, lo, hi);
        return new Resolution(effective, source, lo, hi,
                !SettingValues.same(definition.type(), stored, effective), version,
                bankMin, bankMax);
    }

    /**
     * An effective value with its provenance: {@code min}/{@code max} are the
     * effective range at the asked layer (null for BOOLEAN/ENUM), {@code version}
     * the stored row version at that layer (0 = no row), {@code bankMin}/
     * {@code bankMax} the platform's bank bounds (null = unset).
     */
    public record Resolution(Object value, SettingSource source, Object min, Object max,
                             boolean clamped, int version, Object bankMin, Object bankMax) {
    }
}
