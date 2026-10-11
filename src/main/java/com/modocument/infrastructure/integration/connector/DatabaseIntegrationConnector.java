package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.connector.core.db.DatabaseConnector;
import com.modocument.connector.core.db.DbTarget;
import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.domain.model.integration.ConnectionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DATABASE connector bean (ws25): the server's adapter over connector-core's
 * {@link DatabaseConnector} (PostgreSQL, Oracle, SQL Server, MariaDB/MySQL;
 * identifiers only, never free SQL; per-vendor URL with allow-listed options;
 * read-only watermark reads; one-transaction INSERT/UPSERT batches) — the
 * same code the on-prem agent runs — with the bank's egress guard checked
 * right before the driver connects and the vault secret's {@code password}.
 */
@Component
public class DatabaseIntegrationConnector implements IntegrationConnector {

    private final EgressGuard egress;
    private final DatabaseConnector core;

    @Autowired
    public DatabaseIntegrationConnector(EgressGuard egress, IntegrationConnectorProperties properties) {
        this(egress, properties.limits(), DatabaseConnector::openWithDriver);
    }

    /** {@code opener} replaces the driver in tests to reach an embedded database. */
    public DatabaseIntegrationConnector(EgressGuard egress, ConnectorLimits limits,
                                        DatabaseConnector.JdbcOpener opener) {
        this.egress = egress;
        this.core = new DatabaseConnector(limits, opener);
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.DATABASE;
    }

    /** The validated connection target (everything but the secret). */
    public DbTarget target(ConnectionContext connection) {
        return core.target(CoreBridge.core(connection, egress));
    }

    @Override
    public void validate(ConnectionContext connection, Map<String, Object> spec, boolean outbound) {
        core.validate(CoreBridge.core(connection, egress), spec, outbound);
    }

    @Override
    public ConnectionTestResult test(ConnectionContext connection) {
        return CoreBridge.server(core.test(CoreBridge.core(connection, egress)));
    }

    @Override
    public ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec,
                          String cursor, int maxRecords) throws Exception {
        return CoreBridge.server(core.read(CoreBridge.core(connection, egress), sourceSpec, cursor,
                maxRecords));
    }

    @Override
    public WriteResult write(ConnectionContext connection, Map<String, Object> targetSpec,
                             OutboundBatch batch) throws Exception {
        return CoreBridge.server(core.write(CoreBridge.core(connection, egress), targetSpec,
                CoreBridge.core(batch)));
    }
}
