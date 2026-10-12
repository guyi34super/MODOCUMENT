package com.modocument.agent.exec;

import com.modocument.agent.cloud.Protocol;
import com.modocument.agent.secrets.LocalSecrets;
import com.modocument.connector.core.db.DbDialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SQL text the agent's database connector can produce — connector-core's,
 * the same as the server's: identifiers only, quoted per vendor, a row cap,
 * typed watermark cursors (the agent's pre-1.2.0 cursors still bind), and
 * JDBC URLs built from validated config only.
 */
class DatabaseConnectorSqlTest {

    @Test
    void readsAreIdentifierSelectsWithAWatermarkAndARowCap() throws Exception {
        List<String> columns = List.of("id", "name", "updated_at");
        assertThat(DbDialect.POSTGRESQL.select(DbDialect.POSTGRESQL.qualified("s", "v"), columns,
                "updated_at", true))
                .isEqualTo("SELECT \"id\", \"name\", \"updated_at\" FROM \"s\".\"v\" "
                        + "WHERE \"updated_at\" > ? ORDER BY \"updated_at\"");
        assertThat(DbDialect.MARIADB.select(DbDialect.MARIADB.qualified(null, "v"), columns, null, false))
                .isEqualTo("SELECT `id`, `name`, `updated_at` FROM `v`");
        assertThat(DbDialect.SQLSERVER.select(DbDialect.SQLSERVER.qualified("dbo", "v"), columns,
                "updated_at", false))
                .isEqualTo("SELECT [id], [name], [updated_at] FROM [dbo].[v] ORDER BY [updated_at]");
        assertThat(DbDialect.ORACLE.select(DbDialect.ORACLE.qualified("APP", "V"), columns, "updated_at", true))
                .isEqualTo("SELECT \"id\", \"name\", \"updated_at\" FROM \"APP\".\"V\" "
                        + "WHERE \"updated_at\" > ? ORDER BY \"updated_at\"");

        // The row cap: a watermark read posts at most PAGE_SIZE records and says whether more exist.
        String url = h2();
        sql(url, "CREATE TABLE \"v\" (\"id\" INT PRIMARY KEY, \"updated_at\" BIGINT)",
                "INSERT INTO \"v\" SELECT X, X FROM SYSTEM_RANGE(1, " + (DatabaseConnector.PAGE_SIZE + 20) + ")");
        DatabaseConnector connector = new DatabaseConnector((jdbcUrl, properties) ->
                DriverManager.getConnection(url, "sa", ""));
        Connector.ReadPage page = connector.read(task(Map.of("table", "v", "columns", List.of("id"),
                "watermarkColumn", "updated_at")), null, 10_000);
        assertThat(page.records()).hasSize(DatabaseConnector.PAGE_SIZE);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("N:" + DatabaseConnector.PAGE_SIZE);
        Connector.ReadPage rest = connector.read(task(Map.of("table", "v", "columns", List.of("id"),
                "watermarkColumn", "updated_at")), page.nextCursor(), 10_000);
        assertThat(rest.records()).hasSize(20);
        assertThat(rest.hasMore()).isFalse();
    }

    @Test
    void writesAreParameterisedInsertsOrTheVendorsUpsert() {
        List<String> columns = List.of("ref", "amount");
        List<String> keys = List.of("ref");
        assertThat(DbDialect.POSTGRESQL.insert("\"stg\".\"r\"", columns))
                .isEqualTo("INSERT INTO \"stg\".\"r\" (\"ref\", \"amount\") VALUES (?, ?)");
        assertThat(DbDialect.POSTGRESQL.upsert("\"stg\".\"r\"", columns, keys))
                .isEqualTo("INSERT INTO \"stg\".\"r\" (\"ref\", \"amount\") VALUES (?, ?) ON CONFLICT (\"ref\") "
                        + "DO UPDATE SET \"amount\" = EXCLUDED.\"amount\"");
        assertThat(DbDialect.MARIADB.upsert("`r`", columns, keys))
                .isEqualTo("INSERT INTO `r` (`ref`, `amount`) VALUES (?, ?) ON DUPLICATE KEY UPDATE "
                        + "`amount` = VALUES(`amount`)");
        assertThat(DbDialect.SQLSERVER.upsert("[dbo].[r]", columns, keys))
                .isEqualTo("MERGE INTO [dbo].[r] WITH (HOLDLOCK) AS t USING (VALUES (?, ?)) AS s ([ref], [amount]) "
                        + "ON (t.[ref] = s.[ref]) WHEN MATCHED THEN UPDATE SET t.[amount] = s.[amount] "
                        + "WHEN NOT MATCHED THEN INSERT ([ref], [amount]) VALUES (s.[ref], s.[amount]);");
        assertThat(DbDialect.ORACLE.upsert("\"R\"", columns, keys))
                .isEqualTo("MERGE INTO \"R\" t USING (SELECT ? AS \"ref\", ? AS \"amount\" FROM dual) s "
                        + "ON (t.\"ref\" = s.\"ref\") WHEN MATCHED THEN UPDATE SET t.\"amount\" = s.\"amount\" "
                        + "WHEN NOT MATCHED THEN INSERT (\"ref\", \"amount\") VALUES (s.\"ref\", s.\"amount\")");
    }

    @Test
    void anythingButAPlainIdentifierIsRefused() {
        DatabaseConnector connector = new DatabaseConnector((url, properties) -> {
            throw new AssertionError("must not connect");
        });
        // A schema comes from the connection's 'schema', never from the spec's table name.
        for (String bad : List.of("v; DROP TABLE x", "v--", "\"v\"", "a.b.c", "s.v", "1v", "v x", "")) {
            assertThatThrownBy(() -> connector.read(task(Map.of("table", bad, "columns", List.of("id"))),
                    null, 10)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> connector.read(task(Map.of("table", "v",
                "columns", List.of("id) FROM pg_shadow --"))), null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jdbcUrlsAreBuiltFromValidatedConfig() {
        assertThat(urlOf("POSTGRESQL", "db.bank.local", "core"))
                .startsWith("jdbc:postgresql://db.bank.local:5432/core?stringtype=unspecified")
                .contains("connectTimeout=10");
        assertThat(urlOf("SQLSERVER", "mssql", "Core"))
                .startsWith("jdbc:sqlserver://mssql:1433;databaseName=Core;encrypt=true;trustServerCertificate=false");
        assertThat(urlOf("ORACLE", "ora", "ORCLPDB1")).isEqualTo("jdbc:oracle:thin:@tcp://ora:1521/ORCLPDB1");
        assertThat(urlOf("MARIADB", "maria", "core"))
                .startsWith("jdbc:mariadb://maria:3306/core?allowLocalInfile=false");
        DatabaseConnector refusing = new DatabaseConnector((url, properties) -> {
            throw new AssertionError("must not connect");
        });
        assertThatThrownBy(() -> refusing.test(task(Map.of(), "POSTGRESQL", "db?x=1", "core")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refusing.test(task(Map.of(), "POSTGRESQL", "db", "core;x=1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refusing.test(task(Map.of(), "DB2", "db", "core")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cursorsKeepTheirType() throws Exception {
        String url = h2();
        sql(url, "CREATE TABLE \"events\" (\"id\" INT PRIMARY KEY, \"at\" TIMESTAMP)",
                "INSERT INTO \"events\" VALUES (1, TIMESTAMP '2026-10-01 10:00:00'), "
                        + "(2, TIMESTAMP '2026-10-02 10:00:00'), (3, TIMESTAMP '2026-10-03 10:00:00')");
        DatabaseConnector connector = new DatabaseConnector((jdbcUrl, properties) ->
                DriverManager.getConnection(url, "sa", ""));
        Map<String, Object> spec = Map.of("table", "events", "columns", List.of("id"), "watermarkColumn", "at");

        Connector.ReadPage all = connector.read(task(spec), null, 10);
        assertThat(all.nextCursor()).isEqualTo("T:2026-10-03T10:00");
        // The current typed cursor and the agent's pre-1.2.0 one bind the same way.
        for (String cursor : List.of("T:2026-10-01T10:00", "ts:2026-10-01T10:00")) {
            assertThat(connector.read(task(spec), cursor, 10).records())
                    .as(cursor).extracting(r -> r.get("id")).containsExactly("2", "3");
        }
        assertThatThrownBy(() -> connector.read(task(spec), "T:not-a-time", 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("watermark");
    }

    // ---------------------------------------------------------------- helpers

    private static String urlOf(String vendor, String host, String database) {
        AtomicReference<String> seen = new AtomicReference<>();
        DatabaseConnector connector = new DatabaseConnector((url, properties) -> {
            seen.set(url);
            throw new SQLException("stop here");
        });
        assertThatThrownBy(() -> connector.test(task(Map.of(), vendor, host, database)))
                .hasMessageContaining("connection test failed");
        return seen.get();
    }

    private static String h2() {
        return "jdbc:h2:mem:sql" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private static void sql(String url, String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static Connector.TaskContext task(Map<String, Object> spec) {
        return task(spec, "POSTGRESQL", "db", "core");
    }

    private static Connector.TaskContext task(Map<String, Object> spec, String vendor, String host, String database) {
        Map<String, String> config = new HashMap<>(Map.of("vendor", vendor, "host", host, "database", database));
        return new Connector.TaskContext(new Protocol.AgentTask(1, 1, "DATABASE", "READ", config, null, spec,
                null, 10), LocalSecrets.Secret.EMPTY);
    }
}
