package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.application.privacy.LogSafe;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.DestinationGuard;
import com.modocument.connector.core.spi.SecretMaterial;
import com.modocument.domain.model.integration.ConnectionMode;

/**
 * The server's side of the connector-core seams (docs/integration/
 * connector-sharing.md): maps the server's SPI records to the library's and
 * back. The {@link DestinationGuard} is the bank's {@link EgressGuard}
 * (bank egress rules + the global outbound host policy) for the context's
 * bank; the secret is the vault material {@code IntegrationConnectors}
 * opened; a DIRECT connection requires its secret keys at validation, an
 * AGENT one does not (the bank's agent holds that secret).
 */
final class CoreBridge {

    private CoreBridge() {
    }

    static com.modocument.connector.core.spi.ConnectionContext core(ConnectionContext connection,
                                                                    EgressGuard egress) {
        int bankId = connection.bankId();
        DestinationGuard guard = (host, port) -> egress.checkDestination(bankId, host, port);
        return new com.modocument.connector.core.spi.ConnectionContext(String.valueOf(bankId),
                connection.connectionId(), connection.name(), ConnectionType.valueOf(connection.type().name()),
                connection.mode() == ConnectionMode.DIRECT, connection.config(),
                SecretMaterial.of(connection.secret().values()), guard);
    }

    static com.modocument.connector.core.spi.OutboundBatch core(OutboundBatch batch) {
        return new com.modocument.connector.core.spi.OutboundBatch(batch.idempotencyKey(),
                batch.kind() == null ? null : batch.kind().name(),
                batch.format() == null ? null : batch.format().name(), batch.records(),
                batch.fileName(), batch.contentType(), batch.fileContent());
    }

    static ReadBatch server(com.modocument.connector.core.spi.ReadBatch batch) {
        return new ReadBatch(batch.records(), batch.files().stream()
                .map(file -> new ReadBatch.InboundFile(file.remotePath(), file.fileName(), file.content()))
                .toList(), batch.nextCursor(), batch.hasMore(), batch.notes(), batch.acknowledgement());
    }

    static WriteResult server(com.modocument.connector.core.spi.WriteResult result) {
        return new WriteResult(result.recordsWritten(), result.remoteReference());
    }

    /** The test result, its message through the server's full PII scrub (persisted, shown to admins). */
    static ConnectionTestResult server(com.modocument.connector.core.spi.ConnectionTestResult result) {
        return new ConnectionTestResult(result.ok(), LogSafe.scrub(result.message()),
                result.latencyMillis());
    }
}
