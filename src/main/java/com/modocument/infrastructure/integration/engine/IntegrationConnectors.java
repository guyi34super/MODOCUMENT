package com.modocument.infrastructure.integration.engine;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The connector registry (one bean per {@link ConnectionType}) and the
 * factory of {@link ConnectionContext}s (ws25): the connection's non-secret
 * config plus its {@code authType}, and — for DIRECT mode only, for the
 * duration of one call — the secret opened through the {@link SecretVault}.
 */
@Component
public class IntegrationConnectors {

    private final Map<ConnectionType, IntegrationConnector> byType = new EnumMap<>(ConnectionType.class);
    private final SecretVault vault;

    public IntegrationConnectors(List<IntegrationConnector> connectors, SecretVault vault) {
        for (IntegrationConnector connector : connectors) {
            if (byType.put(connector.type(), connector) != null) {
                throw new IllegalStateException("Two connectors for " + connector.type());
            }
        }
        this.vault = vault;
    }

    public IntegrationConnector forType(ConnectionType type) {
        IntegrationConnector connector = byType.get(type);
        if (connector == null) {
            throw new IllegalStateException("No connector for " + type + ".");
        }
        return connector;
    }

    /** Config and opened secret (DIRECT); config only (AGENT, or {@code withSecret=false}). */
    public ConnectionContext context(IntegrationConnection connection, boolean withSecret) {
        Map<String, String> config = IntegrationJson.config(connection.getConfigJson());
        config.put("authType", connection.getAuthType());
        SecretMaterial secret = SecretMaterial.EMPTY;
        if (withSecret && connection.getMode() == ConnectionMode.DIRECT
                && connection.getSecretCiphertext() != null) {
            secret = vault.open(connection.getBankId(), new SecretVault.Sealed(
                    connection.getSecretCiphertext(),
                    connection.getSecretKeyVersion() == null ? 1 : connection.getSecretKeyVersion()));
        }
        return new ConnectionContext(connection.getBankId(), connection.getId(), connection.getName(),
                connection.getType(), connection.getMode(), config, secret);
    }

    /** A context from config + an explicit secret (validation of a pending change). */
    public static ConnectionContext context(int bankId, Long connectionId, String name,
                                            ConnectionType type, ConnectionMode mode,
                                            Map<String, String> config, String authType,
                                            SecretMaterial secret) {
        Map<String, String> withAuth = new java.util.LinkedHashMap<>(config);
        withAuth.put("authType", authType == null ? "NONE" : authType);
        return new ConnectionContext(bankId, connectionId == null ? 0 : connectionId, name, type,
                mode, withAuth, secret);
    }
}
