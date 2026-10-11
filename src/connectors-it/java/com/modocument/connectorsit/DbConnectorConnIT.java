package com.modocument.connectorsit;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.infrastructure.integration.connector.DatabaseIntegrationConnector;
import com.modocument.infrastructure.integration.connector.IntegrationConnectorProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.oracle.OracleContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DATABASE connector against live Oracle Free, SQL Server and MariaDB
 * (ws25, AC-INT-05/06, {@code mvn -B verify -Pconnectors-it}, Docker
 * required — CI only). Each vendor goes through the connector's PRODUCTION
 * path: the URL built from host/port/database, the vendor's JDBC driver,
 * test(), a watermark-paged read, INSERT, the vendor UPSERT (MERGE / ON
 * DUPLICATE KEY), the all-or-nothing batch and identifier injection.
 * PostgreSQL runs in {@code DatabaseConnectorPgIT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbConnectorConnIT {

    record Vendor(String name, JdbcDatabaseContainer<?> container, String database, Map<String, String> options,
                  String quoteOpen, String quoteClose) {
        @Override
        public String toString() {
            return name;
        }
    }

    private final List<Vendor> vendors = new ArrayList<>();

    @BeforeAll
    void startDatabases() {
        OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");
        MSSQLServerContainer<?> sqlServer = new MSSQLServerContainer<>(
                "mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();
        MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");
        Stream.of(oracle, sqlServer, mariadb).parallel().forEach(JdbcDatabaseContainer::start);
        vendors.add(new Vendor("ORACLE", oracle, oracle.getDatabaseName(), Map.of(), "\"", "\""));
        vendors.add(new Vendor("SQLSERVER", sqlServer, "master",
                Map.of("encrypt", "true", "trustServerCertificate", "true"), "[", "]"));
        vendors.add(new Vendor("MARIADB", mariadb, mariadb.getDatabaseName(), Map.of("sslMode", "disable"),
                "`", "`"));
    }

    @AfterAll
    void stopDatabases() {
        vendors.forEach(vendor -> vendor.container().stop());
    }

    Stream<Vendor> vendors() {
        return vendors.stream();
    }

    private static ConnectionContext context(Vendor vendor) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("vendor", vendor.name());
        config.put("host", vendor.container().getHost());
        config.put("port", String.valueOf(vendor.container().getFirstMappedPort()));
        config.put("database", vendor.database());
        config.put("username", vendor.container().getUsername());
        config.put("authType", "BASIC");
        config.putAll(vendor.options());
        return new ConnectionContext(1, 1, vendor.name(), ConnectionType.DATABASE, ConnectionMode.DIRECT, config,
                new SecretMaterial(Map.of("password", vendor.container().getPassword())));
    }

    private static DatabaseIntegrationConnector connector() {
        return new DatabaseIntegrationConnector((bank, host, port) -> { }, new IntegrationConnectorProperties());
    }

    private static void sql(Vendor vendor, String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(vendor.container().getJdbcUrl(),
                vendor.container().getUsername(), vendor.container().getPassword());
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                try {
                    s.execute(statement);
                } catch (Exception dropOfMissing) {
                    if (!statement.startsWith("DROP")) {
                        throw dropOfMissing;
                    }
                }
            }
        }
    }

    private static List<String> rows(Vendor vendor, String query) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(vendor.container().getJdbcUrl(),
                vendor.container().getUsername(), vendor.container().getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static String q(Vendor vendor, String identifier) {
        return vendor.quoteOpen() + identifier + vendor.quoteClose();
    }

    private static OutboundBatch batch(List<Map<String, Object>> records) {
        return new OutboundBatch("k", PayloadKind.VALIDATED_RECORDS, PayloadFormat.JSON, records, null, null, null);
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void testConnection(Vendor vendor) {
        ConnectionTestResult result = connector().test(context(vendor));
        assertThat(result.ok()).as(result.message()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void readPagesByWatermark(Vendor vendor) throws Exception {
        String table = q(vendor, "CUSTOMERS");
        sql(vendor, "DROP TABLE " + table,
                "CREATE TABLE " + table + " (" + q(vendor, "ID") + " INT PRIMARY KEY, " + q(vendor, "NAME")
                        + " VARCHAR(50), " + q(vendor, "SEQ") + " INT)",
                "INSERT INTO " + table + " VALUES (1, 'Ada', 10)",
                "INSERT INTO " + table + " VALUES (2, 'Bob', 20)",
                "INSERT INTO " + table + " VALUES (3, 'Cy', 30)");
        DatabaseIntegrationConnector db = connector();
        Map<String, Object> spec = Map.of("table", "CUSTOMERS", "columns", List.of("ID", "NAME"),
                "watermarkColumn", "SEQ");
        ReadBatch first = db.read(context(vendor), spec, null, 2);
        assertThat(first.records()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        ReadBatch second = db.read(context(vendor), spec, first.nextCursor(), 2);
        assertThat(second.records()).extracting(r -> r.get("NAME")).containsExactly("Cy");
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void insertAndUpsertAllOrNothing(Vendor vendor) throws Exception {
        String table = q(vendor, "STAGE_ACCOUNTS");
        sql(vendor, "DROP TABLE " + table,
                "CREATE TABLE " + table + " (" + q(vendor, "ID") + " INT PRIMARY KEY, " + q(vendor, "STATUS")
                        + " VARCHAR(10))");
        DatabaseIntegrationConnector db = connector();
        db.write(context(vendor), Map.of("table", "STAGE_ACCOUNTS"),
                batch(List.of(Map.of("ID", "1", "STATUS", "OPEN"))));
        db.write(context(vendor), Map.of("table", "STAGE_ACCOUNTS", "mode", "UPSERT", "keyColumns", List.of("ID")),
                batch(List.of(Map.of("ID", "1", "STATUS", "CLOSED"), Map.of("ID", "2", "STATUS", "OPEN"))));
        assertThat(rows(vendor, "SELECT " + q(vendor, "STATUS") + " FROM " + table + " ORDER BY " + q(vendor, "ID")))
                .containsExactly("CLOSED", "OPEN");
        assertThatThrownBy(() -> db.write(context(vendor), Map.of("table", "STAGE_ACCOUNTS"), batch(List.of(
                Map.of("ID", "3", "STATUS", "OPEN"), Map.of("ID", "4", "STATUS", "FAR-TOO-LONG-VALUE")))))
                .isInstanceOf(java.sql.SQLException.class);
        assertThat(rows(vendor, "SELECT COUNT(*) FROM " + table)).containsExactly("2");
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void identifierInjectionIsRefused(Vendor vendor) {
        DatabaseIntegrationConnector db = connector();
        for (String table : List.of("x; DROP TABLE CUSTOMERS", "x\"y", "x`y", "x]y", "dbo.x", "x--")) {
            assertThatThrownBy(() -> db.read(context(vendor), Map.of("table", table), null, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
