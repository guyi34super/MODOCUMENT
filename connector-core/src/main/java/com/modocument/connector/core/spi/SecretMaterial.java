package com.modocument.connector.core.spi;

import java.util.Map;

/**
 * The secret-resolver seam: the opened secret of one connection, for one
 * call. The server builds it from its vault ({@code SecretVault.open}), the
 * agent from its local store ({@code LocalSecrets}, which also answers a
 * single unnamed secret under any key). Keys the connectors read:
 * {@code password}, {@code username}, {@code token}, {@code apiKey},
 * {@code clientSecret}, {@code clientCertPem}, {@code clientKeyPem},
 * {@code privateKeyPem}, {@code passphrase}, {@code hmacSecret}.
 *
 * <p>Implementations never print a value from {@code toString()}.
 */
@FunctionalInterface
public interface SecretMaterial {

    SecretMaterial EMPTY = of(Map.of());

    /** The value under {@code key}, or null. */
    String get(String key);

    /**
     * Whether the secret names {@code key} itself (not through a fallback such
     * as the agent's single unnamed secret): decides between credentials,
     * e.g. SFTP key versus password auth.
     */
    default boolean contains(String key) {
        return get(key) != null;
    }

    /** A map-backed secret whose {@code toString()} lists the keys only. */
    static SecretMaterial of(Map<String, String> values) {
        return new MapSecret(values == null ? Map.of() : Map.copyOf(values));
    }

    /** Map-backed secret material; values redacted from {@code toString()}. */
    record MapSecret(Map<String, String> values) implements SecretMaterial {

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public String toString() {
            return "SecretMaterial" + values.keySet() + " (values redacted)";
        }
    }
}
