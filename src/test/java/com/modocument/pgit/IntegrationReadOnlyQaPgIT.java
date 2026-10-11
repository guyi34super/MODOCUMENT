package com.modocument.pgit;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.infrastructure.integration.connector.DatabaseIntegrationConnector;
import com.modocument.infrastructure.integration.connector.IntegrationConnectorProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S9 (docs/qa/integration-security-tests.md) on the real PostgreSQL server:
 * the DATABASE connector's READ path cannot write, even when the bank-side
 * object it reads would. The source is a view whose column calls a VOLATILE
 * function that INSERTs — a stand-in for any write a read could trigger. The
 * production opener's read-only transaction makes PostgreSQL refuse it
 * ("cannot execute INSERT in a read-only transaction") and nothing is
 * written. A plain view is still readable.
 */
class IntegrationReadOnlyQaPgIT extends PgItSupport {

    private static ConnectionContext context;

    @BeforeAll
    static void createBankObjects() throws Exception {
        try (Connection owner = ownerDataSource().getConnection(); Statement statement = owner.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS bank_qa");
            statement.execute("DROP VIEW IF EXISTS bank_qa.v_customers_probe, bank_qa.v_customers");
            statement.execute("DROP TABLE IF EXISTS bank_qa.customers, bank_qa.write_sink");
            statement.execute("CREATE TABLE bank_qa.customers (id integer PRIMARY KEY, name text)");
            statement.execute("INSERT INTO bank_qa.customers VALUES (1, 'Ada'), (2, 'Bob')");
            statement.execute("CREATE TABLE bank_qa.write_sink (id serial PRIMARY KEY, at timestamptz DEFAULT now())");
            statement.execute("CREATE OR REPLACE FUNCTION bank_qa.write_probe() RETURNS integer "
                    + "LANGUAGE sql VOLATILE AS $$ INSERT INTO bank_qa.write_sink DEFAULT VALUES RETURNING id $$");
            statement.execute("CREATE VIEW bank_qa.v_customers AS SELECT id, name FROM bank_qa.customers");
            statement.execute("CREATE VIEW bank_qa.v_customers_probe AS "
                    + "SELECT id, name, bank_qa.write_probe() AS probe FROM bank_qa.customers");
        }
        URI uri = URI.create(pg().jdbcUrl().substring("jdbc:".length()));
        Map<String, String> config = new LinkedHashMap<>();
        config.put("vendor", "POSTGRESQL");
        config.put("host", uri.getHost());
        config.put("port", String.valueOf(uri.getPort()));
        config.put("database", uri.getPath().substring(1));
        config.put("schema", "bank_qa");
        config.put("username", pg().ownerUser());
        config.put("sslmode", "disable");
        context = new ConnectionContext(1, 1, "bank-qa", ConnectionType.DATABASE, ConnectionMode.DIRECT,
                config, new SecretMaterial(Map.of("password", pg().ownerPassword() == null ? ""
                : pg().ownerPassword())));
    }

    @Test
    void theReadPathCannotWriteEvenThroughAView() throws Exception {
        DatabaseIntegrationConnector db = new DatabaseIntegrationConnector((bank, host, port) -> { },
                new IntegrationConnectorProperties());

        assertThat(db.read(context, Map.of("table", "v_customers", "columns", List.of("id", "name")), null, 10)
                .records()).hasSize(2);

        assertThatThrownBy(() -> db.read(context, Map.of("table", "v_customers_probe",
                "columns", List.of("id", "name", "probe")), null, 10))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("read-only transaction");
        assertThat(sinkRows()).isZero();
    }

    private static int sinkRows() throws Exception {
        try (Connection owner = ownerDataSource().getConnection(); Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM bank_qa.write_sink")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
