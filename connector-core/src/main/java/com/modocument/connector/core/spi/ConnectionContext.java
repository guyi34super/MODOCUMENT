package com.modocument.connector.core.spi;

import java.util.Map;
import java.util.Objects;

/**
 * Everything a connector needs about one connection for one call: the
 * non-secret config (host, port, baseUrl, vendor, database, schema, sslmode,
 * folder, hostKeyFingerprint, authType, ...), the opened secret and the
 * caller's {@link DestinationGuard}.
 *
 * @param tenant         the caller's partition key for anything cached across
 *                       calls (the OAuth2 token cache): the server passes the
 *                       bank id, the agent a constant
 * @param secretRequired true when validation must find the secret keys the
 *                       auth type needs in {@code secret}: the server's DIRECT
 *                       connections, whose secret MoDocument holds. False for
 *                       a connection whose secret lives with the bank — the
 *                       server validating an AGENT connection, and the agent
 *                       itself: there a missing key fails at call time
 *                       ("The connection secret has no 'token'"), and a
 *                       database password stays optional.
 */
public record ConnectionContext(String tenant, long connectionId, String name, ConnectionType type,
                                boolean secretRequired, Map<String, String> config,
                                SecretMaterial secret, DestinationGuard guard) {

    public ConnectionContext {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(guard, "guard");
        config = config == null ? Map.of() : Map.copyOf(config);
        secret = secret == null ? SecretMaterial.EMPTY : secret;
    }

    public String config(String key) {
        return config.get(key);
    }

    /** The same connection carrying {@code opened}. */
    public ConnectionContext withSecret(SecretMaterial opened) {
        return new ConnectionContext(tenant, connectionId, name, type, secretRequired, config, opened,
                guard);
    }

    /** The secret value under {@code key}; throws when it is missing or blank (call-time check). */
    public String requireSecret(String key) {
        String value = secret.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The connection secret has no '" + key + "'.");
        }
        return value;
    }
}
