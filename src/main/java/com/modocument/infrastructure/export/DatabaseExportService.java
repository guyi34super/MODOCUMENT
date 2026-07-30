package com.modocument.infrastructure.export;

import com.modocument.application.dto.DatabaseExportRequest;
import com.modocument.application.dto.TableData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends an ingested table to a user-supplied database over JDBC. Values only
 * ever travel as bound parameters; table/column identifiers are validated
 * against a strict pattern AND double-quoted, so user input can never splice
 * SQL. Credentials are used for this one export and never stored or logged.
 */
@Service
public class DatabaseExportService {

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

        SimpleDriverDataSource dataSource = dataSourceFor(request);
        boolean createIfMissing = request.getCreateTableIfMissing() == null
                || request.getCreateTableIfMissing();

        String insertSql = "INSERT INTO " + quote(tableName) + " ("
                + columns.stream().map(DatabaseExportService::quote).collect(Collectors.joining(", "))
                + ") VALUES (" + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";

        List<Object[]> batch = table.getRows().stream()
                .map(row -> columns.stream().map(c -> (Object) row.getOrDefault(c, "")).toArray())
                .toList();

        // All-or-nothing: a constraint violation halfway through a batch must
        // not leave the target table holding a partial export that a retry
        // would then duplicate.
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (!tableExists(jdbc, tableName)) {
                if (!createIfMissing) {
                    throw new IllegalStateException(
                            "Table '" + tableName + "' does not exist in the target database.");
                }
                jdbc.execute(createTableSql(tableName, columns));
            }
            jdbc.batchUpdate(insertSql, batch);
        });
        return batch.size();
    }

    private static SimpleDriverDataSource dataSourceFor(DatabaseExportRequest request) {
        return JdbcIdentifiers.dataSource(
                request.getJdbcUrl(), request.getUsername(), request.getPassword());
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

    private static String createTableSql(String tableName, List<String> columns) {
        // VARCHAR(4000) works on every supported target (PostgreSQL, H2).
        return "CREATE TABLE " + quote(tableName) + " ("
                + columns.stream()
                .map(c -> quote(c) + " VARCHAR(4000)")
                .collect(Collectors.joining(", "))
                + ")";
    }

    private static String requireValidIdentifier(String value, String what) {
        return JdbcIdentifiers.requireValidIdentifier(value, what);
    }

    private static String quote(String identifier) {
        return JdbcIdentifiers.quote(identifier);
    }

    /** Host portion of the JDBC url, safe to persist in export info (no credentials). */
    public static String describeTarget(String jdbcUrl) {
        return JdbcIdentifiers.describeTarget(jdbcUrl);
    }
}
