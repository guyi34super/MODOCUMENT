package com.modocument.infrastructure.rule;

import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryDateRuleTest {

    private final ExpiryDateRule rule = new ExpiryDateRule();

    @Test
    void handlesOnlyExpiryDateRuleType() {
        assertThat(rule.canHandle(RuleType.EXPIRY_DATE)).isTrue();
        assertThat(rule.canHandle(RuleType.DATE_FORMAT)).isFalse();
    }

    @Test
    void passesFutureDate() {
        String future = LocalDate.now().plusYears(1).toString();

        assertThat(rule.validate(1, "SINExpiryDate", future, "{}")).isNull();
    }

    @Test
    void reportsPastDate() {
        ValidationError error = rule.validate(1, "SINExpiryDate", "2020-01-01", "{}");

        assertThat(error).isNotNull();
        assertThat(error.errorMessage()).isEqualTo("Expiry date must be in the future.");
    }

    @Test
    void passesUnparseableValue() {
        // Format problems are reported by the DateFormat rule, not this one.
        assertThat(rule.validate(1, "SINExpiryDate", "not-a-date", "{}")).isNull();
        assertThat(rule.validate(1, "SINExpiryDate", "", "{}")).isNull();
    }
}
