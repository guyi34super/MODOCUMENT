package com.modocument.application.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReDoS-guarded regular expressions, shared by the REGEX validation rule and
 * the code-based extraction engine.
 *
 * <p>Java's regex engine has no timeout, so every match runs against a
 * {@link CharSequence} that checks a deadline while the engine reads it:
 * runaway backtracking is cut short with a {@link RegexTimeoutException}.
 * Patterns that come from template authors for extraction (label and value
 * hints) are additionally vetted before use by {@link #check(String)}: a
 * length cap, the compile check of {@link RegexPatternChecker} and a
 * nested-quantifier screen. A {@link Budget} caps the total regex time spent
 * on one document on top of the per-match deadline.
 */
public final class SafeRegex {

    /** Cap for untrusted (extraction-hint) patterns; validation rules keep the checker's cap. */
    public static final int MAX_PATTERN_LENGTH = 500;

    /** Per-match deadline, the original 200 ms regex timeout of the validation rule. */
    public static final long DEFAULT_MATCH_TIMEOUT_MILLIS = 200;

    /** Total regex time one document may spend across all of its matches. */
    public static final long DEFAULT_DOCUMENT_BUDGET_MILLIS = 2_000;

    /** The deadline is checked every this many character reads (nanoTime is not free). */
    private static final int CHECK_INTERVAL = 32;

    private SafeRegex() {
    }

    /**
     * Returns null when an untrusted pattern is acceptable, otherwise a
     * human-readable problem: empty, longer than {@link #MAX_PATTERN_LENGTH},
     * not compilable, or a nested unbounded quantifier such as {@code (a+)+}.
     */
    public static String check(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "Pattern must not be empty.";
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            return "Pattern exceeds the maximum length of " + MAX_PATTERN_LENGTH + " characters.";
        }
        String problem = RegexPatternChecker.check(pattern);
        if (problem != null) {
            return problem;
        }
        if (hasNestedQuantifier(pattern)) {
            return "Pattern repeats a group that already contains a repetition "
                    + "(for example (a+)+), which can take exponential time.";
        }
        return null;
    }

    /** Vets and compiles an untrusted pattern; throws {@link UnsafePatternException} on rejection. */
    public static Pattern compile(String pattern, int flags) {
        String problem = check(pattern);
        if (problem != null) {
            throw new UnsafePatternException(problem);
        }
        return Pattern.compile(pattern, flags);
    }

    /** Wraps {@code input} so a match against it aborts once {@code timeoutMillis} has passed. */
    public static CharSequence withDeadline(CharSequence input, long timeoutMillis) {
        Deadline deadline = new Deadline(timeoutMillis);
        deadline.arm(timeoutMillis * 1_000_000L);
        return new DeadlineCharSequence(input, deadline);
    }

    /** Unanchored search with a per-match deadline. */
    public static boolean find(Pattern pattern, CharSequence input, long timeoutMillis) {
        return pattern.matcher(withDeadline(input, timeoutMillis)).find();
    }

    /** A per-document budget: {@code totalMillis} across all matches, each capped at {@code perMatchMillis}. */
    public static Budget budget(long totalMillis, long perMatchMillis) {
        return new Budget(totalMillis, perMatchMillis);
    }

    /** A budget with the default per-document and per-match limits. */
    public static Budget budget() {
        return budget(DEFAULT_DOCUMENT_BUDGET_MILLIS, DEFAULT_MATCH_TIMEOUT_MILLIS);
    }

    /** One match: span, whole text and capture groups (null for groups that did not take part). */
    public record Match(int start, int end, String value, List<String> groups) {

        /** Group 0 is the whole match; 1..n the capture groups. */
        public String group(int index) {
            if (index == 0) {
                return value;
            }
            return index <= groups.size() ? groups.get(index - 1) : null;
        }

        /** The first capture group when the pattern has one, otherwise the whole match. */
        public String firstGroupOrValue() {
            return groups.isEmpty() || groups.get(0) == null ? value : groups.get(0);
        }
    }

    /**
     * Regex time accounting for one document. Every find gets the smaller of
     * the per-match deadline and the remaining budget; once the budget is
     * spent every further find fails fast with {@link RegexTimeoutException}.
     * Not thread-safe: one budget per document per thread.
     */
    public static final class Budget {

        private final long perMatchNanos;
        private final long perMatchMillis;
        private long remainingNanos;

        private Budget(long totalMillis, long perMatchMillis) {
            this.remainingNanos = Math.max(0, totalMillis) * 1_000_000L;
            this.perMatchMillis = perMatchMillis;
            this.perMatchNanos = Math.max(1, perMatchMillis) * 1_000_000L;
        }

        public boolean exhausted() {
            return remainingNanos <= 0;
        }

        /** First match of {@code pattern} in {@code input}. */
        public Optional<Match> find(Pattern pattern, CharSequence input) {
            List<Match> matches = findAll(pattern, input, 1);
            return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
        }

        /** Up to {@code maxMatches} successive matches of {@code pattern} in {@code input}. */
        public List<Match> findAll(Pattern pattern, CharSequence input, int maxMatches) {
            Deadline deadline = new Deadline(perMatchMillis);
            Matcher matcher = pattern.matcher(new DeadlineCharSequence(input, deadline));
            List<Match> matches = new ArrayList<>();
            while (matches.size() < maxMatches) {
                if (exhausted()) {
                    throw new RegexTimeoutException("Regex time budget for this document is spent.");
                }
                deadline.arm(Math.min(perMatchNanos, remainingNanos));
                long started = System.nanoTime();
                boolean found;
                try {
                    found = matcher.find();
                } finally {
                    remainingNanos -= System.nanoTime() - started;
                }
                if (!found) {
                    break;
                }
                List<String> groups = new ArrayList<>(matcher.groupCount());
                for (int g = 1; g <= matcher.groupCount(); g++) {
                    groups.add(matcher.group(g));
                }
                matches.add(new Match(matcher.start(), matcher.end(), matcher.group(), groups));
                if (matcher.end() == matcher.start() && matcher.end() >= input.length()) {
                    break;
                }
            }
            return matches;
        }
    }

    /** A match ran past its deadline or the document's regex budget. */
    public static class RegexTimeoutException extends IllegalStateException {
        public RegexTimeoutException(String message) {
            super(message);
        }
    }

    /** An untrusted pattern was refused by {@link #check(String)}. */
    public static class UnsafePatternException extends IllegalArgumentException {
        public UnsafePatternException(String message) {
            super(message);
        }
    }

    /**
     * Screens for a group that contains an unbounded repetition and is itself
     * repeated without bound ({@code (a+)+}, {@code (\w*\s?)*}, {@code (x+){2,}},
     * {@code (.*,){10}}), the classic catastrophic-backtracking shape. Atomic groups and
     * possessive outer quantifiers are exempt. Deliberately conservative: the
     * deadline remains the real guard for anything this screen misses.
     */
    static boolean hasNestedQuantifier(String pattern) {
        Deque<boolean[]> groups = new ArrayDeque<>(); // [containsUnbounded, atomic]
        int i = 0;
        int n = pattern.length();
        while (i < n) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 < n && pattern.charAt(i + 1) == 'Q') {
                    int end = pattern.indexOf("\\E", i + 2);
                    i = end < 0 ? n : end + 2;
                } else {
                    i += 2;
                }
                continue;
            }
            if (c == '[') {
                i = skipCharClass(pattern, i);
                continue;
            }
            if (c == '(') {
                boolean atomic = pattern.startsWith("(?>", i);
                groups.push(new boolean[]{false, atomic});
                i++;
                continue;
            }
            if (c == ')') {
                boolean[] inner = groups.isEmpty() ? new boolean[]{false, false} : groups.pop();
                int quantifierEnd = quantifierEnd(pattern, i + 1);
                boolean outerUnbounded = quantifierEnd > i + 1
                        && isUnbounded(pattern.substring(i + 1, quantifierEnd), true)
                        && !(quantifierEnd < n && pattern.charAt(quantifierEnd) == '+');
                if (outerUnbounded && inner[0] && !inner[1]) {
                    return true;
                }
                if (!groups.isEmpty() && (inner[0] || outerUnbounded)) {
                    groups.peek()[0] = true;
                }
                i = quantifierEnd > i + 1 ? quantifierEnd : i + 1;
                continue;
            }
            int quantifierEnd = quantifierEnd(pattern, i);
            if (quantifierEnd > i) {
                if (isUnbounded(pattern.substring(i, quantifierEnd), false) && !groups.isEmpty()) {
                    groups.peek()[0] = true;
                }
                i = quantifierEnd;
                continue;
            }
            i++;
        }
        return false;
    }

    private static int skipCharClass(String pattern, int open) {
        int depth = 0;
        int i = open;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']' && i > open + 1) {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return pattern.length();
    }

    /** End index of a quantifier starting at {@code start}, or {@code start} when there is none. */
    private static int quantifierEnd(String pattern, int start) {
        if (start >= pattern.length()) {
            return start;
        }
        char c = pattern.charAt(start);
        if (c == '*' || c == '+' || c == '?') {
            return start + 1;
        }
        if (c == '{') {
            int close = pattern.indexOf('}', start);
            if (close > start && pattern.substring(start + 1, close).matches("\\d+(,\\d*)?")) {
                return close + 1;
            }
        }
        return start;
    }

    /**
     * {@code *}, {@code +}, {@code {n,}} and wide {@code {n,m}} repeat without
     * a useful bound. Repeating a group, a large exact count ({@code (.*,){10}})
     * is just as bad: polynomial with a high exponent.
     */
    private static boolean isUnbounded(String quantifier, boolean groupRepeat) {
        if (quantifier.equals("*") || quantifier.equals("+")) {
            return true;
        }
        if (quantifier.startsWith("{")) {
            String body = quantifier.substring(1, quantifier.length() - 1);
            int comma = body.indexOf(',');
            if (comma < 0) {
                return groupRepeat && parse(body) >= 10;
            }
            String max = body.substring(comma + 1);
            return max.isEmpty() || parse(max) >= 10;
        }
        return false;
    }

    private static long parse(String digits) {
        return digits.length() > 9 ? Long.MAX_VALUE : Long.parseLong(digits);
    }

    /** Mutable deadline shared by a guarded sequence and its sub-sequences. */
    private static final class Deadline {

        private final long timeoutMillis;
        private long deadlineNanos;

        Deadline(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        void arm(long windowNanos) {
            deadlineNanos = System.nanoTime() + windowNanos;
        }

        void check() {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexTimeoutException("Regex match timed out after " + timeoutMillis + " ms.");
            }
        }
    }

    /** Checks the deadline as the regex engine reads characters, so backtracking cannot run away. */
    private static final class DeadlineCharSequence implements CharSequence {

        private final CharSequence delegate;
        private final Deadline deadline;
        private int untilCheck;

        DeadlineCharSequence(CharSequence delegate, Deadline deadline) {
            this.delegate = delegate;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (--untilCheck <= 0) {
                untilCheck = CHECK_INTERVAL;
                deadline.check();
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
