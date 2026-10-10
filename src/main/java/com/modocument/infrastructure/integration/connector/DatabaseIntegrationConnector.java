package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.DbVendor;
import com.modocument.domain.model.integration.DbWriteMode;
import com.modocument.infrastructure.export.JdbcIdentifiers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * DATABASE connector (ws25): PostgreSQL, Oracle, SQL Server and MariaDB/MySQL
 * (MariaDB Connector/J). No free SQL, ever — the bank's DBA exposes a table
 * or view and the flow names identifiers only.
 * <ul>
 *   <li><b>Connection</b>: the JDBC URL is built per vendor by
 *       {@link DbDialect} from {@code host, port, database} plus allow-listed
 *       options; the destination passes {@link EgressGuard} right before the
 *       driver connects; connect/login/socket timeouts are always set.</li>
 *   <li><b>Read</b> ({@code {table, columns:[…], watermarkColumn?}}): a
 *       {@code setReadOnly(true)} connection, a rolled-back transaction,
 *       {@code SELECT <columns> FROM [schema.]table [WHERE wm > ?] ORDER BY wm}
 *       with {@code maxRows} and a query timeout. The cursor is the last
 *       watermark value, typed ({@code N:}/{@code T:}/{@code Z:}/{@code D:}/
 *       {@code S:} prefix) so it binds with the column's own type.</li>
 *   <li><b>Write</b> ({@code {table, mode:"INSERT|UPSERT", keyColumns:[…],
 *       batchSize}}): a parameterised INSERT or the vendor upsert, the whole
 *       batch in ONE transaction (all or nothing), with a query timeout.</li>
 * </ul>
 * Connection config keys: {@code vendor, host, port, database, schema,
 * username} plus the vendor's allowed options ({@code sslmode} for
 * PostgreSQL, {@code protocol} for Oracle, {@code encrypt},
 * {@code trustServerCertificate}, {@code hostNameInCertificate} for SQL
 * Server, {@code sslMode} for MariaDB). Secret: {@code password}.
 */
@Component
public class DatabaseIntegrationConnector implements IntegrationConnector {

    static final Set<String> SOURCE_KEYS = Set.of("table", "columns", "watermarkColumn");
    static final Set<String> TARGET_KEYS = Set.of("table", "mode", "keyColumns", "batchSize");
    /** Connection config keys that are not vendor URL options. */
    static final Set<String> BASE_CONFIG_KEYS = Set.of("vendor", "host", "port", "database",
            "schema", "username", "authType");

    /** Opens a JDBC connection; replaced in tests to reach an embedded database. */
    @FunctionalInterface
    public interface JdbcOpener {
        Connection open(DbDialect dialect, String url, Properties properties) throws SQLException;
    }

    private final EgressGuard egress;
    private final ConnectorLimits limits;
    private final JdbcOpener opener;

    @Autowired
    public DatabaseIntegrationConnector(EgressGuard egress, IntegrationConnectorProperties properties) {
        this(egress, properties.limits(), DatabaseIntegrationConnector::openWithDriver);
    }

    public DatabaseIntegrationConnector(EgressGuard egress, ConnectorLimits limits, JdbcOpener opener) {
        this.egress = egress;
        this.limits = limits;
        this.opener = opener;
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.DATABASE;
    }

    // ---- validation -----------------------------------------------------------

    /** The validated connection target (everything but the secret). */
    public record Target(DbDialect dialect, String host, int port, String database, String schema,
                         Map<String, String> options, String username) {

        public String url(ConnectorLimits limits) {
            return dialect.url(host, port, database, options, limits);
        }
    }

