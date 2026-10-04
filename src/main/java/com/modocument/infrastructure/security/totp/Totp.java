package com.modocument.infrastructure.security.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30-second steps, 6 digits), dependency-free.
 * Verification accepts a clock skew of ±1 step and answers with the MATCHED
 * step counter, so the caller can persist it and reject the same or an older
 * counter next time (replay prevention, SEC-022). Code comparison is
 * constant-time.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    /** Accepted clock skew, in steps, on each side of "now". */
    public static final int WINDOW = 1;

    private static final String HMAC_ALGORITHM = "HmacSHA1";

    private Totp() {
    }

    /** The 6-digit code for the given RFC 6238 time-step counter. */
    public static String codeAt(byte[] secret, long counter) {
        return hotp(secret, counter, DIGITS);
    }

    /** The current step counter for an epoch-seconds instant. */
    public static long counterAt(long epochSeconds) {
        return epochSeconds / STEP_SECONDS;
    }

    /**
     * Verifies {@code code} against the ±{@link #WINDOW} steps around
     * {@code epochSeconds}.
     *
     * @return the matched step counter, or -1 when no step matches
     */
    public static long verify(byte[] secret, String code, long epochSeconds) {
        if (secret == null || code == null) {
            return -1;
        }
        String presented = code.trim();
        long center = counterAt(epochSeconds);
        long matched = -1;
        for (long counter = center - WINDOW; counter <= center + WINDOW; counter++) {
            if (counter < 0) {
                continue;
            }
            // Every candidate step is computed and compared (no early exit on
            // match) so verification time does not reveal WHICH step matched.
            boolean equal = MessageDigest.isEqual(
                    hotp(secret, counter, DIGITS).getBytes(StandardCharsets.US_ASCII),
                    presented.getBytes(StandardCharsets.US_ASCII));
            if (equal && matched == -1) {
                matched = counter;
            }
        }
        return matched;
    }

    /** RFC 4226 HOTP truncation; package-visible so tests can run RFC vectors. */
    static String hotp(byte[] secret, long counter, int digits) {
        byte[] message = new byte[8];
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (counter & 0xFF);
            counter >>>= 8;
        }
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            hash = mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA1 ships with every JRE; a bad key means an empty secret,
            // which is a programming error upstream.
            throw new IllegalStateException("HMAC-SHA1 unavailable or key invalid.", e);
        }
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int code = binary % (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", code);
    }
}
