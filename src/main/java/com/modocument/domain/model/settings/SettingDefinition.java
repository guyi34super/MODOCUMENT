package com.modocument.domain.model.settings;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One entry of the settings catalogue (ws20): what a setting is, who may
 * change it and within which guardrails. The catalogue is CODE
 * ({@code infrastructure/settings/SettingDefinitions}); only changed values
 * live in the database.
 *
 * <ul>
 *   <li>{@code key} — the stable API/storage key ({@link SettingKeys});</li>
 *   <li>{@code propertyName} — the existing Spring property whose value is
 *       the shipped default (read at startup, so env overrides keep
 *       working); null for settings whose consumer does not exist yet. A
 *       {@code [rule-name]} list selector (e.g.
 *       {@code modocument.alerts.rules[login-failures-per-user].threshold})
 *       addresses a list entry by its {@code name} instead of its index;</li>
 *   <li>{@code propertyUnit} — the unit of a bare-number duration property
 *       (e.g. MILLIS for {@code window-millis}); null otherwise;</li>
 *   <li>{@code codeDefault} — the shipped default, used when the property is
 *       absent (and pinned against application.yml by a test);</li>
 *   <li>{@code hardMin}/{@code hardMax} (ranged types) or
 *       {@code allowedValues} (BOOLEAN/ENUM) — the guardrails nobody can
 *       override; a single allowed value LOCKS the setting;</li>
 *   <li>{@code platformControlled} — a BANK-scope setting whose per-bank
 *       value only the PLATFORM owner may set: each bank can carry its own
 *       value (an override row in that bank), but it is written through the
 *       platform's per-bank settings ({@code /api/platform/banks/{id}/settings},
 *       maker/checker PLATFORM_SETTING_UPDATE) — the bank's administrators
 *       see it read-only. Without an override the bank inherits the platform
 *       value, exactly like any other BANK-scope setting.</li>
 * </ul>
 * Typed values follow {@link SettingType}: Integer, BigDecimal, Boolean,
 * String, Duration.
 */
