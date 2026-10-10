package com.modocument.agent.exec;

import com.modocument.agent.cloud.Protocol;
import com.modocument.agent.secrets.LocalSecrets;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.modocument.agent.exec.DatabaseConnector.Vendor.MARIADB;
import static com.modocument.agent.exec.DatabaseConnector.Vendor.ORACLE;
import static com.modocument.agent.exec.DatabaseConnector.Vendor.POSTGRESQL;
import static com.modocument.agent.exec.DatabaseConnector.Vendor.SQLSERVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SQL text the database connector can produce: identifiers only, per vendor. */
class DatabaseConnectorSqlTest {

    @Test
    void readsAreIdentifierSelectsWithAWatermarkAndARowCap() {
        List<String> columns = List.of("id", "name", "updated_at");
        assertThat(DatabaseConnector.selectSql(POSTGRESQL, "s.v", columns, "updated_at", true, 501))
                .isEqualTo("SELECT id, name, updated_at FROM s.v WHERE updated_at > ? ORDER BY updated_at LIMIT 501");
        assertThat(DatabaseConnector.selectSql(MARIADB, "v", columns, null, false, 10))
                .isEqualTo("SELECT id, name, updated_at FROM v LIMIT 10");
        assertThat(DatabaseConnector.selectSql(SQLSERVER, "dbo.v", columns, "updated_at", false, 501))
                .isEqualTo("SELECT TOP (501) id, name, updated_at FROM dbo.v ORDER BY updated_at");
        assertThat(DatabaseConnector.selectSql(ORACLE, "APP.V", columns, "updated_at", true, 501))
                .isEqualTo("SELECT id, name, updated_at FROM APP.V WHERE updated_at > ? ORDER BY updated_at "
                        + "FETCH FIRST 501 ROWS ONLY");
    }

    @Test
    void writesAreParameterisedInsertsOrTheVendorsUpsert() {
        List<String> columns = List.of("ref", "amount");
        List<String> keys = List.of("ref");
        assertThat(DatabaseConnector.insertSql("stg.r", columns))
                .isEqualTo("INSERT INTO stg.r (ref, amount) VALUES (?, ?)");
        assertThat(DatabaseConnector.upsertSql(POSTGRESQL, "stg.r", columns, keys))
                .isEqualTo("INSERT INTO stg.r (ref, amount) VALUES (?, ?) ON CONFLICT (ref) "
                        + "DO UPDATE SET amount = EXCLUDED.amount");
        assertThat(DatabaseConnector.upsertSql(MARIADB, "r", columns, keys))
                .isEqualTo("INSERT INTO r (ref, amount) VALUES (?, ?) ON DUPLICATE KEY UPDATE amount = VALUES(amount)");
        assertThat(DatabaseConnector.upsertSql(SQLSERVER, "dbo.r", columns, keys))
                .isEqualTo("MERGE INTO dbo.r AS tgt USING (SELECT ? AS ref, ? AS amount) src ON (tgt.ref = src.ref) "
                        + "WHEN MATCHED THEN UPDATE SET tgt.amount = src.amount "
                        + "WHEN NOT MATCHED THEN INSERT (ref, amount) VALUES (src.ref, src.amount);");
        assertThat(DatabaseConnector.upsertSql(ORACLE, "R", columns, keys))
                .isEqualTo("MERGE INTO R tgt USING (SELECT ? AS ref, ? AS amount FROM dual) src ON (tgt.ref = src.ref) "
                        + "WHEN MATCHED THEN UPDATE SET tgt.amount = src.amount "
                        + "WHEN NOT MATCHED THEN INSERT (ref, amount) VALUES (src.ref, src.amount)");
    }

    @Test
    void anythingButAPlainIdentifierIsRefused() {
        for (String bad : List.of("v; DROP TABLE x", "v--", "\"v\"", "a.b.c", "1v", "v x", "")) {
            Connector.TaskContext task = task(Map.of("table", bad, "columns", List.of("id")));
            assertThatThrownBy(() -> DatabaseConnector.qualifiedTable(task))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> DatabaseConnector.identifier("id) FROM pg_shadow --", "column"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jdbcUrlsAreBuiltFromValidatedConfig() {
        assertThat(DatabaseConnector.jdbcUrl(task(Map.of(), "POSTGRESQL", "db.bank.local", "core")))
                .isEqualTo("jdbc:postgresql://db.bank.local:5432/core?sslmode=prefer");
        assertThat(DatabaseConnector.jdbcUrl(task(Map.of(), "SQLSERVER", "mssql", "Core")))
                .isEqualTo("jdbc:sqlserver://mssql:1433;databaseName=Core;encrypt=true;trustServerCertificate=false");
        assertThat(DatabaseConnector.jdbcUrl(task(Map.of(), "ORACLE", "ora", "ORCLPDB1")))
                .isEqualTo("jdbc:oracle:thin:@//ora:1521/ORCLPDB1");
        assertThat(DatabaseConnector.jdbcUrl(task(Map.of(), "MARIADB", "maria", "core")))
                .isEqualTo("jdbc:mariadb://maria:3306/core");
        assertThatThrownBy(() -> DatabaseConnector.jdbcUrl(task(Map.of(), "POSTGRESQL", "db?x=1", "core")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseConnector.jdbcUrl(task(Map.of(), "POSTGRESQL", "db", "core;x=1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseConnector.jdbcUrl(task(Map.of(), "DB2", "db", "core")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cursorsKeepTheirType() {
        DatabaseConnector.Cursor cursor = DatabaseConnector.Cursor.of(java.sql.Timestamp.valueOf("2026-10-01 10:00:00"));
        assertThat(cursor.encode()).isEqualTo("ts:2026-10-01T10:00");
        assertThat(DatabaseConnector.Cursor.parse("ts:2026-10-01T10:00")).isEqualTo(cursor);
        assertThat(DatabaseConnector.Cursor.of(42L).encode()).isEqualTo("n:42");
        assertThat(DatabaseConnector.Cursor.parse("2026-10-01").kind()).isEqualTo("s");
        assertThat(DatabaseConnector.Cursor.parse(null)).isNull();
    }

    private static Connector.TaskContext task(Map<String, Object> spec) {
        return task(spec, "POSTGRESQL", "db", "core");
    }

    private static Connector.TaskContext task(Map<String, Object> spec, String vendor, String host, String database) {
        return new Connector.TaskContext(new Protocol.AgentTask(1, 1, "DATABASE", "READ",
                Map.of("vendor", vendor, "host", host, "database", database), null, spec, null, 10),
                LocalSecrets.Secret.EMPTY);
    }
}
