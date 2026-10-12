package com.modocument.infrastructure.export;

import com.modocument.application.dto.DatabaseExportRequest;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.domain.model.integration.DbVendor;
import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.connector.core.db.DatabaseConnector;
import com.modocument.connector.core.db.DbDialect;
import com.modocument.infrastructure.integration.connector.IntegrationConnectorProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sends an ingested table to a user-supplied database over JDBC (ws28
 * hardening). The target arrives as STRUCTURED fields (vendor, host, port,
 * database, schema, sslmode) — never as a raw JDBC URL — and the URL is built
 * by the integration hub's {@link DbDialect}: only allow-listed driver
 * options reach it, and the connect/login and socket timeouts are ours
 * ({@code modocument.integration.connector.*}, the same limits as the hub's
 * DATABASE connector); every statement also carries a query timeout. The
 * destination passes the bank-aware {@link EgressGuard} (metadata endpoints
 * always refused; internal addresses only with an approved egress rule of
 * the caller's bank) before a socket is opened.
 *
 * <p>Values only ever travel as bound parameters; table/column/schema
 * identifiers are validated against a strict pattern AND quoted the
 * vendor's way, so user input can never splice SQL. Credentials are used for
 * this one export and never stored or logged. The insert is all-or-nothing
 * (one transaction; a constraint violation halfway rolls the batch back).
 */
@Service
public class DatabaseExportService {

    /** Opens the JDBC connection; replaced in tests to reach an embedded database. */
    @FunctionalInterface
    public interface ConnectionOpener {
        Connection open(DbDialect dialect, String url, Properties properties) throws SQLException;
    }

    /** The validated destination (everything but the credentials). */
    public record Target(DbDialect dialect, String host, int port, String database, String schema,
                         Map<String, String> options) {

        /** The JDBC URL, built by the dialect with our timeouts. */
        public String url(ConnectorLimits limits) {
            return dialect.url(host, port, database, options, limits);
        }

        /** Safe to persist in export info and the audit trail: no credentials. */
        public String describe() {
            return dialect.name().toLowerCase(Locale.ROOT) + "://" + host + ":" + port + "/"
                    + database + (schema == null ? "" : " (schema " + schema + ")");
        }
    }

    private static final Set<String> SSL_MODES =
            Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");

    private final EgressGuard egress;
    private final ConnectorLimits limits;
    private final ConnectionOpener opener;

    @Autowired
    public DatabaseExportService(EgressGuard egress, IntegrationConnectorProperties properties,
                                 ObjectProvider<ConnectionOpener> opener) {
        this(egress, properties.limits(), opener.getIfAvailable(() -> DatabaseExportService::openWithDriver));
    }

    public DatabaseExportService(EgressGuard egress, ConnectorLimits limits, ConnectionOpener opener) {
        this.egress = egress;
        this.limits = limits;
        this.opener = opener;
    }

    /**
     * Validates the request's destination (400 on anything unacceptable,
     * including the removed {@code jdbcUrl}). Touches no network.
     */
    public Target target(DatabaseExportRequest request) {
        if (request.isLegacyJdbcUrlSent()) {
            throw new IllegalArgumentException("jdbcUrl is no longer accepted (since 1.2.0). Send the "
                    + "target as vendor (POSTGRESQL, ORACLE, SQLSERVER, MARIADB), host, port "
                    + "(optional), database, schema (optional) and sslmode (optional).");
        }
        if (request.getTableName() == null || request.getTableName().isBlank()) {
            throw new IllegalArgumentException("'tableName' (or 'table') is required.");
        }
        JdbcIdentifiers.requireValidIdentifier(request.getTableName().trim(), "tableName");
        DbVendor vendor = parseVendor(request.getVendor());
        DbDialect dialect = DbDialect.valueOf(vendor.name());
        String host = DbDialect.requireHost(trim(request.getHost()));
        String database = DbDialect.requireDatabase(trim(request.getDatabase()));
        String schema = trim(request.getSchema());
        if (schema != null) {
            JdbcIdentifiers.requireValidIdentifier(schema, "schema");
        }
        int port = request.getPort() == null ? dialect.defaultPort() : request.getPort();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("'port' must be between 1 and 65535.");
        }
        Map<String, String> requested = new LinkedHashMap<>();
        String sslmode = trim(request.getSslmode());
        if (sslmode != null) {
            sslmode = sslmode.toLowerCase(Locale.ROOT);
            if (!SSL_MODES.contains(sslmode)) {
                throw new IllegalArgumentException("'sslmode' must be one of "
                        + String.join(", ", new java.util.TreeSet<>(SSL_MODES)) + ".");
            }
            requested.put("sslmode", sslmode);
        }
        translateSslMode(vendor, requested);
        String username = request.getUsername();
        if (username != null && username.chars().anyMatch(c -> c < 0x20)) {
            throw new IllegalArgumentException("'username' is not a valid database user name.");
        }
        return new Target(dialect, host, port, database, schema, dialect.options(requested));
    }

    /** Exports all rows to the caller's bank's approved destination; returns the row count. */
    public int export(TableData table, DatabaseExportRequest request, int bankId) {
        if (table == null || table.isEmpty()) {
            throw new IllegalStateException("There is no data to export.");
        }
        Target target = target(request);
        String tableName = JdbcIdentifiers.requireValidIdentifier(request.getTableName().trim(), "tableName");
        List<String> columns = table.getColumns();
        for (String column : columns) {
            JdbcIdentifiers.requireValidIdentifier(column, "column");
        }
        // SSRF guard: the bank-aware egress check, before any socket is opened.
        try {
            egress.checkDestination(bankId, target.host(), target.port());
        } catch (SecurityException refused) {
            throw new IllegalArgumentException(refused.getMessage());
        }
        DbDialect dialect = target.dialect();
        String qualified = dialect.qualified(target.schema(), tableName);
        String insertSql = dialect.insert(qualified, columns);
        List<Object[]> batch = table.getRows().stream()
                .map(row -> columns.stream().map(c -> (Object) row.getOrDefault(c, "")).toArray())
                .toList();
        boolean createIfMissing = request.getCreateTableIfMissing() == null
                || request.getCreateTableIfMissing();

        Properties properties = new Properties();
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            properties.setProperty("user", request.getUsername());
        }
        if (request.getPassword() != null) {
            properties.setProperty("password", request.getPassword());
        }
        dialect.driverProperties(properties, limits);

        try (Connection connection = opener.open(dialect, target.url(limits), properties)) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.setQueryTimeout(limits.readTimeoutSeconds());
            try {
                // All-or-nothing: a constraint violation halfway through a
                // batch must not leave the target table holding a partial
                // export that a retry would then duplicate.
                if (!tableExists(connection, target.schema(), tableName)) {
                    if (!createIfMissing) {
                        throw new IllegalStateException(
                                "Table '" + tableName + "' does not exist in the target database.");
                    }
                    jdbc.execute(createTableSql(dialect, qualified, columns));
                }
                jdbc.batchUpdate(insertSql, batch);
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            // Never echo the driver message: it can quote the URL or the host's banner.
            throw new DataAccessResourceFailureException("The export to the target database failed"
                    + (e.getSQLState() == null ? "." : " (SQLState " + e.getSQLState() + ")."), e);
        }
        return batch.size();
    }

    /** {@link Target#describe()} of the request's destination (validates it first). */
    public String describeTarget(DatabaseExportRequest request) {
        return target(request).describe();
    }

    private static boolean tableExists(Connection connection, String schema, String tableName)
            throws SQLException {
        // Unquoted identifiers may be stored case-folded; check common variants.
        for (String candidate : List.of(tableName, tableName.toUpperCase(Locale.ROOT),
                tableName.toLowerCase(Locale.ROOT))) {
            try (ResultSet rs = connection.getMetaData()
                    .getTables(null, schema, candidate, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Text columns every vendor accepts without hitting its row-size limit. */
    static String createTableSql(DbDialect dialect, String qualifiedTable, List<String> columns) {
        String type = switch (dialect) {
            case POSTGRESQL -> "VARCHAR(4000)";
            case ORACLE -> "VARCHAR2(4000)";
            case SQLSERVER -> "NVARCHAR(4000)";
            // InnoDB's 64 KB row limit: TEXT lives off-row.
            case MARIADB -> "TEXT";
        };
        return "CREATE TABLE " + qualifiedTable + " ("
                + columns.stream().map(c -> dialect.quote(c) + " " + type)
                .collect(Collectors.joining(", "))
                + ")";
    }

    /**
     * The UI offers one {@code sslmode} (PostgreSQL vocabulary) for every
     * vendor; it maps to each driver's own option, as the hub's DATABASE
     * connector does. PostgreSQL keeps it as is.
     */
    private static void translateSslMode(DbVendor vendor, Map<String, String> options) {
        String mode = options.get("sslmode");
        if (mode == null || vendor == DbVendor.POSTGRESQL) {
            return;
        }
        options.remove("sslmode");
        boolean off = mode.equals("disable") || mode.equals("allow") || mode.equals("prefer");
        boolean verify = mode.startsWith("verify");
        switch (vendor) {
            case ORACLE -> options.put("protocol", off ? "tcp" : "tcps");
            case SQLSERVER -> {
                options.put("encrypt", off ? "false" : "true");
                options.put("trustServerCertificate", off || verify ? "false" : "true");
            }
            case MARIADB -> options.put("sslMode", off ? "disable"
                    : mode.equals("verify-full") ? "verify-full" : verify ? "verify-ca" : "trust");
            default -> { }
        }
    }

    private static DbVendor parseVendor(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'vendor' is required: one of POSTGRESQL, ORACLE, "
                    + "SQLSERVER, MARIADB.");
        }
        try {
            return DbVendor.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'vendor' must be one of POSTGRESQL, ORACLE, "
                    + "SQLSERVER, MARIADB.");
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static Connection openWithDriver(DbDialect dialect, String url, Properties properties)
            throws SQLException {
        return DatabaseConnector.openWithDriver(dialect, url, properties);
    }
}
