package com.modocument.infrastructure.integration.connector;

import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.connector.core.db.DbDialect;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DATABASE connector (ws25, AC-INT-05/06): per-vendor URL building with
 * option allow-lists, identifier injection refused before any SQL, the
 * generated SELECT/INSERT/UPSERT per vendor, and real reads/writes on H2 in
 * the vendors' compatibility modes (watermark paging, INSERT, the MariaDB
 * ON DUPLICATE KEY upsert, all-or-nothing batches). H2 cannot type an
 * Oracle {@code SELECT ? AS c FROM dual} source, so the Oracle and SQL
 * Server MERGE statements are pinned here as SQL and executed live in the
 * connectors-it suite.
 * PostgreSQL ON CONFLICT runs against the real server in
 * {@code DatabaseConnectorPgIT}; Oracle, SQL Server and MariaDB servers in
 * the {@code -Pconnectors-it} suite.
 */
class DatabaseIntegrationConnectorTest {

    private final List<String> egress = new ArrayList<>();
    private final AtomicReference<String> lastUrl = new AtomicReference<>();
    private final AtomicReference<Properties> lastProperties = new AtomicReference<>();

    /** Opens an H2 in-memory database in the vendor's mode, recording the URL the connector built. */
    private DatabaseIntegrationConnector connector(String h2Url) {
        return new DatabaseIntegrationConnector((bank, host, port) -> egress.add(host + ":" + port),
                ConnectorLimits.defaults(), (dialect, url, properties) -> {
            lastUrl.set(url);
            lastProperties.set(properties);
            return DriverManager.getConnection(h2Url, "sa", "");
        });
    }

