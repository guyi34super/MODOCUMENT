package com.modocument.application.privacy;

import com.modocument.domain.model.PiiKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskerTest {

    @Test
    void accountNumbersKeepTheirLastFour() {
        assertThat(Masker.mask(PiiKind.ACCOUNT_NUMBER, "00445566778899")).isEqualTo("****8899");
        assertThat(Masker.mask(PiiKind.ACCOUNT_NUMBER, "MU12-3456-789")).isEqualTo("****-789");
        // Too short to keep anything meaningful: fully masked.
        assertThat(Masker.mask(PiiKind.ACCOUNT_NUMBER, "1234")).isEqualTo("****");
    }

    @Test
    void nicIsFullyMaskedAtFixedWidth() {
        assertThat(Masker.mask(PiiKind.NIC, "M090976290148E")).isEqualTo("********");
        assertThat(Masker.mask(PiiKind.NIC, "B123456789")).isEqualTo("********");
        // The mask leaks neither length nor format.
        assertThat(Masker.mask(PiiKind.NIC, "x")).isEqualTo("********");
    }

    @Test
    void emailKeepsFirstCharacterAndDomain() {
        assertThat(Masker.mask(PiiKind.EMAIL, "jane@acme.mu")).isEqualTo("j***@acme.mu");
        assertThat(Masker.mask(PiiKind.EMAIL, "a@b.com")).isEqualTo("a***@b.com");
        // Not actually email-shaped: masked like a name, nothing leaks.
        assertThat(Masker.mask(PiiKind.EMAIL, "no-at-sign")).isEqualTo("n***");
    }

    @Test
    void phoneKeepsLastTwoAndNameKeepsFirstLetter() {
        assertThat(Masker.mask(PiiKind.PHONE, "+230 5712 3456")).isEqualTo("****56");
        assertThat(Masker.mask(PiiKind.NAME, "Anna Smith")).isEqualTo("A***");
    }

    @Test
    void nullsBlanksAndNoneKindPassThrough() {
        assertThat(Masker.mask(PiiKind.NIC, null)).isNull();
        assertThat(Masker.mask(PiiKind.NIC, "")).isEmpty();
        assertThat(Masker.mask(PiiKind.NIC, "  ")).isEqualTo("  ");
        assertThat(Masker.mask(PiiKind.NONE, "M090976290148E")).isEqualTo("M090976290148E");
        assertThat(Masker.mask(null, "M090976290148E")).isEqualTo("M090976290148E");
    }

    /** Masking a masked value must be a no-op on every kind (idempotence). */
    @Test
    void maskingIsIdempotent() {
        for (PiiKind kind : PiiKind.values()) {
            for (String raw : new String[] {
                    "M090976290148E", "00445566778899", "jane@acme.mu",
                    "+230 5712 3456", "Anna Smith"}) {
                String once = Masker.mask(kind, raw);
                assertThat(Masker.mask(kind, once))
                        .as("%s re-masked (%s)", kind, raw)
                        .isEqualTo(once);
            }
        }
    }

    @Test
    void maskShapesMasksEmbeddedNicAccountAndEmailShapes() {
        String masked = Masker.maskShapes(
                "Contract for M090976290148E, account 00445566778899, reach jane@acme.mu.");

        assertThat(masked).isEqualTo(
                "Contract for ********, account ****8899, reach j***@acme.mu.");
        // Idempotent: the masked forms match no shape any more.
        assertThat(Masker.maskShapes(masked)).isEqualTo(masked);
    }

    @Test
    void maskShapesLeavesOrdinaryTextAlone() {
        String text = "Roof inspected on 2026-10-04; see job 123 and ticket ABC-42.";
        assertThat(Masker.maskShapes(text)).isEqualTo(text);
        assertThat(Masker.maskShapes(null)).isNull();
    }
}
