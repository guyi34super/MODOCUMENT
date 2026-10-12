package com.modocument.infrastructure.integration.connector;

import com.modocument.application.privacy.LogSafe;

/**
 * Connector failures as messages safe to show an admin, store on a run and
 * log (ws25): connector-core's classification
 * ({@link com.modocument.connector.core.support.ConnectorErrors}) with the
 * server's full PII/credential scrub ({@link LogSafe#scrub}) applied.
 */
public final class ConnectorErrors {

    private ConnectorErrors() {
    }

    public static String safeMessage(Throwable error) {
        return com.modocument.connector.core.support.ConnectorErrors.safeMessage(error, LogSafe::scrub);
    }
}
