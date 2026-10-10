package com.modocument.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCipherTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef"; // 32 bytes
    private static final String JWT_SECRET =
            "modocument-local-dev-secret-change-in-production-0123456789";

    @Test
    void roundTripsASecret() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY, JWT_SECRET, false);
        String stored = cipher.encrypt("JBSWY3DPEHPK3PXP");

        assertThat(stored).isNotEqualTo("JBSWY3DPEHPK3PXP").contains(":");
        assertThat(cipher.decrypt(stored)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    /** Fresh random IV per encryption: same plaintext, different ciphertext. */
    @Test
    void everyEncryptionUsesAFreshIv() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY, JWT_SECRET, false);

        assertThat(cipher.encrypt("JBSWY3DPEHPK3PXP"))
                .isNotEqualTo(cipher.encrypt("JBSWY3DPEHPK3PXP"));
    }

    /** GCM authenticates: a flipped ciphertext bit must never decrypt. */
    @Test
    void tamperedCiphertextIsRejected() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY, JWT_SECRET, false);
        String stored = cipher.encrypt("JBSWY3DPEHPK3PXP");
        char last = stored.charAt(stored.length() - 1);
        String tampered = stored.substring(0, stored.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("not-a-ciphertext"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRequiresADedicatedKeyOfAtLeast32Bytes() {
        assertThatThrownBy(() -> new MfaSecretCipher("", JWT_SECRET, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_MFA_KEY");
        assertThatThrownBy(() -> new MfaSecretCipher("too-short", JWT_SECRET, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    /** Dev/test profiles derive from the JWT secret (warned) and still work. */
    @Test
    void devProfilesFallBackToTheJwtSecretDerivation() {
        MfaSecretCipher cipher = new MfaSecretCipher("", JWT_SECRET, true);

        assertThat(cipher.decrypt(cipher.encrypt("JBSWY3DPEHPK3PXP")))
                .isEqualTo("JBSWY3DPEHPK3PXP");
    }

    /**
     * Byte compatibility across the ws25 refactor onto {@link AesGcm}: a
     * ciphertext in the stored format (fixed IV 00..0b, produced outside
     * this code base with the same SHA-256 key derivation) still decrypts.
     */
    @Test
    void decryptsCiphertextsStoredBeforeTheSharedAesGcmHelper() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY, JWT_SECRET, false);

        assertThat(cipher.decrypt(
                "AAECAwQFBgcICQoL:VEnx6QPjAnRLk14nqERZ5NGcXnmtnufQvoYPOse9H04="))
                .isEqualTo("JBSWY3DPEHPK3PXP");
    }

    /** Different keys must not decrypt each other's ciphertexts. */
    @Test
    void ciphertextsAreKeyBound() {
        MfaSecretCipher one = new MfaSecretCipher(KEY, JWT_SECRET, false);
        MfaSecretCipher other = new MfaSecretCipher(KEY.replace('0', 'f'), JWT_SECRET, false);

        String stored = one.encrypt("JBSWY3DPEHPK3PXP");
        assertThatThrownBy(() -> other.decrypt(stored))
                .isInstanceOf(IllegalStateException.class);
    }
}
