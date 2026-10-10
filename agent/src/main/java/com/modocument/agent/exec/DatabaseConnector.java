package com.modocument.agent.exec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.cloud.Protocol;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DATABASE connections: PostgreSQL, MariaDB/MySQL (MariaDB Connector/J),
 * SQL Server and Oracle over JDBC.
 * <ul>
 *   <li>READ: a {@code setReadOnly(true)} SELECT of the listed columns of an
 *       allow-listed table or view, by IDENTIFIER — never free SQL — ordered
 *       by the optional watermark column ({@code WHERE wm > ?}, typed cursor)
 *       with a row cap;</li>
 *   <li>WRITE: parameterised INSERT, or the vendor's UPSERT (ON CONFLICT /
 *       ON DUPLICATE KEY / MERGE), all rows in ONE transaction;</li>
 *   <li>TEST: connect, authenticate, {@code isValid}.</li>
 * </ul>
 * Identifiers match {@code [A-Za-z_][A-Za-z0-9_$]*} (≤ 128) per part and are
 * used unquoted, so nothing but a name can reach the SQL text. Every
 * connection has connect/socket timeouts, every statement a query timeout.
 */
public class DatabaseConnector implements Connector {

    /** Opens JDBC connections; tests substitute an in-memory database. */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open(String url, Properties properties) throws SQLException;
    }

    enum Vendor { POSTGRESQL, MARIADB, SQLSERVER, ORACLE }

    static final int PAGE_SIZE = 500;
    private static final int QUERY_TIMEOUT_SECONDS = 120;
    private static final int MAX_TEXT = 1_000_000;
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,127}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern DB_NAME = Pattern.compile("[A-Za-z0-9_$][A-Za-z0-9_$.-]{0,127}");
    private static final Set<String> PG_SSL = Set.of("disable", "allow", "prefer", "require",
            "verify-ca", "verify-full");
    private static final Set<String> MARIADB_SSL = Set.of("disable", "trust", "verify-ca", "verify-full");

    private final ConnectionFactory connections;
    private final ObjectMapper mapper;

    public DatabaseConnector(ObjectMapper mapper) {
        this(DriverManager::getConnection, mapper);
    }

    public DatabaseConnector(ConnectionFactory connections, ObjectMapper mapper) {
        this.connections = connections;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "DATABASE";
    }

    @Override
    public void authorize(TaskContext task, AllowList allowList) {
        Vendor vendor = vendor(task);
        allowList.checkDestination(host(task), task.port(defaultPort(vendor)));
        String operation = task.task().operation();
        if ("READ".equals(operation) || "WRITE".equals(operation)) {
            allowList.checkTable(qualifiedTable(task));
        }
    }

    @Override
    public void test(TaskContext task) throws SQLException {
        try (Connection connection = open(task)) {
            if (!connection.isValid(10)) {
                throw new SQLException("The database did not answer.");
            }
        }
    }

    @Override
    public ReadPage read(TaskContext task, String cursor, int max) throws SQLException {
        Vendor vendor = vendor(task);
        String table = qualifiedTable(task);
        List<String> columns = identifiers(task.specList("columns"), "column");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("The source spec lists no columns.");
        }
        String watermark = task.specText("watermarkColumn");
        if (watermark != null && !watermark.isEmpty()) {
            watermark = identifier(watermark, "watermark column");
            if (columns.stream().noneMatch(watermark::equalsIgnoreCase)) {
                columns = new ArrayList<>(columns);
                columns.add(watermark);
            }
        } else {
            watermark = null;
        }
        int cap = Math.max(1, max);
        int pageSize = watermark == null ? cap : Math.min(cap, PAGE_SIZE);
        Cursor after = watermark == null ? null : Cursor.parse(cursor);
        // One extra row tells whether another page exists.
        int limit = watermark == null ? pageSize : pageSize + 1;
        String sql = selectSql(vendor, table, columns, watermark, after != null, limit);

        try (Connection connection = open(task)) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setMaxRows(limit);
                statement.setFetchSize(Math.min(limit, PAGE_SIZE));
                if (after != null) {
                    after.bind(statement, 1);
                }
                List<Map<String, Object>> records = new ArrayList<>();
                Object lastWatermark = null;
                try (ResultSet rows = statement.executeQuery()) {
                    ResultSetMetaData meta = rows.getMetaData();
                    while (rows.next() && records.size() < limit) {
                        Map<String, Object> record = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            String name = meta.getColumnLabel(i);
                            Object value = rows.getObject(i);
                            record.put(name, jsonValue(value));
                            if (watermark != null && name.equalsIgnoreCase(watermark)
                                    && records.size() < pageSize) {
                                lastWatermark = value;
                            }
                        }
                        records.add(record);
                    }
                }
                boolean hasMore = watermark != null && records.size() > pageSize;
                if (hasMore) {
                    records = records.subList(0, pageSize);
                }
                String next = lastWatermark == null ? cursor : Cursor.of(lastWatermark).encode();
                return new ReadPage(new ArrayList<>(records), next, hasMore, null);
            }
        }
    }

    @Override
    public WriteOutcome write(TaskContext task, Protocol.OutboundBatch batch) throws SQLException {
        Vendor vendor = vendor(task);
        String table = qualifiedTable(task);
        List<Map<String, Object>> records = batch.records();
        if (records.isEmpty()) {
            return new WriteOutcome(0, table);
        }
        Set<String> columnSet = new LinkedHashSet<>();
        for (Map<String, Object> record : records) {
            for (String key : record.keySet()) {
                columnSet.add(identifier(key, "column"));
            }
        }
        List<String> columns = List.copyOf(columnSet);
        String mode = task.specText("mode") == null ? "INSERT" : task.specText("mode").toUpperCase(Locale.ROOT);
        String sql;
        if ("UPSERT".equals(mode)) {
            List<String> keys = identifiers(task.specList("keyColumns"), "key column");
            if (keys.isEmpty() || !columns.containsAll(keys)) {
                throw new IllegalArgumentException("UPSERT needs key columns present in every record.");
            }
            sql = upsertSql(vendor, table, columns, keys);
        } else if ("INSERT".equals(mode)) {
            sql = insertSql(table, columns);
        } else {
            throw new IllegalArgumentException("Write mode must be INSERT or UPSERT.");
        }

        try (Connection connection = open(task)) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (Map<String, Object> record : records) {
                    for (int i = 0; i < columns.size(); i++) {
                        bindValue(statement, i + 1, record.get(columns.get(i)));
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
        return new WriteOutcome(records.size(), table);
    }

    // ---------------------------------------------------------------- SQL text

    static String selectSql(Vendor vendor, String table, List<String> columns, String watermark,
                            boolean afterCursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ");
        if (vendor == Vendor.SQLSERVER) {
            sql.append("TOP (").append(limit).append(") ");
        }
        sql.append(String.join(", ", columns)).append(" FROM ").append(table);
        if (watermark != null) {
            if (afterCursor) {
                sql.append(" WHERE ").append(watermark).append(" > ?");
            }
            sql.append(" ORDER BY ").append(watermark);
        }
        switch (vendor) {
            case POSTGRESQL, MARIADB -> sql.append(" LIMIT ").append(limit);
            case ORACLE -> sql.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY");
            case SQLSERVER -> { }
        }
        return sql.toString();
    }

    static String insertSql(String table, List<String> columns) {
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
    }

    static String upsertSql(Vendor vendor, String table, List<String> columns, List<String> keys) {
        List<String> updates = columns.stream()
                .filter(c -> keys.stream().noneMatch(c::equalsIgnoreCase)).toList();
        return switch (vendor) {
            case POSTGRESQL -> insertSql(table, columns) + " ON CONFLICT (" + String.join(", ", keys) + ") "
                    + (updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET " + String.join(", ",
                    updates.stream().map(c -> c + " = EXCLUDED." + c).toList()));
            case MARIADB -> insertSql(table, columns) + " ON DUPLICATE KEY UPDATE " + String.join(", ",
                    (updates.isEmpty() ? keys : updates).stream().map(c -> c + " = VALUES(" + c + ")").toList());
            case SQLSERVER, ORACLE -> {
                String source = vendor == Vendor.SQLSERVER
                        ? "(SELECT " + String.join(", ", columns.stream().map(c -> "? AS " + c).toList()) + ") src"
                        : "(SELECT " + String.join(", ", columns.stream().map(c -> "? AS " + c).toList())
                        + " FROM dual) src";
                String on = String.join(" AND ", keys.stream().map(k -> "tgt." + k + " = src." + k).toList());
                StringBuilder merge = new StringBuilder("MERGE INTO ").append(table)
                        .append(vendor == Vendor.SQLSERVER ? " AS tgt USING " : " tgt USING ")
                        .append(source).append(" ON (").append(on).append(")");
                if (!updates.isEmpty()) {
                    merge.append(" WHEN MATCHED THEN UPDATE SET ").append(String.join(", ",
                            updates.stream().map(c -> "tgt." + c + " = src." + c).toList()));
                }
                merge.append(" WHEN NOT MATCHED THEN INSERT (").append(String.join(", ", columns))
                        .append(") VALUES (").append(String.join(", ",
                                columns.stream().map(c -> "src." + c).toList())).append(")");
                if (vendor == Vendor.SQLSERVER) {
                    merge.append(";");
                }
                yield merge.toString();
            }
        };
    }

    // ---------------------------------------------------------------- helpers

    private Connection open(TaskContext task) throws SQLException {
        Vendor vendor = vendor(task);
        Properties properties = new Properties();
        String user = task.config("username");
        if (user != null) {
            properties.setProperty("user", user);
        }
        String password = task.secret().get("password");
        if (password != null) {
            properties.setProperty("password", password);
        }
        switch (vendor) {
            case POSTGRESQL -> {
                properties.setProperty("connectTimeout", "10");
                properties.setProperty("socketTimeout", String.valueOf(QUERY_TIMEOUT_SECONDS + 10));
            }
            case MARIADB -> {
                properties.setProperty("connectTimeout", "10000");
                properties.setProperty("socketTimeout", String.valueOf((QUERY_TIMEOUT_SECONDS + 10) * 1000));
            }
            case SQLSERVER -> {
                properties.setProperty("loginTimeout", "10");
                properties.setProperty("socketTimeout", String.valueOf((QUERY_TIMEOUT_SECONDS + 10) * 1000));
            }
            case ORACLE -> {
                properties.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
                properties.setProperty("oracle.jdbc.ReadTimeout", String.valueOf((QUERY_TIMEOUT_SECONDS + 10) * 1000));
            }
        }
        return connections.open(jdbcUrl(task), properties);
    }

    static String jdbcUrl(TaskContext task) {
        Vendor vendor = vendor(task);
        String host = host(task);
        int port = task.port(defaultPort(vendor));
        String database = task.requireConfig("database");
        if (!DB_NAME.matcher(database).matches()) {
            throw new IllegalArgumentException("The database name is not a plain name.");
        }
        String ssl = task.config("sslmode");
        return switch (vendor) {
            case POSTGRESQL -> {
                String mode = ssl == null ? "prefer" : ssl.toLowerCase(Locale.ROOT);
                if (!PG_SSL.contains(mode)) {
                    throw new IllegalArgumentException("Unsupported PostgreSQL sslmode.");
                }
                yield "jdbc:postgresql://" + host + ":" + port + "/" + database + "?sslmode=" + mode;
            }
            case MARIADB -> {
                if (ssl == null) {
                    yield "jdbc:mariadb://" + host + ":" + port + "/" + database;
                }
                String mode = ssl.toLowerCase(Locale.ROOT);
                if (!MARIADB_SSL.contains(mode)) {
                    throw new IllegalArgumentException("Unsupported MariaDB sslMode.");
                }
                yield "jdbc:mariadb://" + host + ":" + port + "/" + database + "?sslMode=" + mode;
            }
            case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database
                    + ";encrypt=true;trustServerCertificate="
                    + ("true".equalsIgnoreCase(task.config("trustServerCertificate")) ? "true" : "false");
            case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
        };
    }

    static Vendor vendor(TaskContext task) {
        String value = task.requireConfig("vendor").toUpperCase(Locale.ROOT);
        try {
            return Vendor.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported database vendor " + value + ".");
        }
    }

    static int defaultPort(Vendor vendor) {
        return switch (vendor) {
            case POSTGRESQL -> 5432;
            case MARIADB -> 3306;
            case SQLSERVER -> 1433;
            case ORACLE -> 1521;
        };
    }

    private static String host(TaskContext task) {
        String host = task.requireConfig("host");
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("The database host is not a host name.");
        }
        return host;
    }

    /** {@code schema.table}: the spec's own schema, else the connection's {@code schema}. */
    static String qualifiedTable(TaskContext task) {
        String table = task.requireSpecText("table");
        String[] parts = table.split("\\.", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("The table must be [schema.]table.");
        }
        for (String part : parts) {
            identifier(part, "table");
        }
        String schema = task.config("schema");
        if (parts.length == 1 && schema != null) {
            return identifier(schema, "schema") + "." + table;
        }
        return table;
    }

    static String identifier(String name, String what) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("The " + what + " '" + (name == null ? "" : name.replaceAll("[^A-Za-z0-9_$ .-]", "?"))
                    + "' is not a plain identifier.");
        }
        return name;
    }

    private static List<String> identifiers(List<String> names, String what) {
        return names.stream().map(name -> identifier(name, what)).toList();
    }

    private void bindValue(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NULL);
        } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            statement.setObject(index, value);
        } else {
            try {
                statement.setString(index, mapper.writeValueAsString(value));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("A record value could not be written.");
            }
        }
    }

    static Object jsonValue(Object value) throws SQLException {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Double || value instanceof Float || value instanceof BigDecimal
                || value instanceof BigInteger) {
            return value;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof TemporalAccessor || value instanceof java.sql.Time) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                char[] buffer = new char[(int) Math.min(MAX_TEXT, Math.max(0, clob.length()))];
                int read = reader.read(buffer);
                return read <= 0 ? "" : new String(buffer, 0, read);
            } catch (IOException e) {
                throw new SQLException("A text column could not be read.", e);
            }
        }
        return String.valueOf(value);
    }

    /** Typed watermark: {@code ts:}, {@code tz:}, {@code d:}, {@code n:} or {@code s:} + value. */
    record Cursor(String kind, String value) {

        static Cursor of(Object watermark) {
            if (watermark instanceof Timestamp ts) {
                return new Cursor("ts", ts.toLocalDateTime().toString());
            }
            if (watermark instanceof LocalDateTime local) {
                return new Cursor("ts", local.toString());
            }
            if (watermark instanceof OffsetDateTime offset) {
                return new Cursor("tz", offset.toString());
            }
            if (watermark instanceof java.sql.Date date) {
                return new Cursor("d", date.toLocalDate().toString());
            }
            if (watermark instanceof LocalDate date) {
                return new Cursor("d", date.toString());
            }
            if (watermark instanceof Number number) {
                return new Cursor("n", new BigDecimal(number.toString()).toPlainString());
            }
            return new Cursor("s", String.valueOf(watermark));
        }

        static Cursor parse(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            int colon = encoded.indexOf(':');
            if (colon < 1) {
                // A server-side watermark without a type: compare as text.
                return new Cursor("s", encoded);
            }
            String kind = encoded.substring(0, colon);
            String value = encoded.substring(colon + 1);
            return switch (kind) {
                case "ts", "tz", "d", "n", "s" -> new Cursor(kind, value);
                default -> new Cursor("s", encoded);
            };
        }

        String encode() {
            return kind + ":" + value;
        }

        void bind(PreparedStatement statement, int index) throws SQLException {
            try {
                switch (kind) {
                    case "ts" -> statement.setTimestamp(index, Timestamp.valueOf(LocalDateTime.parse(value)));
                    case "tz" -> statement.setObject(index, OffsetDateTime.parse(value));
                    case "d" -> statement.setDate(index, java.sql.Date.valueOf(LocalDate.parse(value)));
                    case "n" -> statement.setBigDecimal(index, new BigDecimal(value));
                    default -> statement.setString(index, value);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The read cursor is malformed.");
            }
        }
    }
}
