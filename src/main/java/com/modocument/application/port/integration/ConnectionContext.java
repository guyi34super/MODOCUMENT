package com.modocument.application.port.integration;

import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;

import java.util.Map;

/**
 * Everything a connector needs about one stored connection (ws25): the
 * non-secret config (host, port, baseUrl, vendor, database, schema, sslmode,
 * folder, hostKeyFingerprint, authType, …) and the opened secret material
 * (empty for AGENT mode on the server side and for auth type NONE).
 */
public record ConnectionContext(int bankId, long connectionId, String name, ConnectionType type,
                                ConnectionMode mode, Map<String, String> config,
                                SecretMaterial secret) {

    public ConnectionContext {
        config = config == null ? Map.of() : Map.copyOf(config);
        secret = secret == null ? SecretMaterial.EMPTY : secret;
    }

    public String config(String key) {
        return config.get(key);
    }
}
