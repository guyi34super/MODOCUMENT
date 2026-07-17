package com.modocument.infrastructure.rule;

import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RangeRuleTest {

    private final RangeRule rule = new RangeRule();

    @Test
    void handlesOnlyRangeRuleType() {
        assertThat(rule.canHandle(RuleType.RANGE)).isTrue();
        assertThat(rule.canHandle(RuleType.REGEX)).isFalse();
    }

    @Test
    void reportsNonNumericValue() {
        ValidationError error = rule.validate(1, "PayRate", "abc", "{\"Min\":0,\"Max\":10}");

        assertThat(error).isNotNull();
        assertThat(error.errorMessage()).isEqualTo("Value is not a number.");
    }

    @Test
    void reportsOutOfRangeValue() {
        ValidationError error = rule.validate(1, "PayRate", "11", "{\"Min\":0,\"Max\":10}");

        assertThat(error).isNotNull();
        assertThat(error.errorMessage()).isEqualTo("Must be between 0 and 10.");
    }

    @Test
    void passesInRangeValue() {
        assertThat(rule.validate(1, "PayRate", "5.5", "{\"Min\":0,\"Max\":10}")).isNull();
        assertThat(rule.validate(1, "PayRate", "0", "{\"Min\":0,\"Max\":10}")).isNull();
        assertThat(rule.validate(1, "PayRate", "10", "{\"Min\":0,\"Max\":10}")).isNull();
    }
}
