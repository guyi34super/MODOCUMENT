package com.modocument.connector.core.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.connector.core.spi.ConnectionContext;
import com.modocument.connector.core.spi.ConnectionTestResult;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.Connector;
import com.modocument.connector.core.spi.DestinationGuard;
import com.modocument.connector.core.spi.Operation;
import com.modocument.connector.core.spi.OutboundBatch;
import com.modocument.connector.core.spi.ReadBatch;
import com.modocument.connector.core.spi.WriteResult;
import com.modocument.connector.core.support.ConnectorErrors;
import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.connector.core.support.Specs;

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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * DATABASE connector: PostgreSQL, Oracle, SQL Server and MariaDB/MySQL
 * (MariaDB Connector/J). No free SQL, ever — the bank's DBA exposes a table
 * or view and the flow names identifiers only.
 * <ul>
 *   <li><b>Connection</b>: the JDBC URL is built per vendor by
 *       {@link DbDialect} from {@code host, port, database} plus allow-listed
 *       options; the destination passes the {@link DestinationGuard} right
 *       before the driver connects; connect/login/socket timeouts are always
 *       set.</li>
 *   <li><b>Read</b> ({@code {table, columns:[…], watermarkColumn?}}): a
 *       {@code setReadOnly(true)} connection, a rolled-back transaction,
 *       {@code SELECT <columns> FROM [schema.]table [WHERE wm > ?] ORDER BY wm}
 *       with a row cap of {@code maxRecords + 1} (the extra row only tells
 *       whether another page exists) and a query timeout. Values arrive as
 *       text. The cursor is the last watermark value, typed ({@code N:}/
 *       {@code T:}/{@code Z:}/{@code D:}/{@code S:} prefix) so it binds with
 *       the column's own type; cursors stored by agents before 1.2.0
 *       ({@code n:}/{@code ts:}/{@code tz:}/{@code d:}/{@code s:}) still
 *       bind.</li>
 *   <li><b>Write</b> ({@code {table, mode:"INSERT|UPSERT", keyColumns:[…],
 *       batchSize}}): a parameterised INSERT or the vendor upsert, the whole
 *       batch in ONE transaction (all or nothing), with a query timeout.</li>
 * </ul>
 * Connection config keys: {@code vendor, host, port, database, schema,
 * username} plus the vendor's allowed options ({@code sslmode} for every
 * vendor, translated; {@code protocol} for Oracle, {@code encrypt},
 * {@code trustServerCertificate}, {@code hostNameInCertificate} for SQL
 * Server, {@code sslMode} for MariaDB). Secret: {@code password} (optional at
 * call time: a bank database may trust the agent's host).
 */
public class DatabaseConnector implements Connector {

    public static final Set<String> SOURCE_KEYS = Set.of("table", "columns", "watermarkColumn");
    public static final Set<String> TARGET_KEYS = Set.of("table", "mode", "keyColumns", "batchSize");
    /** Connection config keys that are not vendor URL options. */
    static final Set<String> BASE_CONFIG_KEYS = Set.of("vendor", "host", "port", "database",
            "schema", "username", "authType");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** INSERT or the vendor's UPSERT. */
    public enum WriteMode { INSERT, UPSERT }

    /** Opens a JDBC connection; replaced in tests to reach an embedded database. */
    @FunctionalInterface
    public interface JdbcOpener {
        Connection open(DbDialect dialect, String url, Properties properties) throws SQLException;
    }

    private final ConnectorLimits limits;
    private final JdbcOpener opener;

    public DatabaseConnector(ConnectorLimits limits) {
        this(limits, DatabaseConnector::openWithDriver);
    }

    public DatabaseConnector(ConnectorLimits limits, JdbcOpener opener) {
        this.limits = limits;
        this.opener = opener;
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.DATABASE;
    }

    public ConnectorLimits limits() {
        return limits;
    }

    // ---- validation -----------------------------------------------------------

    /** The validated connection target (everything but the secret). */
    public DbTarget target(ConnectionContext connection) {
        Map<String, String> config = connection.config();
        DbDialect dialect = dialect(config);
        String host = DbDialect.requireHost(Specs.string(config, "host"));
        Integer port = Specs.integer(config, "port", 1, 65_535);
        String database = DbDialect.requireDatabase(Specs.string(config, "database"));
        String schema = Specs.string(config, "schema");
        if (schema != null) {
            DbIdentifiers.require(schema, "schema");
        }
        Map<String, String> requested = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (!BASE_CONFIG_KEYS.contains(key) && value != null && !value.isBlank()) {
                requested.put(key, value.trim());
            }
        });
        translateSslMode(dialect, requested);
        String username = Specs.string(config, "username");
        if (username != null && (username.length() > 128 || username.chars().anyMatch(c -> c < 0x20))) {
            throw new IllegalArgumentException("'username' is not a valid database user name.");
        }
        return new DbTarget(dialect, host, port == null ? dialect.defaultPort() : port, database,
                schema, dialect.options(requested), username);
    }

    private static DbDialect dialect(Map<String, String> config) {
        DbDialect dialect = Specs.enumValue(config, "vendor", DbDialect.class, null);
        if (dialect == null) {
            throw new IllegalArgumentException("The connection setting 'vendor' is required.");
        }
        return dialect;
    }

    /**
     * The UI offers one {@code sslmode} (PostgreSQL vocabulary: disable,
     * require, verify-ca, verify-full) for every vendor; it maps to each
     * driver's own option. PostgreSQL keeps it as is.
     */
    static void translateSslMode(DbDialect dialect, Map<String, String> options) {
        String key = options.keySet().stream().filter(k -> k.equals("sslmode")).findFirst().orElse(null);
        if (key == null || dialect == DbDialect.POSTGRESQL) {
            return;
        }
        String mode = options.remove(key).toLowerCase(Locale.ROOT);
        boolean off = mode.equals("disable") || mode.equals("allow") || mode.equals("prefer");
        boolean verify = mode.startsWith("verify");
        switch (dialect) {
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

    /** The connection settings (and, when required, the secret's password). */
    public DbTarget validateConnection(ConnectionContext connection) {
        DbTarget target = target(connection);
        if (connection.secretRequired() && connection.secret().get("password") == null) {
            throw new IllegalArgumentException("The connection secret has no 'password'.");
        }
        return target;
    }

    @Override
    public void validate(ConnectionContext connection, Map<String, Object> spec, boolean outbound) {
        DbTarget target = validateConnection(connection);
        if (outbound) {
            Specs.allowOnly(spec, "target spec", TARGET_KEYS);
            DbIdentifiers.require(Specs.requireString(spec, "table", "Target spec"), "table");
            WriteMode mode = Specs.enumValue(spec, "mode", WriteMode.class, WriteMode.INSERT);
            List<String> keys = identifiers(Specs.stringList(spec, "keyColumns"), "keyColumns");
            if (mode == WriteMode.UPSERT && keys.isEmpty()) {
                throw new IllegalArgumentException("UPSERT needs at least one key column.");
            }
            Specs.integer(spec, "batchSize", 1, 10_000);
            target.dialect().quote(Specs.string(spec, "table"));
            return;
        }
        Specs.allowOnly(spec, "source spec", SOURCE_KEYS);
        DbIdentifiers.require(Specs.requireString(spec, "table", "Source spec"), "table");
        identifiers(Specs.stringList(spec, "columns"), "columns");
        String watermark = Specs.string(spec, "watermarkColumn");
        if (watermark != null) {
            DbIdentifiers.require(watermark, "watermarkColumn");
        }
    }

    /**
     * Destination first (host and port, then the table as the spec names it,
     * qualified with the connection's schema), then the validation — so a
     * refused table is reported as refused even when it is not a valid name.
     */
    @Override
    public void authorize(ConnectionContext connection, Map<String, Object> spec, Operation operation) {
        Map<String, String> config = connection.config();
        DbDialect dialect = dialect(config);
        String host = DbDialect.requireHost(Specs.string(config, "host"));
        Integer port = Specs.integer(config, "port", 1, 65_535);
        connection.guard().checkHost(host, port == null ? dialect.defaultPort() : port);
        if (operation != Operation.TEST) {
            String table = Specs.string(spec, "table");
            String schema = Specs.string(config, "schema");
            if (table != null) {
                connection.guard().checkTable(schema == null ? table : schema + "." + table);
            }
            validate(connection, spec, operation == Operation.WRITE);
        } else {
            validateConnection(connection);
        }
    }

    private static List<String> identifiers(List<String> names, String what) {
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            DbIdentifiers.require(name, what);
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
            DbTarget target = target(connection);
            try (Connection jdbc = open(connection, target)) {
                jdbc.setReadOnly(true);
                if (!jdbc.isValid(limits.connectTimeoutSeconds())) {
                    return ConnectionTestResult.failed("The database did not answer the validity check.",
                            Connector.elapsedMillis(started));
                }
                DatabaseMetaData meta = jdbc.getMetaData();
                return ConnectionTestResult.ok("Connected to " + meta.getDatabaseProductName() + " "
                        + meta.getDatabaseProductVersion().lines().findFirst().orElse(""),
                        Connector.elapsedMillis(started));
            }
        } catch (Exception e) {
            return ConnectionTestResult.failed(ConnectorErrors.safeMessage(e),
                    Connector.elapsedMillis(started));
        }
    }

    // ---- read -----------------------------------------------------------------------

    @Override
    public ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec,
                          String cursor, int maxRecords) throws Exception {
        validate(connection, sourceSpec, false);
        DbTarget target = target(connection);
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
        // One row more than asked for: it only tells whether another page exists.
        int fetch = watermark == null ? max : max + 1;

        List<Map<String, Object>> rows = new ArrayList<>();
        String nextCursor = cursor;
        boolean more = false;
        try (Connection jdbc = open(connection, target)) {
            jdbc.setReadOnly(true);
            jdbc.setAutoCommit(false);
            try (PreparedStatement statement = jdbc.prepareStatement(sql)) {
                statement.setQueryTimeout(limits.readTimeoutSeconds());
                statement.setMaxRows(fetch);
                statement.setFetchSize(Math.min(fetch, 500));
                if (afterCursor) {
                    bindCursor(statement, 1, cursor);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int watermarkIndex = watermark == null ? -1 : indexOf(meta, watermark);
                    while (rs.next()) {
                        if (rows.size() >= max) {
                            more = watermark != null;
                            break;
                        }
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

    /** A column value as record text; binary columns are not record data. */
    static Object text(Object value) {
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
            return null;
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
    public static String encodeCursor(int sqlType, Object value) {
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

    /**
     * Binds a stored cursor with its type. Accepts the current one-letter
     * prefixes, the agent's pre-1.2.0 prefixes ({@code n: ts: tz: d: s:})
     * and an untyped legacy value (bound as text).
     */
    public static void bindCursor(PreparedStatement statement, int index, String cursor)
            throws SQLException {
        int colon = cursor.indexOf(':');
        String kind = colon < 1 ? "" : cursor.substring(0, colon);
        String value = colon < 1 ? cursor : cursor.substring(colon + 1);
        try {
            switch (kind) {
                case "N", "n" -> statement.setBigDecimal(index, new BigDecimal(value));
                case "T", "ts" -> statement.setTimestamp(index, Timestamp.valueOf(LocalDateTime.parse(value)));
                case "Z", "tz" -> statement.setObject(index, OffsetDateTime.parse(value));
                case "D", "d" -> statement.setDate(index, java.sql.Date.valueOf(LocalDate.parse(value)));
                case "S", "s" -> statement.setString(index, value);
                default -> statement.setString(index, cursor);
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
        DbTarget target = target(connection);
        DbDialect dialect = target.dialect();
        String table = dialect.qualified(target.schema(), Specs.string(targetSpec, "table"));
        WriteMode mode = Specs.enumValue(targetSpec, "mode", WriteMode.class, WriteMode.INSERT);
        List<String> keys = Specs.stringList(targetSpec, "keyColumns");

        List<String> columns = columnsOf(batch.records());
        if (mode == WriteMode.UPSERT) {
            for (String key : keys) {
                if (columns.stream().noneMatch(c -> c.equalsIgnoreCase(key))) {
                    throw new IllegalArgumentException("Key column '" + key
                            + "' is missing from the records to upsert.");
                }
            }
        }
        String sql = mode == WriteMode.UPSERT ? dialect.upsert(table, columns, keys)
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
        return new WriteResult(batch.records().size(), dialect.name().toLowerCase(Locale.ROOT)
                + ":" + Specs.string(targetSpec, "table"));
    }

    /** The union of the records' keys (validated identifiers), first spelling wins, case-insensitive. */
    public static List<String> columnsOf(List<Map<String, Object>> records) {
        Map<String, String> byLower = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            for (String column : record.keySet()) {
                DbIdentifiers.require(column, "column");
                byLower.putIfAbsent(column.toLowerCase(Locale.ROOT), column);
            }
        }
        return new ArrayList<>(byLower.values());
    }

    /** Text, numbers and booleans as they are; nested objects and lists as JSON text. */
    static void bindValue(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else if (value instanceof Number || value instanceof Boolean) {
            statement.setObject(index, value);
        } else if (value instanceof Map || value instanceof Collection) {
            try {
                statement.setString(index, MAPPER.writeValueAsString(value));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("A record value could not be written.");
            }
        } else {
            statement.setString(index, value.toString());
        }
    }

    // ---- plumbing ---------------------------------------------------------------------

    private Connection open(ConnectionContext connection, DbTarget target) throws SQLException {
        connection.guard().checkHost(target.host(), target.port());
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
    public static Connection openWithDriver(DbDialect dialect, String url, Properties properties)
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
}
