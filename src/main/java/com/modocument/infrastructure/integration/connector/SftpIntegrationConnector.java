package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.connector.core.sftp.SftpConnector;
import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.domain.model.integration.ConnectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * SFTP connector bean (ws25): the server's adapter over connector-core's
 * {@link SftpConnector} (pinned host key, password or private-key auth,
 * folder confinement, the size cap, temp-upload-then-rename writes) — the
 * same code the on-prem agent runs — with the bank's egress guard and the
 * vault secret.
 *
 * <p>Reads are two-phase: {@link #read} moves nothing; the flow runner
 * acknowledges the files whose ingestion jobs exist
 * ({@link ReadBatch#acknowledge}), and only those go to {@code processed/}
 * (or are deleted). Files over the size cap stay in place and are logged.
 */
@Component
public class SftpIntegrationConnector implements IntegrationConnector {

    private static final Logger log = LoggerFactory.getLogger(SftpIntegrationConnector.class);

    private final EgressGuard egress;
    private final SftpConnector core;

    @Autowired
    public SftpIntegrationConnector(EgressGuard egress, IntegrationConnectorProperties properties) {
        this(egress, properties.limits(), Clock.systemUTC());
    }

    public SftpIntegrationConnector(EgressGuard egress, ConnectorLimits limits, Clock clock) {
        this.egress = egress;
        this.core = new SftpConnector(limits, clock);
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.SFTP;
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
        ReadBatch batch = CoreBridge.server(core.read(CoreBridge.core(connection, egress), sourceSpec,
                cursor, maxRecords));
        for (String note : batch.notes()) {
            log.warn("SFTP read of connection {}: {}", connection.connectionId(), note);
        }
        return batch;
    }

    /**
     * The matching files of a folder, by name, WITHOUT reading or moving
     * anything (the flow preview). Returns names and sizes only.
     */
    public List<Map<String, Object>> listFiles(ConnectionContext connection,
                                               Map<String, Object> sourceSpec, int max)
            throws Exception {
        return core.listFiles(CoreBridge.core(connection, egress), sourceSpec, max);
    }

    @Override
    public WriteResult write(ConnectionContext connection, Map<String, Object> targetSpec,
                             OutboundBatch batch) throws Exception {
        return CoreBridge.server(core.write(CoreBridge.core(connection, egress), targetSpec,
                CoreBridge.core(batch)));
    }
}