    private static ConnectionContext context(String vendor, Map<String, String> extra) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("vendor", vendor);
        config.put("host", "db.bank.example");
        config.put("database", "core");
        config.put("username", "reader");
        config.put("authType", "BASIC");
        config.putAll(extra);
        return new ConnectionContext(1, 9, "db", ConnectionType.DATABASE, ConnectionMode.DIRECT, config,
                new SecretMaterial(Map.of("password", "db-password")));
    }

    private static String h2(String mode) {
        return "jdbc:h2:mem:conn" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1";
    }

    private static void sql(String url, String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private static List<String> column(String url, String query) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static OutboundBatch batch(List<Map<String, Object>> records) {
        return new OutboundBatch("k", PayloadKind.VALIDATED_RECORDS, PayloadFormat.JSON, records, null,
                null, null);
    }

    // ---- URLs -------------------------------------------------------------------------

    @Test
    void urlsAreBuiltPerVendorWithTimeoutsAndOnlyAllowedOptions() {
        ConnectorLimits limits = ConnectorLimits.defaults();
        DatabaseIntegrationConnector db = connector(h2("PostgreSQL"));
        assertThat(db.target(context("POSTGRESQL", Map.of("sslmode", "require"))).url(limits))
                .startsWith("jdbc:postgresql://db.bank.example:5432/core?sslmode=require&stringtype=unspecified")
                .contains("connectTimeout=10").contains("socketTimeout=");
        assertThat(db.target(context("ORACLE", Map.of("port", "1522", "sslmode", "verify-full"))).url(limits))
                .isEqualTo("jdbc:oracle:thin:@tcps://db.bank.example:1522/core");
        assertThat(db.target(context("SQLSERVER", Map.of("sslmode", "require"))).url(limits))
                .startsWith("jdbc:sqlserver://db.bank.example:1433;databaseName=core;encrypt=true;"
                        + "trustServerCertificate=true").contains("loginTimeout=10");
        assertThat(db.target(context("MARIADB", Map.of())).url(limits))
                .startsWith("jdbc:mariadb://db.bank.example:3306/core?allowLocalInfile=false");
    }

    @Test
    void dangerousDriverOptionsAndRawUrlsAreRefused() {
        DatabaseIntegrationConnector db = connector(h2("PostgreSQL"));
        for (Map<String, String> bad : List.of(
                Map.of("socketFactory", "org.springframework.context.support.ClassPathXmlApplicationContext"),
                Map.of("loggerFile", "/tmp/x"),
                Map.of("sslmode", "require&socketFactory=x"),
                Map.of("allowLocalInfile", "true"),
                Map.of("host", "db;INIT=RUNSCRIPT FROM 'x'"),
                Map.of("database", "core?sslfactory=x"),
                Map.of("jdbcUrl", "jdbc:h2:mem:x"))) {
            assertThatThrownBy(() -> db.target(context("POSTGRESQL", bad)))
                    .as(bad.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> db.target(context("MYSQL", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- identifiers ---------------------------------------------------------------------

    @Test
    void identifierInjectionIsRefusedBeforeAnySqlIsSent() {
        DatabaseIntegrationConnector db = connector(h2("PostgreSQL"));
        ConnectionContext ctx = context("POSTGRESQL", Map.of());
        for (String table : List.of("customers; DROP TABLE x", "cust\"omers", "public.customers",
                "customers--", "custоmers" /* Cyrillic o */, "1table", "a b", "x/*y*/")) {
            assertThatThrownBy(() -> db.read(ctx, Map.of("table", table), null, 10))
                    .as(table).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> db.write(ctx, Map.of("table", table), batch(List.of(Map.of("a", "1")))))
                    .as(table).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> db.read(ctx, Map.of("table", "t", "columns", List.of("a", "b) FROM x --")),
                null, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.write(ctx, Map.of("table", "t"),
                batch(List.of(Map.of("a\"; DROP", "1"))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.read(ctx, Map.of("table", "t", "sql", "select 1"), null, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(lastUrl.get()).isNull(); // never connected
    }

    // ---- SQL generation per vendor -------------------------------------------------------------

    @Test
    void sqlIsGeneratedWithVendorQuotingAndUpserts() {
        List<String> columns = List.of("id", "name", "amount");
        List<String> keys = List.of("id");
        assertThat(DbDialect.POSTGRESQL.select(DbDialect.POSTGRESQL.qualified("stage", "orders"),
                List.of("id", "updated_at"), "updated_at", true))
                .isEqualTo("SELECT \"id\", \"updated_at\" FROM \"stage\".\"orders\" "
                        + "WHERE \"updated_at\" > ? ORDER BY \"updated_at\"");
        assertThat(DbDialect.POSTGRESQL.upsert("\"t\"", columns, keys))
                .isEqualTo("INSERT INTO \"t\" (\"id\", \"name\", \"amount\") VALUES (?, ?, ?) "
                        + "ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\", "
                        + "\"amount\" = EXCLUDED.\"amount\"");
        assertThat(DbDialect.ORACLE.upsert("\"T\"", columns, keys))
                .isEqualTo("MERGE INTO \"T\" t USING (SELECT ? AS \"id\", ? AS \"name\", ? AS \"amount\" "
                        + "FROM dual) s ON (t.\"id\" = s.\"id\") WHEN MATCHED THEN UPDATE SET "
                        + "t.\"name\" = s.\"name\", t.\"amount\" = s.\"amount\" WHEN NOT MATCHED THEN "
                        + "INSERT (\"id\", \"name\", \"amount\") VALUES (s.\"id\", s.\"name\", s.\"amount\")");
        assertThat(DbDialect.SQLSERVER.upsert("[dbo].[t]", columns, keys))
                .isEqualTo("MERGE INTO [dbo].[t] WITH (HOLDLOCK) AS t USING (VALUES (?, ?, ?)) AS s "
                        + "([id], [name], [amount]) ON (t.[id] = s.[id]) WHEN MATCHED THEN UPDATE SET "
                        + "t.[name] = s.[name], t.[amount] = s.[amount] WHEN NOT MATCHED THEN "
                        + "INSERT ([id], [name], [amount]) VALUES (s.[id], s.[name], s.[amount]);");
        assertThat(DbDialect.MARIADB.upsert("`t`", columns, keys))
                .isEqualTo("INSERT INTO `t` (`id`, `name`, `amount`) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `amount` = VALUES(`amount`)");
        assertThat(DbDialect.POSTGRESQL.upsert("\"t\"", List.of("id"), keys))
                .endsWith("ON CONFLICT (\"id\") DO NOTHING");
        assertThat(DbDialect.SQLSERVER.select("[t]", List.of(), null, false)).isEqualTo("SELECT * FROM [t]");
    }

    // ---- reads ----------------------------------------------------------------------------

    @Test
    void readsPageByTypedWatermarkUnderTheRowCap() throws Exception {
        String url = h2("PostgreSQL");
        sql(url, "CREATE TABLE \"customers\" (\"id\" INT PRIMARY KEY, \"name\" VARCHAR(50), "
                        + "\"updated_at\" TIMESTAMP)",
                "INSERT INTO \"customers\" VALUES (1, 'Ada', TIMESTAMP '2026-01-01 10:00:00'), "
                        + "(2, 'Bob', TIMESTAMP '2026-01-02 10:00:00'), (3, 'Cy', TIMESTAMP '2026-01-03 10:00:00')");
        DatabaseIntegrationConnector db = connector(url);
        ConnectionContext ctx = context("POSTGRESQL", Map.of());
        Map<String, Object> spec = Map.of("table", "customers", "columns", List.of("id", "name"),
                "watermarkColumn", "updated_at");

        ReadBatch first = db.read(ctx, spec, null, 2);
        assertThat(first.records()).extracting(r -> r.get("name")).containsExactly("Ada", "Bob");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isEqualTo("T:2026-01-02T10:00");
        ReadBatch second = db.read(ctx, spec, first.nextCursor(), 2);
        assertThat(second.records()).extracting(r -> r.get("name")).containsExactly("Cy");
        assertThat(second.hasMore()).isFalse();
        ReadBatch none = db.read(ctx, spec, second.nextCursor(), 2);
        assertThat(none.records()).isEmpty();
        assertThat(none.nextCursor()).isEqualTo(second.nextCursor());

        assertThat(egress).allMatch(e -> e.equals("db.bank.example:5432"));
        assertThat(lastProperties.get().getProperty("user")).isEqualTo("reader");
        assertThat(lastProperties.get().getProperty("password")).isEqualTo("db-password");
    }

    @Test
    void numericWatermarksBindAsNumbers() throws Exception {
        String url = h2("PostgreSQL");
        sql(url, "CREATE TABLE \"ledger\" (\"seq\" BIGINT, \"amount\" DECIMAL(10,2))",
                "INSERT INTO \"ledger\" VALUES (9, 1.50), (10, 2.00), (11, 3.25)");
        DatabaseIntegrationConnector db = connector(url);
        ReadBatch batch = db.read(context("POSTGRESQL", Map.of()),
                Map.of("table", "ledger", "watermarkColumn", "seq"), "N:9", 10);
        assertThat(batch.records()).extracting(r -> r.get("amount")).containsExactly("2.00", "3.25");
        assertThat(batch.nextCursor()).isEqualTo("N:11");
    }

    // ---- writes ---------------------------------------------------------------------------

    @Test
    void insertIsOneTransactionPerBatchAllOrNothing() throws Exception {
        String url = h2("PostgreSQL");
        sql(url, "CREATE TABLE \"staging\" (\"id\" INT PRIMARY KEY, \"name\" VARCHAR(5))");
        DatabaseIntegrationConnector db = connector(url);
        ConnectionContext ctx = context("POSTGRESQL", Map.of());
        db.write(ctx, Map.of("table", "staging"), batch(List.of(
                Map.of("id", "1", "name", "Ada"), Map.of("id", "2", "name", "Bob"))));
        assertThat(column(url, "SELECT \"name\" FROM \"staging\" ORDER BY \"id\"")).containsExactly("Ada", "Bob");

        // The second row violates the column size: nothing of this batch lands.
        assertThatThrownBy(() -> db.write(ctx, Map.of("table", "staging"), batch(List.of(
                Map.of("id", "3", "name", "Cy"), Map.of("id", "4", "name", "far-too-long")))))
                .isInstanceOf(java.sql.SQLException.class);
        assertThat(column(url, "SELECT COUNT(*) FROM \"staging\"")).containsExactly("2");
    }

    @Test
    void mariaDbUpsertUpdatesExistingKeys() throws Exception {
        String url = h2("MySQL");
        sql(url, "CREATE TABLE `accounts` (`id` INT PRIMARY KEY, `status` VARCHAR(20))",
                "INSERT INTO `accounts` VALUES (1, 'OLD')");
        DatabaseIntegrationConnector db = connector(url);
        db.write(context("MARIADB", Map.of()), Map.of("table", "accounts", "mode", "UPSERT",
                "keyColumns", List.of("id")), batch(List.of(Map.of("id", "1", "status", "NEW"),
                Map.of("id", "2", "status", "ADDED"))));
        assertThat(column(url, "SELECT `status` FROM `accounts` ORDER BY `id`")).containsExactly("NEW", "ADDED");
    }

    @Test
    void upsertNeedsKeyColumnsPresentInTheRecords() {
        DatabaseIntegrationConnector db = connector(h2("PostgreSQL"));
        ConnectionContext ctx = context("POSTGRESQL", Map.of());
        assertThatThrownBy(() -> db.validate(ctx, Map.of("table", "t", "mode", "UPSERT"), true))
                .hasMessageContaining("key column");
        assertThatThrownBy(() -> db.write(ctx, Map.of("table", "t", "mode", "UPSERT", "keyColumns",
                List.of("id")), batch(List.of(Map.of("name", "x")))))
                .hasMessageContaining("Key column 'id'");
    }

    @Test
    void testReportsTheProductWithoutTheCredentials() {
        DatabaseIntegrationConnector db = connector(h2("PostgreSQL"));
        ConnectionTestResult result = db.test(context("POSTGRESQL", Map.of()));
        assertThat(result.ok()).as(result.message()).isTrue();
        assertThat(result.message()).startsWith("Connected to H2");
        DatabaseIntegrationConnector failing = new DatabaseIntegrationConnector((b, h, p) -> { },
                ConnectorLimits.defaults(), (dialect, url, properties) -> {
            throw new java.sql.SQLException("password authentication failed for user reader (db-password)",
                    "28P01");
        });
        ConnectionTestResult refused = failing.test(context("POSTGRESQL", Map.of()));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).contains("28P01");
    }

    @Test
    void theEgressGuardRefusalStopsTheConnection() {
        DatabaseIntegrationConnector db = new DatabaseIntegrationConnector((b, h, p) -> {
            throw new SecurityException("The destination " + h + " is not an allowed egress target.");
        }, ConnectorLimits.defaults(), (dialect, url, properties) -> {
            throw new AssertionError("must not connect");
        });
        assertThatThrownBy(() -> db.read(context("POSTGRESQL", Map.of()), Map.of("table", "t"), null, 1))
                .isInstanceOf(SecurityException.class);
    }
}
