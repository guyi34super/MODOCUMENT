package com.modocument.connector.core.db;

import com.modocument.connector.core.support.ConnectorLimits;

import java.util.Map;

/** A validated database connection target (everything but the secret). */
public record DbTarget(DbDialect dialect, String host, int port, String database, String schema,
                       Map<String, String> options, String username) {

    public String url(ConnectorLimits limits) {
        return dialect.url(host, port, database, options, limits);
    }
}
