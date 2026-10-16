package com.modocument.doccreation.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The plain-language rule summaries of the builder's Checks panel (field-validation-contract §4.4). */
class RuleSummariesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> LABELS = Map.of("start", "Start date", "married", "Married");

    private static String summary(DocRuleType type, String config) throws Exception {
        JsonNode node = config == null ? null : JSON.readTree(config);
        return RuleSummaries.summary(type, node, LABELS);
    }

    @Test
    void everyRuleTypeReadsAsPlainWords() throws Exception {
        assertThat(summary(DocRuleType.REQUIRED, "{}")).isEqualTo("Required");
        assertThat(summary(DocRuleType.EMAIL, "{}")).isEqualTo("E-mail address");
        assertThat(summary(DocRuleType.EXPIRY_DATE, "{}")).isEqualTo("A future date");
        assertThat(summary(DocRuleType.REGEX, "{\"Pattern\":\"^[A-Z]{2}\\\\d{6}$\"}"))
                .isEqualTo("Matches the pattern ^[A-Z]{2}\\d{6}$");
        assertThat(summary(DocRuleType.LENGTH, "{\"Min\":1,\"Max\":40}")).isEqualTo("1–40 characters");
        assertThat(summary(DocRuleType.LENGTH, "{\"Max\":40}")).isEqualTo("At most 40 characters");
        assertThat(summary(DocRuleType.LENGTH, "{\"Min\":3}")).isEqualTo("At least 3 characters");
        assertThat(summary(DocRuleType.RANGE, "{\"Min\":0,\"Max\":100}")).isEqualTo("Between 0 and 100");
        assertThat(summary(DocRuleType.RANGE, "{\"Min\":18}")).isEqualTo("At least 18");
        assertThat(summary(DocRuleType.ALLOWED_VALUES, "{\"Values\":[\"Single\",\"Married\"]}"))
                .isEqualTo("One of: Single, Married");
        assertThat(summary(DocRuleType.ALLOWED_VALUES, "{\"Values\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"))
                .isEqualTo("One of: a, b, c, d, e, …");
        assertThat(summary(DocRuleType.DATE_FORMAT, "{}")).isEqualTo("A date like yyyy-MM-dd");
        assertThat(summary(DocRuleType.DATE_FORMAT, "{\"Formats\":[\"dd/MM/yyyy\",\"d MMM yyyy\"]}"))
                .isEqualTo("A date like dd/MM/yyyy or d MMM yyyy");
        assertThat(summary(DocRuleType.DATE_RANGE, "{\"Max\":\"-18y\"}")).isEqualTo("On or before 18 years ago");
        assertThat(summary(DocRuleType.DATE_RANGE, "{\"Min\":\"today\",\"Max\":\"+1m\"}"))
                .isEqualTo("Between today and 1 month from today");
        assertThat(summary(DocRuleType.DATE_RANGE, "{\"Min\":\"2026-01-01\"}")).isEqualTo("On or after 2026-01-01");
        assertThat(summary(DocRuleType.REQUIRED_IF, "{\"Field\":\"start\"}"))
                .isEqualTo("Required when Start date is filled");
        assertThat(summary(DocRuleType.REQUIRED_IF, "{\"Field\":\"married\",\"Equals\":\"Yes\"}"))
                .isEqualTo("Required when Married is Yes");
        assertThat(summary(DocRuleType.COMPARE, "{\"Field\":\"start\",\"Op\":\">=\"}"))
                .isEqualTo("Must be ≥ Start date");
        assertThat(summary(DocRuleType.COMPARE, "{\"Field\":\"unknown_key\",\"Op\":\"!=\"}"))
                .isEqualTo("Must be different from unknown_key");
    }

    @Test
    void unreadableSettingsGiveTheGenericSummary() throws Exception {
        assertThat(summary(DocRuleType.REGEX, null)).isEqualTo("Matches a pattern");
        assertThat(summary(DocRuleType.RANGE, "[1,2]")).isEqualTo("A number");
        assertThat(summary(DocRuleType.LENGTH, "{\"Min\":{\"x\":1}}")).isEqualTo("Any length");
        assertThat(summary(DocRuleType.COMPARE, "{}")).isEqualTo("Must be compared with another field");
        assertThat(summary(DocRuleType.REGEX, "{\"Pattern\":\"" + "a".repeat(60) + "\"}"))
                .isEqualTo("Matches the pattern " + "a".repeat(40) + "…");
    }

    @Test
    void relativeDatesReadAsWords() {
        assertThat(RuleSummaries.date("-1d")).isEqualTo("1 day ago");
        assertThat(RuleSummaries.date("+2w")).isEqualTo("2 weeks from today");
        assertThat(RuleSummaries.date("+0d")).isEqualTo("today");
        assertThat(RuleSummaries.date(" TODAY ")).isEqualTo("today");
        assertThat(RuleSummaries.date("31/12/2026")).isEqualTo("31/12/2026");
    }
}
