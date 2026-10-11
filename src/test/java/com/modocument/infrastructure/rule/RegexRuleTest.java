package com.modocument.infrastructure.rule;

import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegexRuleTest {

    private final RegexRule rule = new RegexRule();

    @Test
    void handlesOnlyRegexRuleType() {
        assertThat(rule.canHandle(RuleType.REGEX)).isTrue();
        assertThat(rule.canHandle(RuleType.REQUIRED)).isFalse();
    }

    @Test
    void skipsBlankValues() {
        assertThat(rule.validate(1, "SIN", "", "{\"Pattern\":\"^x$\"}")).isNull();
        assertThat(rule.validate(1, "SIN", "   ", "{\"Pattern\":\"^x$\"}")).isNull();
        assertThat(rule.validate(1, "SIN", null, "{\"Pattern\":\"^x$\"}")).isNull();
    }

    @Test
    void passesMatchingValue() {
        assertThat(rule.validate(1, "DayforceBranchNo", "PP1",
                "{\"Pattern\":\"^(PP(1|2|3|4|7|9))$\"}")).isNull();
    }

    @Test
    void returnsDomainMessageForMismatch() {
        ValidationError error = rule.validate(1, "DayforceBranchNo", "PP5",
                "{\"Pattern\":\"^(PP(1|2|3|4|7|9))$\"}");

        assertThat(error).isNotNull();
        assertThat(error.errorMessage())
                .isEqualTo("Dayforce Branch Number must be one of PP1,PP2,PP3,PP4,PP7 or PP9.");
    }

    @Test
    void returnsGenericMessageForUnknownField() {
        ValidationError error = rule.validate(3, "SomeUnknownField", "abc",
                "{\"Pattern\":\"^x$\"}");

        assertThat(error).isNotNull();
        assertThat(error.errorMessage()).isEqualTo("Validation error for SomeUnknownField.");
    }

    @Test
    void appendsExampleForKnownPresetPattern() {
        ValidationError error = rule.validate(2, "SomeUnknownField", "not-an-email",
                "{\"Pattern\":\"^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$\"}");

        assertThat(error).isNotNull();
        assertThat(error.errorMessage())
                .isEqualTo("Validation error for SomeUnknownField. e.g. jane@acme.com");
    }

    @Test
    void reportsInvalidConfiguration() {
        ValidationError error = rule.validate(1, "SIN", "123", "not-json");

        assertThat(error).isNotNull();
        assertThat(error.errorMessage()).isEqualTo("Invalid validation configuration.");
    }

    @Test
    void abortsCatastrophicBacktrackingAfterTheMatchTimeout() {
        // Polynomial blow-up JDK 21 cannot memoise; the SafeRegex deadline stops it.
        String config = "{\"Pattern\":\"^(.*,){10}X\"}";

        assertThatThrownBy(() -> rule.validate(1, "Notes", "a,".repeat(40), config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Regex match timed out after " + RegexRule.MATCH_TIMEOUT_MILLIS + " ms.");
    }

    @Test
    void validatesSinFormatFromSeedPattern() {
        String config = "{\"Pattern\": \"^(?![08])\\\\d{3} \\\\d{3} \\\\d{3}$\"}";

        assertThat(rule.validate(1, "SIN", "123 456 789", config)).isNull();
        assertThat(rule.validate(1, "SIN", "023 456 789", config)).isNotNull();
        assertThat(rule.validate(1, "SIN", "123456789", config)).isNotNull();
    }
}
