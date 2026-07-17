package com.modocument.infrastructure.rule;

import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredRuleTest {

    private final RequiredRule rule = new RequiredRule();

    @Test
    void handlesOnlyRequiredRuleType() {
        assertThat(rule.canHandle(RuleType.REQUIRED)).isTrue();
        assertThat(rule.canHandle(RuleType.REGEX)).isFalse();
    }

    @Test
    void returnsErrorForBlankValue() {
        ValidationError error = rule.validate(1, "FirstName", "  ", "{}");

        assertThat(error).isNotNull();
        assertThat(error.errorMessage()).isEqualTo("Value is required.");
        assertThat(error.row()).isEqualTo(1);
        assertThat(error.fieldName()).isEqualTo("FirstName");
    }

    @Test
    void returnsErrorForNullValue() {
        assertThat(rule.validate(1, "FirstName", null, "{}")).isNotNull();
    }

    @Test
    void passesForNonBlankValue() {
        assertThat(rule.validate(1, "FirstName", "Ada", "{}")).isNull();
    }
}
