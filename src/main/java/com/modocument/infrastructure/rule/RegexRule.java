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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class RegexRule implements ValidationRuleValidator {

    /** Matching is aborted once it exceeds this budget, like the original 200 ms regex timeout. */
    static final long MATCH_TIMEOUT_MILLIS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Rule configs are a small, stable set but validation runs this per cell,
     * so parsing the JSON and compiling the pattern for every value made the
     * cost of a 10k-row file linear in wasted work. Keyed by the raw config
     * string; cleared wholesale if it ever grows past the cap, since that
     * would mean configs are being generated rather than reused.
     */
    private static final int CACHE_LIMIT = 500;
    private static final Map<String, Compiled> CACHE = new ConcurrentHashMap<>();
    private static final Compiled INVALID = new Compiled(null, null);

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Config(@JsonProperty("Pattern") String pattern) {
    }

    /** A parsed+compiled config, or {@link #INVALID} when either step failed. */
    private record Compiled(Pattern pattern, String source) {
    }

    @Override
    public boolean canHandle(RuleType type) {
        return type == RuleType.REGEX;
    }

    @Override
    public ValidationError validate(int row, String fieldName, String rawValue, String configJson) {
        Compiled compiled = compiled(configJson);
        if (compiled == INVALID) {
            return new ValidationError(row, fieldName, rawValue, "Invalid validation configuration.");
        }

        if (rawValue == null || rawValue.trim().isEmpty()) {
            return null;
        }

        CharSequence guarded = new DeadlineCharSequence(rawValue, MATCH_TIMEOUT_MILLIS);
        // C#'s Regex.IsMatch is an unanchored search; the seeded patterns carry
        // their own ^...$ anchors, so find() reproduces the same semantics.
        if (!compiled.pattern().matcher(guarded).find()) {
            return RegexValidationErrorMessages.getRegexValidationErrorMessage(
                    row, fieldName, rawValue, compiled.source());
        }
        return null;
    }

    private static Compiled compiled(String configJson) {
        if (configJson == null) {
            return INVALID;
        }
        Compiled hit = CACHE.get(configJson);
        if (hit != null) {
            return hit;
        }
        Compiled result;
        try {
            Config cfg = MAPPER.readValue(configJson, Config.class);
            String source = cfg.pattern() == null ? "" : cfg.pattern();
            result = new Compiled(Pattern.compile(source), source);
        } catch (IOException | PatternSyntaxException e) {
            // A malformed pattern is a configuration error, not a 500: report
            // it the same way as unreadable config JSON.
            result = INVALID;
        }
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(configJson, result);
        return result;
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
