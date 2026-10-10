package com.modocument.infrastructure.security.totp;

/**
 * RFC 4648 Base32 (the alphabet authenticator apps expect for otpauth://
 * secrets). Dependency-free on purpose — pulling in commons-codec for forty
 * lines would be the heavier risk. Encoding emits no padding (authenticator
 * apps reject '='-padded secrets); decoding accepts padding, whitespace and
 * lowercase, so hand-typed secrets survive.
 */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int[] REVERSE = new int[128];

    static {
        java.util.Arrays.fill(REVERSE, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            REVERSE[ALPHABET[i]] = i;
            REVERSE[Character.toLowerCase(ALPHABET[i])] = i;
        }
    }

    private Base32() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET[(buffer >>> bits) & 0x1F]);
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        return out.toString();
    }

    /**
     * @throws IllegalArgumentException on any character outside the Base32
     *     alphabet (after stripping padding, spaces and hyphens)
     */
    public static byte[] decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("Base32 input is null.");
        }
        String cleaned = encoded.replace("=", "").replace(" ", "").replace("-", "");
        int outLength = cleaned.length() * 5 / 8;
        byte[] out = new byte[outLength];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            int value = c < 128 ? REVERSE[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 character.");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out[index++] = (byte) ((buffer >>> bits) & 0xFF);
            }
        }
        return out;
    }
}
