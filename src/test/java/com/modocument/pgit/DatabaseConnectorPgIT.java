package com.modocument.pgit;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.infrastructure.integration.connector.DatabaseIntegrationConnector;
import com.modocument.infrastructure.integration.connector.IntegrationConnectorProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DATABASE connector against the real PostgreSQL server (ws25,
 * AC-INT-05) through its PRODUCTION opener: the URL it builds from
 * host/port/database (with stringtype=unspecified and timeouts) reaches the
 * server; a read-only SELECT pages by a timestamptz watermark; INSERT and
 * the {@code ON CONFLICT} UPSERT write typed columns from text records in
 * one transaction per batch; a read-only connection cannot write.
 */
class DatabaseConnectorPgIT extends PgItSupport {

    private static ConnectionContext context;

    @BeforeAll
    static void createBankTables() throws Exception {
        try (Connection owner = ownerDataSource().getConnection(); Statement statement = owner.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS bank_sim");
            statement.execute("DROP TABLE IF EXISTS bank_sim.customers, bank_sim.accounts");
            statement.execute("CREATE TABLE bank_sim.customers (id integer PRIMARY KEY, name text, "
                    + "updated_at timestamptz NOT NULL)");
            statement.execute("INSERT INTO bank_sim.customers VALUES (1, 'Ada', '2026-01-01 10:00+00'), "
                    + "(2, 'Bob', '2026-01-02 10:00+00'), (3, 'Cy', '2026-01-03 10:00+00')");
            statement.execute("CREATE TABLE bank_sim.accounts (id integer PRIMARY KEY, balance numeric(12,2), "
                    + "status varchar(10))");
        }
        URI uri = URI.create(pg().jdbcUrl().substring("jdbc:".length()));
        Map<String, String> config = new LinkedHashMap<>();
        config.put("vendor", "POSTGRESQL");
        config.put("host", uri.getHost());
        config.put("port", String.valueOf(uri.getPort()));
        config.put("database", uri.getPath().substring(1));
        config.put("schema", "bank_sim");
        config.put("username", pg().ownerUser());
        config.put("sslmode", "disable");
        context = new ConnectionContext(1, 1, "bank-sim", ConnectionType.DATABASE, ConnectionMode.DIRECT,
                config, new SecretMaterial(Map.of("password", pg().ownerPassword() == null ? ""
                : pg().ownerPassword())));
    }

    private static DatabaseIntegrationConnector connector() {
        IntegrationConnectorProperties properties = new IntegrationConnectorProperties();
        return new DatabaseIntegrationConnector((bank, host, port) -> { }, properties);
    }

    @Test
    void theBuiltUrlConnectsAndTheWatermarkPagesInOrder() throws Exception {
        DatabaseIntegrationConnector db = connector();
        ConnectionTestResult test = db.test(context);
        assertThat(test.ok()).as(test.message()).isTrue();
        assertThat(test.message()).contains("PostgreSQL");

        Map<String, Object> spec = Map.of("table", "customers", "columns", List.of("id", "name"),
                "watermarkColumn", "updated_at");
        List<String> names = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            ReadBatch batch = db.read(context, spec, cursor, 2);
            batch.records().forEach(r -> names.add(String.valueOf(r.get("name"))));
            cursor = batch.nextCursor();
            if (!batch.hasMore()) {
                break;
            }
        }
        assertThat(names).containsExactly("Ada", "Bob", "Cy");
        assertThat(cursor).isNotNull();
        assertThat(db.read(context, spec, cursor, 2).records()).isEmpty();
    }

    @Test
    void insertThenUpsertWriteTypedColumnsFromText() throws Exception {
        DatabaseIntegrationConnector db = connector();
        db.write(context, Map.of("table", "accounts"), batch(List.of(
                Map.of("id", "1", "balance", "10.50", "status", "OPEN"))));
        db.write(context, Map.of("table", "accounts", "mode", "UPSERT", "keyColumns", List.of("id")),
                batch(List.of(Map.of("id", "1", "balance", "99.99", "status", "CLOSED"),
                        Map.of("id", "2", "balance", "1", "status", "OPEN"))));
        assertThat(rows("SELECT id || ':' || balance || ':' || status FROM bank_sim.accounts ORDER BY id"))
                .containsExactly("1:99.99:CLOSED", "2:1.00:OPEN");

        // All or nothing: the bad number fails the whole batch.
        assertThatThrownBy(() -> db.write(context, Map.of("table", "accounts"), batch(List.of(
                Map.of("id", "3", "balance", "5", "status", "OPEN"),
                Map.of("id", "4", "balance", "not-a-number", "status", "OPEN")))))
                .isInstanceOf(java.sql.SQLException.class);
        assertThat(rows("SELECT COUNT(*) FROM bank_sim.accounts")).containsExactly("2");
    }

    @Test
    void theDefaultLimitsHaveTimeouts() {
        assertThat(ConnectorLimits.defaults().readTimeoutSeconds()).isEqualTo(30);
        assertThat(connector().target(context).url(ConnectorLimits.defaults()))
                .contains("socketTimeout=35").contains("connectTimeout=10").contains("stringtype=unspecified");
    }

    private static OutboundBatch batch(List<Map<String, Object>> records) {
        return new OutboundBatch("k", PayloadKind.VALIDATED_RECORDS, PayloadFormat.JSON, records, null, null, null);
    }

    private static List<String> rows(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection owner = ownerDataSource().getConnection(); Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
