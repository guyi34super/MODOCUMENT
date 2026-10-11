package com.modocument.infrastructure.security.client;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random machine credentials (ws26). Identifiers use a lower-case
 * alphanumeric alphabet (URL-, header- and log-safe); secrets are 32 random
 * bytes, base64url without padding (43 characters, 256 bits).
 * <ul>
 *   <li>client id: {@code mdc_} + 24 characters (≈124 bits) — public;</li>
 *   <li>API key prefix: {@code mdk_} + 16 characters — public half of
 *       {@code <prefix>.<secret>}, the lookup key;</li>
 *   <li>secret: shown once, stored only as an Argon2id hash.</li>
 * </ul>
 */
public final class ClientCredentials {

    public static final String CLIENT_ID_PREFIX = "mdc_";
    public static final String KEY_PREFIX_PREFIX = "mdk_";

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClientCredentials() {
    }

    public static String newClientId() {
        return CLIENT_ID_PREFIX + randomToken(24);
    }

    public static String newKeyPrefix() {
        return KEY_PREFIX_PREFIX + randomToken(16);
    }

    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What the bank system presents: {@code <prefix>.<secret>}. */
    public static String apiKey(String keyPrefix, String secret) {
        return keyPrefix + "." + secret;
    }

    static String randomToken(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
