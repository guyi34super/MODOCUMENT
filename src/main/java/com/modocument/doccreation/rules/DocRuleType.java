package com.modocument.doccreation.rules;

import java.util.Locale;
import java.util.Optional;

/**
 * The rule types of document-creation fields
 * (docs/doccreation/field-validation-contract.md §3). Stored by NAME in
 * {@code DocFieldRule.RuleType} (never by ordinal), so the order may change.
 */
public enum DocRuleType {
    /** A value (or, for IMAGE/SIGNATURE, a placed photo) is present. Config {@code {}}. */
    REQUIRED,
    /** The value matches {@code {"Pattern": "..."}} (unanchored find, SafeRegex). */
    REGEX,
    /** The value is an e-mail address. Config {@code {}}. */
    EMAIL,
    /** Text length within {@code {"Min": n?, "Max": n?}}. */
    LENGTH,
    /** A number within {@code {"Min": n?, "Max": n?}}. */
    RANGE,
    /** One of {@code {"Values": [...], "IgnoreCase": bool?}}. */
    ALLOWED_VALUES,
    /** The value parses with one of {@code {"Formats": ["dd/MM/yyyy", ...]}} (default ISO). */
    DATE_FORMAT,
    /** A date within {@code {"Min": d?, "Max": d?}}: ISO dates or relative (today, +30d, -18y, +2m). */
    DATE_RANGE,
    /** A date after today. Config {@code {}}. */
    EXPIRY_DATE,
    /** Required when {@code {"Field": key, "Equals": value?}}: the other field is filled (or equals). */
    REQUIRED_IF,
    /** Compares with another field: {@code {"Field": key, "Op": "<|<=|=|!=|>=|>"}} (dates or numbers). */
    COMPARE;

    /** The type for a stored or posted name (case-insensitive), empty when unknown. */
    public static Optional<DocRuleType> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Rules that still apply to a blank value (all others pass a blank value). */
    public boolean checksBlank() {
        return this == REQUIRED || this == REQUIRED_IF;
    }
}
