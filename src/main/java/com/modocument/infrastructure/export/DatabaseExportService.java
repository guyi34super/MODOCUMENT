package com.modocument.infrastructure.export;

import com.modocument.application.dto.DatabaseExportRequest;
import com.modocument.application.dto.TableData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sends an ingested table to a user-supplied database over JDBC. Values only
 * ever travel as bound parameters; table/column identifiers are validated
 * against a strict pattern AND double-quoted, so user input can never splice
 * SQL. Credentials are used for this one export and never stored or logged.
 */
@Service
public class DatabaseExportService {

    /** Conservative identifier rule for target table/column names. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    /** Drivers actually on the classpath; other targets need a driver dependency first. */
    private static final Map<String, String> DRIVERS_BY_URL_PREFIX = Map.of(
            "jdbc:sqlserver:", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:h2:", "org.h2.Driver");

    /** Exports all rows and returns the inserted row count. */
    public int export(TableData table, DatabaseExportRequest request) {
        if (table == null || table.isEmpty()) {
            throw new IllegalStateException("There is no data to export.");
        }
        String tableName = requireValidIdentifier(request.getTableName(), "tableName");
        List<String> columns = table.getColumns();
        for (String column : columns) {
            requireValidIdentifier(column, "column");
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSourceFor(request));
        boolean createIfMissing = request.getCreateTableIfMissing() == null
                || request.getCreateTableIfMissing();

        if (!tableExists(jdbc, tableName)) {
            if (!createIfMissing) {
                throw new IllegalStateException(
                        "Table '" + tableName + "' does not exist in the target database.");
            }
            jdbc.execute(createTableSql(jdbc, tableName, columns));
        }

        String insertSql = "INSERT INTO " + quote(tableName) + " ("
                + columns.stream().map(DatabaseExportService::quote).collect(Collectors.joining(", "))
                + ") VALUES (" + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";

        List<Object[]> batch = table.getRows().stream()
                .map(row -> columns.stream().map(c -> (Object) row.getOrDefault(c, "")).toArray())
                .toList();
        jdbc.batchUpdate(insertSql, batch);
        return batch.size();
    }

    private static SimpleDriverDataSource dataSourceFor(DatabaseExportRequest request) {
        String url = request.getJdbcUrl() == null ? "" : request.getJdbcUrl().trim();
        String driverClass = DRIVERS_BY_URL_PREFIX.entrySet().stream()
                .filter(e -> url.toLowerCase(java.util.Locale.ROOT).startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported jdbcUrl; supported prefixes: "
                                + String.join(", ", DRIVERS_BY_URL_PREFIX.keySet())));
        try {
            Driver driver = (Driver) Class.forName(driverClass)
                    .getDeclaredConstructor().newInstance();
            return new SimpleDriverDataSource(driver, url,
                    request.getUsername(), request.getPassword());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("JDBC driver " + driverClass + " is unavailable.", e);
        }
    }

    private static boolean tableExists(JdbcTemplate jdbc, String tableName) {
        return Boolean.TRUE.equals(jdbc.execute((Connection connection) -> {
            // Unquoted identifiers may be stored case-folded; check common variants.
            for (String candidate : List.of(tableName,
                    tableName.toUpperCase(java.util.Locale.ROOT),
                    tableName.toLowerCase(java.util.Locale.ROOT))) {
                try (ResultSet rs = connection.getMetaData()
                        .getTables(null, null, candidate, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            return false;
        }));
    }

    private static String createTableSql(JdbcTemplate jdbc, String tableName, List<String> columns) {
        String textType = isSqlServer(jdbc) ? "NVARCHAR(4000)" : "VARCHAR(4000)";
        return "CREATE TABLE " + quote(tableName) + " ("
                + columns.stream()
                .map(c -> quote(c) + " " + textType)
                .collect(Collectors.joining(", "))
                + ")";
    }

    private static boolean isSqlServer(JdbcTemplate jdbc) {
        String product = jdbc.execute((Connection connection) ->
                connection.getMetaData().getDatabaseProductName());
        return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("sql server");
    }

    private static String requireValidIdentifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + what + " '" + value + "': only letters, digits, and underscores "
                            + "are allowed (max 128 chars, must not start with a digit).");
        }
        return value;
    }

    private static String quote(String identifier) {
        return '"' + identifier + '"';
    }

    /** Host portion of the JDBC url, safe to persist in export info (no credentials). */
    public static String describeTarget(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "";
        }
        int semicolon = jdbcUrl.indexOf(';');
        String base = semicolon < 0 ? jdbcUrl : jdbcUrl.substring(0, semicolon);
        int question = base.indexOf('?');
        return question < 0 ? base : base.substring(0, question);
    }
}
