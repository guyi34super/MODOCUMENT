package com.modocument.application.privacy;

import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.RuleType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiClassifierTest {

    private final PiiClassifier classifier = new PiiClassifier();

    private PiiKind byName(String fieldName) {
        return classifier.infer(fieldName, List.of(), List.of());
    }

    @Test
    void classifiesByFieldName() {
        assertThat(byName("NIC")).isEqualTo(PiiKind.NIC);
        assertThat(byName("Customer NIC")).isEqualTo(PiiKind.NIC);
        assertThat(byName("nic_no")).isEqualTo(PiiKind.NIC);
        assertThat(byName("National ID")).isEqualTo(PiiKind.NIC);
        assertThat(byName("AccountNumber")).isEqualTo(PiiKind.ACCOUNT_NUMBER);
        assertThat(byName("acct_ref")).isEqualTo(PiiKind.ACCOUNT_NUMBER);
        assertThat(byName("IBAN")).isEqualTo(PiiKind.ACCOUNT_NUMBER);
        assertThat(byName("Email Address")).isEqualTo(PiiKind.EMAIL);
        assertThat(byName("MobilePhone")).isEqualTo(PiiKind.PHONE);
        assertThat(byName("FirstName")).isEqualTo(PiiKind.NAME);
        assertThat(byName("Surname")).isEqualTo(PiiKind.NAME);
    }

    /** "nic" must be its own word — electronics are not national ids. */
    @Test
    void nicIsNeverInferredFromMereSubstrings() {
        assertThat(byName("Electronic")).isEqualTo(PiiKind.NONE);
        assertThat(byName("UnicodeFlag")).isEqualTo(PiiKind.NONE);
        assertThat(byName("CommunicationChannel")).isEqualTo(PiiKind.NONE);
    }

    @Test
    void emailRegexRuleClassifiesAsEmail() {
        ValidationRule emailRule = new ValidationRule(1, "Contact", RuleType.REGEX,
                "{\"Pattern\":\"^[^@]+@[^@]+$\"}");

        assertThat(classifier.infer("Contact", List.of(emailRule), List.of()))
                .isEqualTo(PiiKind.EMAIL);
        // A non-regex rule, or a regex without @, says nothing.
        ValidationRule required = new ValidationRule(1, "Contact", RuleType.REQUIRED, "{}");
        assertThat(classifier.infer("Contact", List.of(required), List.of()))
                .isEqualTo(PiiKind.NONE);
    }

    @Test
    void unanimousValueShapesClassify() {
        assertThat(classifier.infer("colA", List.of(),
                List.of("M090976290148E", "B123456789012X", "")))
                .isEqualTo(PiiKind.NIC);
        assertThat(classifier.infer("colB", List.of(),
                List.of("00445566778899", "123456789")))
                .isEqualTo(PiiKind.ACCOUNT_NUMBER);
        assertThat(classifier.infer("colC", List.of(),
                List.of("a@b.mu", "jane.doe@acme.com")))
                .isEqualTo(PiiKind.EMAIL);
    }

    /** One non-matching sample vetoes shape classification — no false positives. */
    @Test
    void mixedOrEmptySamplesStayUnclassified() {
        assertThat(classifier.infer("colD", List.of(),
                List.of("M090976290148E", "hello"))).isEqualTo(PiiKind.NONE);
        assertThat(classifier.infer("colE", List.of(),
                List.of("00445566778899", "a@b.mu"))).isEqualTo(PiiKind.NONE);
        assertThat(classifier.infer("colF", List.of(), List.of())).isEqualTo(PiiKind.NONE);
        assertThat(classifier.infer("colG", List.of(), List.of("", "  "))).isEqualTo(PiiKind.NONE);
        assertThat(classifier.infer(null, null, null)).isEqualTo(PiiKind.NONE);
    }

    /** The explicit tag beats everything — enforced by the caller, name wins here. */
    @Test
    void nameSignalOutranksShapes() {
        // A column NAMED email but holding digits still classifies EMAIL:
        // the owner-facing name is the stronger signal.
        assertThat(classifier.infer("Email", List.of(), List.of("123456789")))
                .isEqualTo(PiiKind.EMAIL);
    }
}
