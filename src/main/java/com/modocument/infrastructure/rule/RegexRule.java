package com.modocument.infrastructure.rule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.ValidationRuleValidator;
import com.modocument.application.validation.RegexValidationErrorMessages;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
public class RegexRule implements ValidationRuleValidator {

    /** Matching is aborted once it exceeds this budget, like the original 200 ms regex timeout. */
    static final long MATCH_TIMEOUT_MILLIS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Config(@JsonProperty("Pattern") String pattern) {
    }

    @Override
    public boolean canHandle(RuleType type) {
        return type == RuleType.REGEX;
    }

    @Override
    public ValidationError validate(int row, String fieldName, String rawValue, String configJson) {
        Config cfg;
        try {
            cfg = MAPPER.readValue(configJson, Config.class);
        } catch (IOException e) {
            return new ValidationError(row, fieldName, rawValue, "Invalid validation configuration.");
        }

        if (rawValue == null || rawValue.trim().isEmpty()) {
            return null;
        }

        Pattern pattern = Pattern.compile(cfg.pattern() == null ? "" : cfg.pattern());
        CharSequence guarded = new DeadlineCharSequence(rawValue, MATCH_TIMEOUT_MILLIS);
        // C#'s Regex.IsMatch is an unanchored search; the seeded patterns carry
        // their own ^...$ anchors, so find() reproduces the same semantics.
        if (!pattern.matcher(guarded).find()) {
            return RegexValidationErrorMessages.getRegexValidationErrorMessage(row, fieldName, rawValue);
        }
        return null;
    }

    /**
     * Java's regex engine has no built-in timeout; this wrapper checks a
     * deadline on every charAt access so runaway backtracking is cut short.
     */
    private static final class DeadlineCharSequence implements CharSequence {

        private final CharSequence delegate;
        private final long deadlineNanos;

        DeadlineCharSequence(CharSequence delegate, long timeoutMillis) {
            this.delegate = delegate;
            this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000;
        }

        private DeadlineCharSequence(CharSequence delegate, long deadlineNanos, boolean raw) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalStateException("Regex match timed out after "
                        + MATCH_TIMEOUT_MILLIS + " ms.");
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadlineNanos, true);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
