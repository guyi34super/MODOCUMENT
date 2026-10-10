package com.modocument.application.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SEC-021 / gate B4: what a password must survive to be stored. */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(12);

    // ------------------------------------------------------------- B4 gate

    /** B4: 'Password123' dies on the breached list, in any casing. */
    @Test
    void password123IsRejected() {
        for (String variant : new String[]{"Password123", "password123", "PASSWORD123"}) {
            assertThatThrownBy(() -> policy.validate(variant, "someone@example.com"))
                    .as(variant)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("commonly used or breached");
        }
    }

    /** B4: the user's own email local-part inside the password is rejected. */
    @Test
    void emailLocalPartInsidePasswordIsRejected() {
        assertThatThrownBy(() -> policy.validate("xy-Marguerite!2026", "marguerite@bank.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
        // Case-insensitive both ways.
        assertThatThrownBy(() -> policy.validate("xyMARGUERITE2026!", "Marguerite@bank.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    /** B4: a 64+ char passphrase is first-class — no silly upper cap. */
    @Test
    void longPassphraseIsAccepted() {
        String passphrase =
                "correct horse battery staple rides again over the misty mountain pass";
        assertThat(passphrase.length()).isGreaterThanOrEqualTo(64);

        assertThatCode(() -> policy.validate(passphrase, "someone@example.com"))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------- details

    @Test
    void minimumLengthIsEnforced() {
        assertThatThrownBy(() -> policy.validate("elevenchars", "a@b.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12");
        assertThatThrownBy(() -> policy.validate(null, "a@b.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12");
        assertThatCode(() -> policy.validate("twelve-chars", "a@b.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void minimumLengthIsConfigurable() {
        PasswordPolicy loose = new PasswordPolicy(8);
        assertThatCode(() -> loose.validate("Kq2#zv9!", "a@b.com")).doesNotThrowAnyException();
        assertThat(loose.minLength()).isEqualTo(8);
    }

    /** Keyboard walks, seasons+years and numeric runs die on the list too. */
    @Test
    void commonFamiliesAreRejected() {
        for (String weak : new String[]{"qwertyuiop123", "1q2w3e4r5t2024",
                "September2025", "1234567890123"}) {
            assertThatThrownBy(() -> policy.validate(weak, "a@b.com"))
                    .as(weak)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("commonly used or breached");
        }
    }

    /** A sub-3-char local-part must not poison every password containing it. */
    @Test
    void tinyLocalPartsAreNotSubstringChecked() {
        assertThatCode(() -> policy.validate("stable-genius-42", "st@bank.example"))
                .doesNotThrowAnyException();
    }

    @Test
    void strongPasswordPasses() {
        assertThatCode(() -> policy.validate("Rotor-Kestrel-91x", "someone@example.com"))
                .doesNotThrowAnyException();
    }
}
