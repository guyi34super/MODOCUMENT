package com.modocument.agent.exec;

import com.modocument.connector.core.support.ConnectorLimits;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * DATABASE connections (PostgreSQL, MariaDB/MySQL, SQL Server, Oracle):
 * connector-core's {@link com.modocument.connector.core.db.DatabaseConnector}
 * — identifiers only, never free SQL; read-only watermark reads; INSERT or the
 * vendor's UPSERT in one transaction per write chunk; timeouts on every
 * connection and statement. A watermark read posts at most
 * {@value #PAGE_SIZE} records per page.
 */
public class DatabaseConnector extends CoreConnector {

    /** Opens JDBC connections; tests substitute an in-memory database. */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open(String url, Properties properties) throws SQLException;
    }

    static final int PAGE_SIZE = 500;

    /** The vendor's own driver class (never DriverManager discovery). */
    public DatabaseConnector() {
        this(LIMITS, com.modocument.connector.core.db.DatabaseConnector::openWithDriver);
    }

    public DatabaseConnector(ConnectionFactory connections) {
        this(LIMITS, (dialect, url, properties) -> connections.open(url, properties));
    }

    private DatabaseConnector(ConnectorLimits limits,
                              com.modocument.connector.core.db.DatabaseConnector.JdbcOpener opener) {
        super(new com.modocument.connector.core.db.DatabaseConnector(limits, opener));
    }

    @Override
    protected int pageLimit(TaskContext task, int max) {
        Object watermark = task.task().spec().get("watermarkColumn");
        return watermark == null || String.valueOf(watermark).isBlank() ? max : Math.min(max, PAGE_SIZE);
    }
}
