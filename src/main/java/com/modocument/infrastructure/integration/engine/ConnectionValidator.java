package com.modocument.infrastructure.integration.engine;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.OutboundAuthType;
import com.modocument.infrastructure.integration.connector.DatabaseIntegrationConnector;
import com.modocument.infrastructure.integration.connector.OutboundHttp;
import com.modocument.infrastructure.integration.connector.SftpIntegrationConnector;
import com.modocument.infrastructure.net.EgressTargets;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Connection-level validation (ws25) — what a connector checks before any
 * spec: the URL/host settings, the auth type allowed for the connection
 * type, and (DIRECT mode, when a new secret is given) that the secret holds
 * the keys that auth type needs. Messages are safe to show the admin.
 *
 * <p>Auth types per connection type: REST_API — the {@link OutboundAuthType}
 * names except HMAC_SIGNATURE; DATABASE — BASIC (user name in the config,
 * {@code password} secret); SFTP — BASIC with the config's {@code sftpAuth}
 * PASSWORD or PRIVATE_KEY; WEBHOOK — HMAC_SIGNATURE ({@code hmacSecret}).
 */
@Component
public class ConnectionValidator {

    /** Every secret key a connection may carry. */
    public static final Set<String> SECRET_KEYS = Set.of("username", "password", "token", "apiKey",
            "clientSecret", "clientCertPem", "clientKeyPem", "privateKeyPem", "passphrase",
            "hmacSecret");

    private final OutboundHttp http;
    private final DatabaseIntegrationConnector database;
    private final SftpIntegrationConnector sftp;

    public ConnectionValidator(OutboundHttp http, DatabaseIntegrationConnector database,
                               SftpIntegrationConnector sftp) {
        this.http = http;
        this.database = database;
        this.sftp = sftp;
    }

    /** The auth type a connection of this type may use (normalized), else 400. */
    public static String authTypeFor(ConnectionType type, String requested) {
        String auth = requested == null || requested.isBlank() ? null
                : requested.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case REST_API -> {
                String value = auth == null ? "NONE" : auth;
                try {
                    OutboundAuthType parsed = OutboundAuthType.valueOf(value);
                    if (parsed == OutboundAuthType.HMAC_SIGNATURE) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("A REST_API connection's authType must be NONE, "
                            + "BASIC, BEARER, API_KEY_HEADER, OAUTH2_CLIENT_CREDENTIALS or MTLS.");
                }
                yield value;
            }
            case DATABASE -> {
                if (auth != null && !auth.equals("BASIC") && !auth.equals("PASSWORD")) {
                    throw new IllegalArgumentException("A DATABASE connection's authType is BASIC.");
                }
                yield "BASIC";
            }
            case SFTP -> {
                if (auth != null && !Set.of("BASIC", "PASSWORD", "PRIVATE_KEY").contains(auth)) {
                    throw new IllegalArgumentException("An SFTP connection's authType is BASIC "
                            + "(sftpAuth picks PASSWORD or PRIVATE_KEY).");
                }
                yield auth == null ? "BASIC" : auth;
            }
            case WEBHOOK -> {
                if (auth != null && !auth.equals("HMAC_SIGNATURE")) {
                    throw new IllegalArgumentException("A WEBHOOK connection's authType is HMAC_SIGNATURE.");
                }
                yield "HMAC_SIGNATURE";
            }
        };
    }

    /**
     * Validates the connection settings; {@code secretGiven}: the context
     * carries a NEW secret whose keys must suit the auth type.
     */
    public void validate(ConnectionContext connection, boolean secretGiven) {
        for (Map.Entry<String, String> entry : connection.config().entrySet()) {
            if (entry.getValue() != null && entry.getValue().length() > 8000) {
                throw new IllegalArgumentException("The setting '" + entry.getKey() + "' is too long.");
            }
            if (SECRET_KEYS.contains(entry.getKey()) && !entry.getKey().equals("username")) {
                throw new IllegalArgumentException("'" + entry.getKey() + "' is a secret: send it in "
                        + "'secret', never in 'config'.");
            }
        }
        ConnectionContext checked = secretGiven ? connection
                : new ConnectionContext(connection.bankId(), connection.connectionId(), connection.name(),
                connection.type(), com.modocument.domain.model.integration.ConnectionMode.AGENT,
                connection.config(), connection.secret());
        refuseMetadataDestination(connection);
        switch (connection.type()) {
            case REST_API -> {
                http.baseUri(checked);
                http.validateAuth(checked, OutboundHttp.authType(checked));
            }
            case WEBHOOK -> {
                http.baseUri(checked);
                if (secretGiven && checked.mode() == com.modocument.domain.model.integration.ConnectionMode.DIRECT
                        && (checked.secret().get("hmacSecret") == null
                        || checked.secret().get("hmacSecret").length() < 16)) {
                    throw new IllegalArgumentException("The secret needs an 'hmacSecret' of at least "
                            + "16 characters.");
                }
            }
            case DATABASE -> {
                database.target(checked);
                if (secretGiven && checked.mode() == com.modocument.domain.model.integration.ConnectionMode.DIRECT
                        && checked.secret().get("password") == null) {
                    throw new IllegalArgumentException("The secret needs a 'password'.");
                }
            }
            case SFTP -> sftp.validate(checked, Map.of(), false);
        }
    }

    /**
     * A cloud metadata / credential endpoint (IMDS, the ECS task endpoint)
     * written as an IP literal is refused when the connection is SUBMITTED,
     * not only when the connector connects (BUG-06; no egress rule can ever
     * open these). Host names are not resolved here; one that resolves to
     * such an address is still refused by the EgressGuard at connect time.
     */
    private void refuseMetadataDestination(ConnectionContext connection) {
        String host = switch (connection.type()) {
            case REST_API, WEBHOOK -> {
                String url = connection.config("url") != null ? connection.config("url")
                        : connection.config("baseUrl");
                try {
                    yield url == null ? null : java.net.URI.create(url.trim()).getHost();
                } catch (IllegalArgumentException e) {
                    yield null; // malformed: the type-specific checks report it
                }
            }
            case DATABASE, SFTP -> connection.config("host");
        };
        if (host == null || host.isBlank()) {
            return;
        }
        String bare = host.trim();
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }
        if (EgressTargets.isIpLiteral(bare) && EgressTargets.isMetadata(EgressTargets.literal(bare))) {
            throw new IllegalArgumentException("The destination " + host + " is a cloud metadata "
                    + "endpoint and can never be a connection target.");
        }
    }
}
