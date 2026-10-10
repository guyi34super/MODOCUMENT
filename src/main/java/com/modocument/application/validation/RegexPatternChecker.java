package com.modocument.application.validation;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compile-time check for UI-supplied regex patterns. Runtime ReDoS protection
 * is separate: RegexRule matches through {@link SafeRegex}'s 200 ms deadline,
 * and extraction-hint patterns are vetted further by {@link SafeRegex#check}.
 */
public final class RegexPatternChecker {

    /** Generous cap; UI-supplied field patterns are typically far shorter. */
    public static final int MAX_PATTERN_LENGTH = 2000;

    private RegexPatternChecker() {
    }

    /** Returns null when the pattern is acceptable, otherwise a human-readable problem. */
    public static String check(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "Pattern must not be empty.";
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            return "Pattern exceeds the maximum length of " + MAX_PATTERN_LENGTH + " characters.";
        }
        try {
            Pattern.compile(pattern);
            return null;
        } catch (PatternSyntaxException e) {
            return "Invalid regular expression near index " + e.getIndex() + ": " + e.getDescription();
        }
    }
}
