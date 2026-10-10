package com.modocument.application.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeRegexTest {

    /**
     * Still catastrophic on JDK 21 (polynomial with a high exponent, so loop
     * memoisation does not help): ten nested ".*," splits of a long comma list
     * with no X at the end. Compiled directly, the way a validation rule's
     * pattern is, to show the deadline catches what vetting would refuse.
     */
    private static final Pattern CATASTROPHIC = Pattern.compile("^(.*,){10}X");
    private static final String EVIL_INPUT = "a,".repeat(40);

    @ParameterizedTest
    @ValueSource(strings = {
            "^(PP(1|2|3|4|7|9))$",
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
            "REF-(\\d{4}-\\d{4})",
            "MUR ([\\d,]+\\.\\d{2})",
            "from ([A-Z][A-Z ]+[A-Z])\\.",
            "(a+)++",
            "(?>a+)+",
            "(ab){2}",
            "(\\d{10})+",
            "[(+]+x"})
    void acceptsOrdinaryPatterns(String pattern) {
        assertThat(SafeRegex.check(pattern)).isNull();
        assertThat(SafeRegex.compile(pattern, 0).pattern()).isEqualTo(pattern);
    }

    @ParameterizedTest
    @ValueSource(strings = {"(a+)+$", "(\\w+\\s?)*$", "(x*)*y", "((ab)+c)+", "(a|b+){2,}", "(\\d+,?){1,50}",
            "^(.*,){10}X"})
    void rejectsNestedUnboundedRepetition(String pattern) {
        assertThat(SafeRegex.check(pattern)).contains("exponential");
        assertThatThrownBy(() -> SafeRegex.compile(pattern, 0))
                .isInstanceOf(SafeRegex.UnsafePatternException.class);
    }

    @Test
    void rejectsOverlongEmptyAndInvalidPatterns() {
        assertThat(SafeRegex.check("a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1))).contains("maximum length");
        assertThat(SafeRegex.check("")).contains("empty");
        assertThat(SafeRegex.check(null)).contains("empty");
        assertThat(SafeRegex.check("([a-z")).contains("Invalid regular expression");
    }

    @Test
    void cutsCatastrophicBacktrackingAtTheDeadline() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> SafeRegex.find(CATASTROPHIC, EVIL_INPUT, 50))
                .isInstanceOf(SafeRegex.RegexTimeoutException.class)
                .hasMessageContaining("timed out after 50 ms");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void finishesOrdinaryMatchesWithinTheDeadline() {
        assertThat(SafeRegex.find(Pattern.compile("^a+$"), "a".repeat(10_000), 200)).isTrue();
        assertThat(SafeRegex.find(Pattern.compile("b"), "a".repeat(10_000), 200)).isFalse();
    }

    @Test
    void documentBudgetIsSharedAcrossMatchesAndFailsFastOnceSpent() {
        SafeRegex.Budget budget = SafeRegex.budget(60, 40);
        assertThatThrownBy(() -> budget.find(CATASTROPHIC, EVIL_INPUT))
                .isInstanceOf(SafeRegex.RegexTimeoutException.class);
        assertThatThrownBy(() -> budget.find(CATASTROPHIC, EVIL_INPUT))
                .isInstanceOf(SafeRegex.RegexTimeoutException.class);
        assertThat(budget.exhausted()).isTrue();
        assertThatThrownBy(() -> budget.find(Pattern.compile("x"), "x"))
                .isInstanceOf(SafeRegex.RegexTimeoutException.class)
                .hasMessageContaining("budget");
    }

    @Test
    void findAllReturnsSpansAndGroups() {
        SafeRegex.Budget budget = SafeRegex.budget();
        List<SafeRegex.Match> matches = budget.findAll(Pattern.compile("REF-(\\d+)"), "REF-1 x REF-22 y REF-333", 2);

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).group(1)).isEqualTo("1");
        assertThat(matches.get(1).value()).isEqualTo("REF-22");
        assertThat(matches.get(1).firstGroupOrValue()).isEqualTo("22");
        assertThat(matches.get(1).start()).isEqualTo(8);
        assertThat(budget.find(Pattern.compile("y"), "xyz").orElseThrow().firstGroupOrValue()).isEqualTo("y");
        assertThat(budget.exhausted()).isFalse();
    }
}
