package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.connector.core.webhook.WebhookConnector;
import com.modocument.domain.model.integration.ConnectionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/**
 * WEBHOOK connector bean (ws25), write only: the server's adapter over
 * connector-core's {@link WebhookConnector} (a JSON envelope signed with the
 * connection's {@code hmacSecret} at send time, see
 * {@link com.modocument.connector.core.webhook.WebhookSigner}), with the
 * bank's egress guard and the vault secret.
 */
@Component
public class WebhookIntegrationConnector implements IntegrationConnector {

    private final OutboundHttp http;
    private final WebhookConnector core;

    @Autowired
    public WebhookIntegrationConnector(OutboundHttp http) {
        this(http, Clock.systemUTC());
    }

    public WebhookIntegrationConnector(OutboundHttp http, Clock clock) {
        this.http = http;
        this.core = new WebhookConnector(http.core(), clock);
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.WEBHOOK;
    }

    @Override
    public void validate(ConnectionContext connection, Map<String, Object> spec, boolean outbound) {
        core.validate(CoreBridge.core(connection, http.egress()), spec, outbound);
    }

    @Override
    public ConnectionTestResult test(ConnectionContext connection) {
        return CoreBridge.server(core.test(CoreBridge.core(connection, http.egress())));
    }

    @Override
    public ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec,
                          String cursor, int maxRecords) {
        return CoreBridge.server(core.read(CoreBridge.core(connection, http.egress()), sourceSpec,
                cursor, maxRecords));
    }

    @Override
    public WriteResult write(ConnectionContext connection, Map<String, Object> targetSpec,
                             OutboundBatch batch) throws Exception {
        return CoreBridge.server(core.write(CoreBridge.core(connection, http.egress()), targetSpec,
                CoreBridge.core(batch)));
    }
}
