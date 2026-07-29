package com.modocument.application.privacy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiTokenizerTest {

    private static PiiTokenizer tokenizer(boolean enabled, String... extraFields) {
        PrivacyProperties properties = new PrivacyProperties();
        properties.setTokenizePii(enabled);
        properties.setExtraSensitiveFields(List.of(extraFields));
        return new PiiTokenizer(properties);
    }

    private final PiiTokenizer defaultTokenizer = tokenizer(true);

    @Test
    void detectsSensitiveFieldNamesAcrossCaseAndPunctuation() {
        PiiTokenizer.Session session = defaultTokenizer.newSession();

        assertThat(session.tokenizeValue("FirstName", "Ada")).isEqualTo("[NAME_1]");
        assertThat(session.tokenizeValue("first_name", "Grace")).isEqualTo("[NAME_2]");
        assertThat(session.tokenizeValue("FIRST-NAME", "Joan")).isEqualTo("[NAME_3]");
        assertThat(session.tokenizeValue("Email", "a@b.com")).isEqualTo("[EMAIL_1]");
        assertThat(session.tokenizeValue("PhoneNumber", "416 555 0199")).isEqualTo("[PHONE_1]");
        assertThat(session.tokenizeValue("SIN", "123456789")).isEqualTo("[ID_1]");
        assertThat(session.tokenizeValue("EmployeeNumber", "E-42")).isEqualTo("[ID_2]");
        assertThat(session.tokenizeValue("DateOfBirth", "1990-01-01")).isEqualTo("[DATE_1]");
        assertThat(session.tokenizeValue("BirthDate", "1991-02-02")).isEqualTo("[DATE_2]");
        assertThat(session.tokenizeValue("Street", "1 Main St")).isEqualTo("[ADDRESS_1]");
        assertThat(session.tokenizeValue("Gender", "F")).isEqualTo("[GENDER_1]");
        assertThat(session.tokenizeValue("PayRate", "42.50")).isEqualTo("[PAY_1]");
    }

    @Test
    void extraSensitiveFieldsAreHonored() {
        PiiTokenizer.Session session = tokenizer(true, "badge").newSession();

        assertThat(session.tokenizeValue("BadgeNumber", "B-778")).isEqualTo("[VALUE_1]");
    }

    @Test
    void valuePatternsCatchSensitiveShapesInInnocuouslyNamedFields() {
        PiiTokenizer.Session session = defaultTokenizer.newSession();

        assertThat(session.tokenizeValue("ContactInfo", "jane@acme.com")).isEqualTo("[EMAIL_1]");
        assertThat(session.tokenizeValue("Misc", "(416) 555-0199")).isEqualTo("[PHONE_1]");
        assertThat(session.tokenizeValue("Ref", "123 456 789")).isEqualTo("[ID_1]");
        assertThat(session.tokenizeValue("Code", "4111 1111 1111 1111")).isEqualTo("[ID_2]");
    }

    @Test
    void plainNumbersDatesAndCodesStayRawInNonSensitiveFields() {
        PiiTokenizer.Session session = defaultTokenizer.newSession();

        assertThat(session.tokenizeValue("Age", "42")).isEqualTo("42");
        assertThat(session.tokenizeValue("HireDate", "2024-01-05")).isEqualTo("2024-01-05");
        assertThat(session.tokenizeValue("Province", "ON")).isEqualTo("ON");
        assertThat(session.tokenizeValue("Department", "Engineering")).isEqualTo("Engineering");
    }

    @Test
    void embeddedSensitiveShapesAreReplacedInsideFreeText() {
        PiiTokenizer.Session session = defaultTokenizer.newSession();

        String tokenized = session.tokenizeValue("Notes", "Reach me at jane@acme.com after 5pm");
        assertThat(tokenized).isEqualTo("Reach me at [EMAIL_1] after 5pm");
        assertThat(session.detokenize(tokenized)).isEqualTo("Reach me at jane@acme.com after 5pm");
    }

    @Test
    void repeatedValuesReuseTheirToken() {
        PiiTokenizer.Session session = defaultTokenizer.newSession();

        assertThat(session.tokenizeValue("Email", "a@b.com")).isEqualTo("[EMAIL_1]");
        assertThat(session.tokenizeValue("AdditionalEmail", "a@b.com")).isEqualTo("[EMAIL_1]");
        assertThat(session.tokenizeValue("Email", "c@d.com")).isEqualTo("[EMAIL_2]");
    }

    @Test
    void rowRoundTripRestoresOriginalsExactly() {
        PiiTokenizer.Session session = defaultTokenizer.newSession();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("FirstName", "Ada");
        row.put("Email", "ada@acme.com");
        row.put("Age", "36");
        row.put("Notes", "");

        Map<String, String> tokenized = session.tokenizeRow(row);
        assertThat(tokenized.get("FirstName")).isEqualTo("[NAME_1]");
        assertThat(tokenized.get("Email")).isEqualTo("[EMAIL_1]");
        assertThat(tokenized.get("Age")).isEqualTo("36");
        assertThat(tokenized.get("Notes")).isEmpty();

        assertThat(session.detokenizeRow(tokenized)).isEqualTo(row);
    }

    @Test
    void detokenizeHandlesProseUnknownTokensAndSimilarNumbers() {
        PiiTokenizer.Session session = defaultTokenizer.newSession();
        for (int i = 0; i < 12; i++) {
            session.tokenizeValue("Email", "user" + i + "@x.com");
        }

        assertThat(session.detokenize("First is [EMAIL_1], twelfth is [EMAIL_12]."))
                .isEqualTo("First is user0@x.com, twelfth is user11@x.com.");
        assertThat(session.detokenize("Model invented [NAME_99] here."))
                .isEqualTo("Model invented [NAME_99] here.");
    }

    @Test
    void maskTextMasksShapesButKeepsStructure() {
        String masked = defaultTokenizer.maskText(
                "Name,Email,Phone\nAda,ada@acme.com,416-555-0199\n");

        assertThat(masked).startsWith("Name,Email,Phone");
        assertThat(masked).contains("[EMAIL]").contains("[PHONE]");
        assertThat(masked).doesNotContain("ada@acme.com").doesNotContain("416-555-0199");
    }

    @Test
    void disabledModeIsIdentity() {
        PiiTokenizer disabled = tokenizer(false);
        PiiTokenizer.Session session = disabled.newSession();

        assertThat(session.tokenizeValue("Email", "a@b.com")).isEqualTo("a@b.com");
        assertThat(session.detokenize("[EMAIL_1]")).isEqualTo("[EMAIL_1]");
        assertThat(disabled.maskText("ada@acme.com")).isEqualTo("ada@acme.com");
        assertThat(disabled.isEnabled()).isFalse();
    }

    @Test
    void blankValuesPassThrough() {
        PiiTokenizer.Session session = defaultTokenizer.newSession();

        assertThat(session.tokenizeValue("Email", "")).isEmpty();
        assertThat(session.tokenizeValue("Email", "   ")).isEqualTo("   ");
        assertThat(session.tokenizeValue("Email", null)).isNull();
    }

    @Test
    void rawValuesShapedLikeTokensAreLeftAlone() {
        PiiTokenizer.Session session = defaultTokenizer.newSession();

        String tokenized = session.tokenizeValue("Email", "[EMAIL_1]");
        assertThat(tokenized).isEqualTo("[EMAIL_1]");
        // Nothing was recorded, so detokenization cannot corrupt it either.
        assertThat(session.detokenize("[EMAIL_1]")).isEqualTo("[EMAIL_1]");
    }
}
