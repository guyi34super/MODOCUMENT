package com.modocument.infrastructure.security.totp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** RFC 6238's SHA-1 test secret: ASCII "12345678901234567890". */
    private static final byte[] RFC_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /**
     * RFC 6238 Appendix B vectors (SHA-1 rows, 8 digits): epoch second →
     * expected TOTP. Exercises the same HOTP core the production 6-digit
     * path uses.
     */
    @Test
    void rfc6238AppendixBVectors() {
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < times.length; i++) {
            assertThat(Totp.hotp(RFC_SECRET, Totp.counterAt(times[i]), 8))
                    .as("T=%d", times[i])
                    .isEqualTo(expected[i]);
        }
    }

    /** RFC 4226 Appendix D (HOTP, 6 digits) — the production digit width. */
    @Test
    void rfc4226AppendixDVectors() {
        String[] expected = {"755224", "287082", "359152", "969429", "338314",
                "254676", "287922", "162583", "399871", "520489"};
        for (int counter = 0; counter < expected.length; counter++) {
            assertThat(Totp.hotp(RFC_SECRET, counter, 6)).isEqualTo(expected[counter]);
        }
    }

    @Test
    void verifyAcceptsTheCurrentStepAndReportsItsCounter() {
        long now = 1_700_000_000L;
        String code = Totp.codeAt(RFC_SECRET, Totp.counterAt(now));

        assertThat(Totp.verify(RFC_SECRET, code, now)).isEqualTo(Totp.counterAt(now));
    }

    @Test
    void verifyAcceptsOneStepOfClockSkewEachWay() {
        long now = 1_700_000_000L;
        String previous = Totp.codeAt(RFC_SECRET, Totp.counterAt(now) - 1);
        String next = Totp.codeAt(RFC_SECRET, Totp.counterAt(now) + 1);

        assertThat(Totp.verify(RFC_SECRET, previous, now)).isEqualTo(Totp.counterAt(now) - 1);
        assertThat(Totp.verify(RFC_SECRET, next, now)).isEqualTo(Totp.counterAt(now) + 1);
    }

    @Test
    void verifyRejectsOutsideTheWindowAndGarbage() {
        long now = 1_700_000_000L;
        String stale = Totp.codeAt(RFC_SECRET, Totp.counterAt(now) - 2);

        assertThat(Totp.verify(RFC_SECRET, stale, now)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "000000", now)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "not-a-code", now)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, null, now)).isEqualTo(-1);
    }

    @Test
    void verifyToleratesSurroundingWhitespace() {
        long now = 1_700_000_000L;
        String code = Totp.codeAt(RFC_SECRET, Totp.counterAt(now));

        assertThat(Totp.verify(RFC_SECRET, " " + code + " ", now))
                .isEqualTo(Totp.counterAt(now));
    }
}
