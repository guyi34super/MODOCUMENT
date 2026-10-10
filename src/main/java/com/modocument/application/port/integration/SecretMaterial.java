package com.modocument.application.port.integration;

import java.util.Map;

/**
 * Opened connection secrets (ws25), e.g. {@code password}, {@code token},
 * {@code apiKey}, {@code clientSecret}, {@code privateKeyPem},
 * {@code clientCertPem}, {@code clientKeyPem}, {@code hmacSecret}. Lives in
 * memory for one call only; {@link #toString()} never prints a value.
 */
public record SecretMaterial(Map<String, String> values) {

    public static final SecretMaterial EMPTY = new SecretMaterial(Map.of());

    public SecretMaterial {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public String get(String key) {
        return values.get(key);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public String toString() {
        return "SecretMaterial" + values.keySet() + " (values redacted)";
    }
}
