package com.modocument.domain.model;

/**
 * Kinds of validation rules. Persisted by ordinal to match the numeric
 * values the original .NET backend stored for this enum.
 */
public enum RuleType {
    CONDITIONAL_REQUIREMENT,
    DATE_FORMAT,
    EXPIRY_DATE,
    REQUIRED,
    REGEX,
    RANGE,
    TYPE,
    CUSTOM
}