public record SettingDefinition(String key, SettingType type, SettingScope scope, SettingTier tier,
                                SettingGroup group, String label, String description, String unit,
                                String propertyName, ChronoUnit propertyUnit, Object codeDefault,
                                Object hardMin, Object hardMax, List<String> allowedValues,
                                boolean requiresRestart, boolean platformControlled) {

    /** Keys are dotted camelCase, short enough for AuditEvent.EntityId (64). */
    private static final Pattern KEY_SHAPE =
            Pattern.compile("^[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+$");
    public static final int MAX_KEY_LENGTH = 64;

    public SettingDefinition {
        Objects.requireNonNull(key, "key");
        if (key.length() > MAX_KEY_LENGTH || !KEY_SHAPE.matcher(key).matches()) {
            throw new IllegalArgumentException("Bad setting key '" + key + "'");
        }
        Objects.requireNonNull(type, key + ": type");
        Objects.requireNonNull(scope, key + ": scope");
        Objects.requireNonNull(tier, key + ": tier");
        Objects.requireNonNull(group, key + ": group");
        Objects.requireNonNull(label, key + ": label");
        description = description == null ? "" : description;
        Objects.requireNonNull(codeDefault, key + ": codeDefault");
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        if (type.isRanged()) {
            if (hardMin == null || hardMax == null) {
                throw new IllegalArgumentException(key + ": a ranged setting needs hard limits");
            }
            if (!allowedValues.isEmpty()) {
                throw new IllegalArgumentException(key + ": a ranged setting has no allowed list");
            }
            if (SettingValues.compare(type, hardMin, hardMax) > 0) {
                throw new IllegalArgumentException(key + ": hardMin > hardMax");
            }
        } else {
            if (hardMin != null || hardMax != null) {
                throw new IllegalArgumentException(key + ": only ranged settings have limits");
            }
            if (allowedValues.isEmpty()) {
                throw new IllegalArgumentException(key + ": allowed values are required");
            }
        }
        if (platformControlled && scope != SettingScope.BANK) {
            throw new IllegalArgumentException(key + ": only a BANK-scope setting can be "
                    + "platform-controlled per bank");
        }
        if (propertyUnit != null && type != SettingType.DURATION) {
            throw new IllegalArgumentException(key + ": propertyUnit is for durations only");
        }
        if (!isWithinLimits(type, codeDefault, hardMin, hardMax, allowedValues)) {
            throw new IllegalArgumentException(key + ": the code default "
                    + SettingValues.format(type, codeDefault) + " violates its own limits");
        }
    }

    /** A setting with exactly one allowed value: shown, never changeable. */
    public boolean locked() {
        if (type.isRanged()) {
            return SettingValues.compare(type, hardMin, hardMax) == 0;
        }
        return allowedValues.size() == 1;
    }

    /** Ranged settings of BANK scope carry platform bank bounds. */
    public boolean hasBankBounds() {
        return scope == SettingScope.BANK && type.isRanged();
    }

    /** Parses (and, for ENUM, canonicalizes) user or stored text; no limit check. */
    public Object parse(String text) {
        Object value = SettingValues.parse(type, text);
        if (type == SettingType.ENUM) {
            String candidate = (String) value;
            for (String allowed : allowedValues) {
                if (allowed.equalsIgnoreCase(candidate)) {
                    return allowed;
                }
            }
        }
        return value;
    }

    /** The canonical text of a value of this setting. */
    public String format(Object value) {
        return SettingValues.format(type, value);
    }

    /** Whether a value respects the hard limits / allowed values. */
    public boolean isWithinHardLimits(Object value) {
        return isWithinLimits(type, value, hardMin, hardMax, allowedValues);
    }

    /** The allowed values as the API shows them (null for ranged settings). */
    public List<String> allowedForDisplay() {
        return type.isRanged() ? null : allowedValues;
    }

    private static boolean isWithinLimits(SettingType type, Object value, Object min, Object max,
                                          List<String> allowed) {
        if (value == null) {
            return false;
        }
        if (type.isRanged()) {
            return SettingValues.compare(type, value, min) >= 0
                    && SettingValues.compare(type, value, max) <= 0;
        }
        return allowed.contains(SettingValues.format(type, value));
    }

    /** Fluent construction for the catalogue (values given as canonical text). */
    public static Builder builder(String key, SettingType type) {
        return new Builder(key, type);
    }

    /** Builder for {@link SettingDefinition}; limits and defaults are parsed by type. */
    public static final class Builder {

        private final String key;
        private final SettingType type;
        private SettingScope scope = SettingScope.BANK;
        private SettingTier tier = SettingTier.OPERATIONAL;
        private SettingGroup group;
        private String label;
        private String description;
        private String unit;
        private String propertyName;
        private ChronoUnit propertyUnit;
        private String codeDefault;
        private String hardMin;
        private String hardMax;
        private final List<String> allowed = new ArrayList<>();
        private boolean requiresRestart;
        private boolean platformControlled;

        private Builder(String key, SettingType type) {
            this.key = key;
            this.type = type;
        }

        public Builder platform() {
            this.scope = SettingScope.PLATFORM;
            return this;
        }

        public Builder bank() {
            this.scope = SettingScope.BANK;
            return this;
        }

        public Builder security() {
            this.tier = SettingTier.SECURITY;
            return this;
        }

        public Builder operational() {
            this.tier = SettingTier.OPERATIONAL;
            return this;
        }

        public Builder group(SettingGroup group) {
            this.group = group;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder unit(String unit) {
            this.unit = unit;
            return this;
        }

        /** The Spring property holding the shipped default. */
        public Builder property(String propertyName) {
            this.propertyName = propertyName;
            return this;
        }

        /** A bare-number duration property in this unit (e.g. MILLIS). */
        public Builder property(String propertyName, ChronoUnit bareNumberUnit) {
            this.propertyName = propertyName;
            this.propertyUnit = bareNumberUnit;
            return this;
        }

        public Builder defaultValue(String canonical) {
            this.codeDefault = canonical;
            return this;
        }

        public Builder range(String min, String max) {
            this.hardMin = min;
            this.hardMax = max;
            return this;
        }

        public Builder allowed(String... values) {
            this.allowed.addAll(List.of(values));
            return this;
        }

        /** Locked: the one allowed value is also the default. */
        public Builder locked(String value) {
            this.allowed.clear();
            this.allowed.add(value);
            this.codeDefault = value;
            return this;
        }

        public Builder requiresRestart() {
            this.requiresRestart = true;
            return this;
        }

        /**
         * A BANK-scope setting whose per-bank value only the platform owner
         * sets (the bank sees it read-only); implies {@link #bank()}.
         */
        public Builder platformControlled() {
            this.scope = SettingScope.BANK;
            this.platformControlled = true;
            return this;
        }

        public SettingDefinition build() {
            List<String> allowedValues = new ArrayList<>(allowed);
            if (type == SettingType.BOOLEAN && allowedValues.isEmpty()) {
                allowedValues.add("true");
                allowedValues.add("false");
            }
            // Canonical text ("TRUE" -> "true", " INFO " -> "INFO").
            allowedValues.replaceAll(value ->
                    SettingValues.format(type, SettingValues.parse(type, value)));
            Object min = hardMin == null ? null : SettingValues.parse(type, hardMin);
            Object max = hardMax == null ? null : SettingValues.parse(type, hardMax);
            Object dflt = codeDefault == null ? null : SettingValues.parse(type, codeDefault);
            if (dflt instanceof String text && type == SettingType.ENUM) {
                for (String candidate : allowedValues) {
                    if (candidate.equalsIgnoreCase(text)) {
                        dflt = candidate;
                    }
                }
            }
            return new SettingDefinition(key, type, scope, tier, group, label, description, unit,
                    propertyName, propertyUnit, dflt, min, max, allowedValues, requiresRestart,
                    platformControlled);
        }
    }
}
