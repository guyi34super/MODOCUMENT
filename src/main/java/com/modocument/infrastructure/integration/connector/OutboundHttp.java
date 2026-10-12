package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.connector.core.support.ConnectorLimits;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.util.Map;

/**
 * The server's HTTP transport for the REST_API and WEBHOOK connectors: ONE
 * connector-core {@link com.modocument.connector.core.rest.OutboundHttp}
 * (base-URL validation, outbound auth, the OAuth2 token cache, no
 * redirects, timeouts, response cap) per application, with every destination
 * checked by the bank's {@link EgressGuard}.
 */
@Component
public class OutboundHttp {

    private final EgressGuard egress;
    private final com.modocument.connector.core.rest.OutboundHttp core;

    @Autowired
    public OutboundHttp(EgressGuard egress, IntegrationConnectorProperties properties) {
        this(egress, properties.limits(), Clock.systemUTC());
    }

    public OutboundHttp(EgressGuard egress, ConnectorLimits limits, Clock clock) {
        this.egress = egress;
        this.core = new com.modocument.connector.core.rest.OutboundHttp(limits, clock);
    }

    com.modocument.connector.core.rest.OutboundHttp core() {
        return core;
    }

    EgressGuard egress() {
        return egress;
    }

    public ConnectorLimits limits() {
        return core.limits();
    }

    /** The connection's validated base URL ({@code baseUrl}, or {@code url} for WEBHOOK). */
    public URI baseUri(ConnectionContext connection) {
        return core.baseUri(CoreBridge.core(connection, egress));
    }

    /** Refuses a connection whose auth settings (or, for DIRECT, secret keys) are incomplete. */
    public void validateAuth(ConnectionContext connection) {
        com.modocument.connector.core.spi.ConnectionContext context = CoreBridge.core(connection, egress);
        core.validateAuth(context, com.modocument.connector.core.rest.OutboundHttp.authType(context));
    }

    /** {@code base} + a validated relative path + an encoded query. */
    public static URI resolve(URI base, String relativePath, Map<String, String> query) {
        return com.modocument.connector.core.rest.OutboundHttp.resolve(base, relativePath, query);
    }

    /** OAuth2 tokens currently cached (tests). */
    int cachedTokens() {
        return core.cachedTokens();
    }
}