    public Target target(ConnectionContext connection) {
        Map<String, String> config = connection.config();
        DbVendor vendor = Specs.enumValue(config, "vendor", DbVendor.class, null);
        if (vendor == null) {
            throw new IllegalArgumentException("The connection setting 'vendor' is required.");
        }
        DbDialect dialect = DbDialect.of(vendor);
        String host = DbDialect.requireHost(Specs.string(config, "host"));
        Integer port = Specs.integer(config, "port", 1, 65_535);
        String database = DbDialect.requireDatabase(Specs.string(config, "database"));
        String schema = Specs.string(config, "schema");
        if (schema != null) {
            JdbcIdentifiers.requireValidIdentifier(schema, "schema");
        }
        Map<String, String> requested = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (!BASE_CONFIG_KEYS.contains(key) && value != null && !value.isBlank()) {
                requested.put(key, value.trim());
            }
        });
        translateSslMode(vendor, requested);
        String username = Specs.string(config, "username");
        if (username != null && (username.length() > 128 || username.chars().anyMatch(c -> c < 0x20))) {
            throw new IllegalArgumentException("'username' is not a valid database user name.");
        }
        return new Target(dialect, host, port == null ? dialect.defaultPort() : port, database,
                schema, dialect.options(requested), username);
    }

    /**
     * The UI offers one {@code sslmode} (PostgreSQL vocabulary: disable,
     * require, verify-ca, verify-full) for every vendor; it maps to each
     * driver's own option. PostgreSQL keeps it as is.
     */
    static void translateSslMode(DbVendor vendor, Map<String, String> options) {
        String key = options.keySet().stream().filter(k -> k.equals("sslmode")).findFirst().orElse(null);
        if (key == null || vendor == DbVendor.POSTGRESQL) {
            return;
        }
        String mode = options.remove(key).toLowerCase(Locale.ROOT);
        boolean off = mode.equals("disable") || mode.equals("allow") || mode.equals("prefer");
        boolean verify = mode.startsWith("verify");
        switch (vendor) {
            case ORACLE -> options.putIfAbsent("protocol", off ? "tcp" : "tcps");
            case SQLSERVER -> {
                options.putIfAbsent("encrypt", off ? "false" : "true");
                options.putIfAbsent("trustServerCertificate", off || verify ? "false" : "true");
            }
            case MARIADB -> options.putIfAbsent("sslMode", off ? "disable"
                    : mode.equals("verify-full") ? "verify-full" : verify ? "verify-ca" : "trust");
            default -> { }
        }
    }

    @Override
    public void validate(ConnectionContext connection, Map<String, Object> spec, boolean outbound) {
        Target target = target(connection);
        if (connection.mode() == ConnectionMode.DIRECT && (connection.secret().get("password") == null)) {
            throw new IllegalArgumentException("The connection secret has no 'password'.");
        }
        if (outbound) {
            Specs.allowOnly(spec, "target spec", TARGET_KEYS);
            JdbcIdentifiers.requireValidIdentifier(Specs.requireString(spec, "table", "Target spec"),
                    "table");
            DbWriteMode mode = Specs.enumValue(spec, "mode", DbWriteMode.class, DbWriteMode.INSERT);
            List<String> keys = identifiers(Specs.stringList(spec, "keyColumns"), "keyColumns");
            if (mode == DbWriteMode.UPSERT && keys.isEmpty()) {
                throw new IllegalArgumentException("UPSERT needs at least one key column.");
            }
            Specs.integer(spec, "batchSize", 1, 10_000);
            target.dialect().quote(Specs.string(spec, "table"));
            return;
        }
        Specs.allowOnly(spec, "source spec", SOURCE_KEYS);
        JdbcIdentifiers.requireValidIdentifier(Specs.requireString(spec, "table", "Source spec"),
                "table");
        identifiers(Specs.stringList(spec, "columns"), "columns");
        String watermark = Specs.string(spec, "watermarkColumn");
        if (watermark != null) {
            JdbcIdentifiers.requireValidIdentifier(watermark, "watermarkColumn");
        }
    }

    private static List<String> identifiers(List<String> names, String what) {
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            JdbcIdentifiers.requireValidIdentifier(name, what);
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("'" + what + "' lists '" + name + "' twice.");
            }
        }
        return names;
    }

    // ---- test ---------------------------------------------------------------------

    @Override
    public ConnectionTestResult test(ConnectionContext connection) {
        long started = System.nanoTime();
        try {
            Target target = target(connection);
            try (Connection jdbc = open(connection, target)) {
                jdbc.setReadOnly(true);
                if (!jdbc.isValid(limits.connectTimeoutSeconds())) {
                    return ConnectionTestResult.failed("The database did not answer the validity check.",
                            elapsed(started));
                }
                DatabaseMetaData meta = jdbc.getMetaData();
                return ConnectionTestResult.ok("Connected to " + meta.getDatabaseProductName() + " "
                        + meta.getDatabaseProductVersion().lines().findFirst().orElse(""),
                        elapsed(started));
            }
        } catch (Exception e) {
            return ConnectionTestResult.failed(ConnectorErrors.safeMessage(e), elapsed(started));
        }
    }

    // ---- read -----------------------------------------------------------------------

    @Override
    public ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec,
                          String cursor, int maxRecords) throws Exception {
        validate(connection, sourceSpec, false);
        Target target = target(connection);
        DbDialect dialect = target.dialect();
        String table = dialect.qualified(target.schema(), Specs.string(sourceSpec, "table"));
        List<String> columns = new ArrayList<>(Specs.stringList(sourceSpec, "columns"));
        String watermark = Specs.string(sourceSpec, "watermarkColumn");
        if (watermark != null && !columns.isEmpty()
                && columns.stream().noneMatch(c -> c.equalsIgnoreCase(watermark))) {
            columns.add(watermark);
        }
        boolean afterCursor = watermark != null && cursor != null && !cursor.isBlank();
        String sql = dialect.select(table, columns, watermark, afterCursor);
        int max = Math.max(1, maxRecords);

        List<Map<String, Object>> rows = new ArrayList<>();
        String nextCursor = cursor;
        try (Connection jdbc = open(connection, target)) {
            jdbc.setReadOnly(true);
            jdbc.setAutoCommit(false);
            try (PreparedStatement statement = jdbc.prepareStatement(sql)) {
                statement.setQueryTimeout(limits.readTimeoutSeconds());
                statement.setMaxRows(max);
                statement.setFetchSize(Math.min(max, 500));
                if (afterCursor) {
                    bindCursor(statement, 1, cursor);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int watermarkIndex = watermark == null ? -1 : indexOf(meta, watermark);
                    while (rs.next() && rows.size() < max) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(meta.getColumnLabel(i), text(rs.getObject(i)));
                        }
                        rows.add(row);
                        if (watermarkIndex > 0) {
                            String encoded = encodeCursor(meta.getColumnType(watermarkIndex),
                                    rs.getObject(watermarkIndex));
                            if (encoded != null) {
                                nextCursor = encoded;
                            }
                        }
                    }
                }
            } finally {
                jdbc.rollback();
            }
        }
        boolean more = watermark != null && rows.size() >= max;
        return new ReadBatch(rows, List.of(), watermark == null ? null : nextCursor, more);
    }

    private static int indexOf(ResultSetMetaData meta, String column) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (meta.getColumnLabel(i).equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    private static Object text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof byte[] || value instanceof java.sql.Blob) {
            return null; // binary columns are not record data
        }
        if (value instanceof java.sql.Clob clob) {
            try {
                return clob.getSubString(1, (int) Math.min(clob.length(), 1_000_000));
            } catch (SQLException e) {
                return null;
            }
        }
        return value.toString();
    }

    /** The typed cursor of a watermark value; null for an unusable (null) value. */
    static String encodeCursor(int sqlType, Object value) {
        if (value == null) {
            return null;
        }
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC,
                 Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE ->
                    "N:" + new BigDecimal(value.toString()).toPlainString();
            case Types.TIMESTAMP -> "T:" + (value instanceof Timestamp ts
                    ? ts.toLocalDateTime().toString() : value.toString());
            case Types.TIMESTAMP_WITH_TIMEZONE -> value instanceof OffsetDateTime odt
                    ? "Z:" + odt : "S:" + value;
            case Types.DATE -> "D:" + (value instanceof java.sql.Date d
                    ? d.toLocalDate().toString() : value.toString());
            default -> "S:" + value;
        };
    }

    static void bindCursor(PreparedStatement statement, int index, String cursor) throws SQLException {
        if (cursor.length() < 2 || cursor.charAt(1) != ':') {
            statement.setString(index, cursor);
            return;
        }
        String value = cursor.substring(2);
        try {
            switch (cursor.charAt(0)) {
                case 'N' -> statement.setBigDecimal(index, new BigDecimal(value));
                case 'T' -> statement.setTimestamp(index, Timestamp.valueOf(LocalDateTime.parse(value)));
                case 'Z' -> statement.setObject(index, OffsetDateTime.parse(value));
                case 'D' -> statement.setDate(index, java.sql.Date.valueOf(LocalDate.parse(value)));
                default -> statement.setString(index, value);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The stored watermark could not be read; reset the "
                    + "flow's cursor.");
        }
    }

    // ---- write ---------------------------------------------------------------------

    @Override
    public WriteResult write(ConnectionContext connection, Map<String, Object> targetSpec,
                             OutboundBatch batch) throws Exception {
        validate(connection, targetSpec, true);
        if (batch.records().isEmpty()) {
            return new WriteResult(0, "no rows");
        }
        Target target = target(connection);
        DbDialect dialect = target.dialect();
        String table = dialect.qualified(target.schema(), Specs.string(targetSpec, "table"));
        DbWriteMode mode = Specs.enumValue(targetSpec, "mode", DbWriteMode.class, DbWriteMode.INSERT);
        List<String> keys = Specs.stringList(targetSpec, "keyColumns");

        List<String> columns = columnsOf(batch.records());
        if (mode == DbWriteMode.UPSERT) {
            for (String key : keys) {
                if (columns.stream().noneMatch(c -> c.equalsIgnoreCase(key))) {
                    throw new IllegalArgumentException("Key column '" + key
                            + "' is missing from the records to upsert.");
                }
            }
        }
        String sql = mode == DbWriteMode.UPSERT ? dialect.upsert(table, columns, keys)
                : dialect.insert(table, columns);

        try (Connection jdbc = open(connection, target)) {
            jdbc.setAutoCommit(false);
            try (PreparedStatement statement = jdbc.prepareStatement(sql)) {
                statement.setQueryTimeout(limits.readTimeoutSeconds());
                for (Map<String, Object> record : batch.records()) {
                    for (int i = 0; i < columns.size(); i++) {
                        bindValue(statement, i + 1, record.get(columns.get(i)));
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
                jdbc.commit();
            } catch (SQLException | RuntimeException e) {
                // All or nothing: a failing row leaves the target untouched.
                jdbc.rollback();
                throw e;
            }
        }
        return new WriteResult(batch.records().size(), target.dialect().name().toLowerCase(Locale.ROOT)
                + ":" + Specs.string(targetSpec, "table"));
    }

    static List<String> columnsOf(List<Map<String, Object>> records) {
        Map<String, String> byLower = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            for (String column : record.keySet()) {
                JdbcIdentifiers.requireValidIdentifier(column, "column");
                byLower.putIfAbsent(column.toLowerCase(Locale.ROOT), column);
            }
        }
        return new ArrayList<>(byLower.values());
    }

    private static void bindValue(PreparedStatement statement, int index, Object value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else if (value instanceof Number || value instanceof Boolean) {
            statement.setObject(index, value);
        } else {
            statement.setString(index, value.toString());
        }
    }

    // ---- plumbing ---------------------------------------------------------------------

    private Connection open(ConnectionContext connection, Target target) throws SQLException {
        egress.checkDestination(connection.bankId(), target.host(), target.port());
        Properties properties = new Properties();
        if (target.username() != null) {
            properties.setProperty("user", target.username());
        }
        String password = connection.secret().get("password");
        if (password != null) {
            properties.setProperty("password", password);
        }
        target.dialect().driverProperties(properties, limits);
        return opener.open(target.dialect(), target.url(limits), properties);
    }

    /** Production opener: the vendor's driver class, never DriverManager discovery. */
    static Connection openWithDriver(DbDialect dialect, String url, Properties properties)
            throws SQLException {
        Driver driver;
        try {
            driver = (Driver) Class.forName(dialect.driverClass()).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The " + dialect.name() + " JDBC driver is not available.");
        }
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new IllegalStateException("The " + dialect.name() + " driver refused the URL.");
        }
        return connection;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
