package com.modocument.domain.model.settings;

/**
 * The value type of a setting (ws20). Stored and transported as canonical
 * text (see {@link SettingValues}); typed in memory as {@link Integer},
 * {@link java.math.BigDecimal}, {@link Boolean}, {@link String} (ENUM) and
 * {@link java.time.Duration}.
 */
public enum SettingType {
    INT,
    DECIMAL,
    BOOLEAN,
    ENUM,
    DURATION;

    /** Whether values of this type are ordered and bounded by min/max. */
    public boolean isRanged() {
        return this == INT || this == DECIMAL || this == DURATION;
    }
}
