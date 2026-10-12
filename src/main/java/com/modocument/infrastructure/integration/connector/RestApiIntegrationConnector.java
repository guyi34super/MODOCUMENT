package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.connector.core.rest.RestApiConnector;
import com.modocument.domain.model.integration.ConnectionType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * REST_API connector bean (ws25): the server's adapter over connector-core's
 * {@link RestApiConnector} (pagination NONE/PAGE/CURSOR/LINK, records path,
 * outbound auth, POST/PUT with the Idempotency-Key, no redirects) — the same
 * code the on-prem agent runs — with the bank's egress guard and the vault
 * secret of the connection.
 */
@Component
public class RestApiIntegrationConnector implements IntegrationConnector {

    private final OutboundHttp http;
    private final RestApiConnector core;

    public RestApiIntegrationConnector(OutboundHttp http) {
        this.http = http;
        this.core = new RestApiConnector(http.core());
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.REST_API;
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
                          String cursor, int maxRecords) throws Exception {
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
