package com.modocument.infrastructure.rule;

import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DateFormatRuleTest {

    private final DateFormatRule rule = new DateFormatRule();

    @Test
    void handlesOnlyDateFormatRuleType() {
        assertThat(rule.canHandle(RuleType.DATE_FORMAT)).isTrue();
        assertThat(rule.canHandle(RuleType.EXPIRY_DATE)).isFalse();
    }

    @Test
    void passesIsoDate() {
        assertThat(rule.validate(1, "BirthDate", "2002-07-11", "{}")).isNull();
    }

    @Test
    void reportsWrongFormat() {
        ValidationError error = rule.validate(1, "BirthDate", "11-07-2002", "{}");

        assertThat(error).isNotNull();
        assertThat(error.errorMessage())
                .isEqualTo("Date must be in the format YYYY-MM-DD, e.g. 2026-07-18");
    }

    @Test
    void reportsInvalidCalendarDate() {
        assertThat(rule.validate(1, "BirthDate", "2002-02-30", "{}")).isNotNull();
    }

    @Test
    void reportsEmptyValue() {
        assertThat(rule.validate(1, "BirthDate", "", "{}")).isNotNull();
        assertThat(rule.validate(1, "BirthDate", null, "{}")).isNotNull();
    }
}
