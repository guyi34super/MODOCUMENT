package com.modocument.application.validation;

import java.util.Map;
import java.util.Optional;

/**
 * Example values for the well-known regex patterns the UI's pattern builder
 * offers as presets (frontend RegexBuilder PRESETS — the pattern strings here
 * mirror those verbatim, keyed by the literal pattern text stored in the rule
 * config). Lets validation errors show the user a concrete valid value.
 */
public final class RegexPatternExamples {

    private static final Map<String, String> EXAMPLES_BY_PATTERN = Map.of(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", "jane@acme.com",
            "^\\+?[0-9][0-9 ()-]{5,18}[0-9]$", "+1 416 555-0199",
            "^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$", "2026-07-18",
            "^\\d+$", "42",
            "^\\d{1,9}\\.\\d{2}$", "1250.00",
            "^[A-Za-z]+$", "Nairobi",
            "^[A-Za-z ]+$", "Mary Jane",
            "^[A-Za-z0-9]+$", "AB12CD",
            "^https?://[A-Za-z0-9.-]+(/\\S*)?$", "https://example.com/page");

    private RegexPatternExamples() {
    }

    /** A value matching the given pattern, when the pattern is a known preset. */
    public static Optional<String> exampleFor(String pattern) {
        return Optional.ofNullable(pattern).map(EXAMPLES_BY_PATTERN::get);
    }
}
