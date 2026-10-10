package com.modocument.infrastructure.security.totp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RFC 4648 §10 test vectors (padding stripped — otpauth secrets carry none). */
class Base32Test {

    @Test
    void rfc4648Vectors() {
        assertThat(Base32.encode("".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(Base32.encode("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Base32.encode("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
        assertThat(Base32.encode("foo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6");
        assertThat(Base32.encode("foob".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YQ");
        assertThat(Base32.encode("fooba".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTB");
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void decodeReversesTheVectors() {
        assertThat(Base32.decode("MZXW6YTBOI")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base32.decode("MZXW6")).isEqualTo("foo".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void decodeToleratesPaddingWhitespaceAndLowercase() {
        assertThat(Base32.decode("mzxw6ytboi")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base32.decode("MZXW 6YTB-OI")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base32.decode("MZXW6YQ=")).isEqualTo("foob".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void decodeRejectsForeignCharacters() {
        assertThatThrownBy(() -> Base32.decode("MZXW0")) // '0' is not in the alphabet
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base32.decode("MZX!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsRandomSecrets() {
        SecureRandom random = new SecureRandom();
        for (int length = 1; length <= 40; length++) {
            byte[] secret = new byte[length];
            random.nextBytes(secret);
            assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
        }
    }
}
