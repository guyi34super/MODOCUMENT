package com.modocument.connector.core.db;

import com.modocument.connector.core.TestContexts;
import com.modocument.connector.core.TestContexts.RecordingGuard;
import com.modocument.connector.core.spi.ConnectionContext;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.Operation;
import com.modocument.connector.core.spi.OutboundBatch;
import com.modocument.connector.core.spi.ReadBatch;
import com.modocument.connector.core.support.ConnectorLimits;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
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
 * The DATABASE connector's resolved drift (connector-sharing.md, "Resolved
 * drift"): one row cap with a look-ahead row, values as text, typed cursors
 * that also accept the agent's pre-1.2.0 prefixes, identifiers quoted per
 * vendor with the schema from the connection, the guard asked first, and the
 * password required only where MoDocument holds the secret.
 */
class DatabaseConnectorTest {

    private final AtomicReference<Properties> lastProperties = new AtomicReference<>();

    private DatabaseConnector connector(String h2Url) {
        return new DatabaseConnector(ConnectorLimits.defaults(), (dialect, url, properties) -> {
            lastProperties.set(properties);
            return DriverManager.getConnection(h2Url, "sa", "");
        });
    }

    private static Map<String, String> config(Map<String, String> extra) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("vendor", "POSTGRESQL");
        config.put("host", "db.bank.example");
        config.put("database", "core");
        config.put("username", "reader");
        config.putAll(extra);
        return config;
    }

    private static ConnectionContext direct(RecordingGuard guard) {
        return TestContexts.direct(ConnectionType.DATABASE, config(Map.of()), Map.of("password", "pw"), guard);
    }

    private static String h2() {
        return "jdbc:h2:mem:core" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private static void sql(String url, String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    @Test
    void aPageOfExactlyMaxRowsSaysThereIsNoMore() throws Exception {
        String url = h2();
        sql(url, "CREATE TABLE \"t\" (\"id\" INT PRIMARY KEY, \"seq\" BIGINT)",
                "INSERT INTO \"t\" VALUES (1, 1), (2, 2), (3, 3)");
        DatabaseConnector db = connector(url);
        Map<String, Object> spec = Map.of("table", "t", "watermarkColumn", "seq");
        ReadBatch exact = db.read(direct(new RecordingGuard()), spec, null, 3);
        assertThat(exact.records()).hasSize(3);
        assertThat(exact.hasMore()).isFalse(); // the look-ahead row did not exist
        ReadBatch capped = db.read(direct(new RecordingGuard()), spec, null, 2);
        assertThat(capped.records()).hasSize(2);
        assertThat(capped.hasMore()).isTrue();
        assertThat(capped.nextCursor()).isEqualTo("N:2"); // the look-ahead row is not consumed
        // Without a watermark there is no paging: never "more".
        assertThat(db.read(direct(new RecordingGuard()), Map.of("table", "t"), null, 2).hasMore()).isFalse();
    }

    @Test
    void valuesArriveAsTextAndBinaryIsNotRecordData() throws Exception {
        String url = h2();
        sql(url, "CREATE TABLE \"v\" (\"id\" INT, \"amount\" DECIMAL(10,2), \"at\" TIMESTAMP, \"blob\" VARBINARY(4))",
                "INSERT INTO \"v\" VALUES (7, 1.50, TIMESTAMP '2026-01-02 03:04:05', X'01')");
        Map<String, Object> row = connector(url).read(direct(new RecordingGuard()), Map.of("table", "v"), null, 5)
                .records().get(0);
        assertThat(row).containsEntry("id", "7").containsEntry("amount", "1.50")
                .containsEntry("at", "2026-01-02T03:04:05").containsEntry("blob", null);
    }

    @Test
    void cursorsAreTypedAndTheAgentsLegacyPrefixesStillBind() throws Exception {
        assertThat(DatabaseConnector.encodeCursor(Types.TIMESTAMP, Timestamp.valueOf("2026-10-01 10:00:00")))
                .isEqualTo("T:2026-10-01T10:00");
        assertThat(DatabaseConnector.encodeCursor(Types.BIGINT, 42L)).isEqualTo("N:42");
        assertThat(DatabaseConnector.encodeCursor(Types.VARCHAR, "k-9")).isEqualTo("S:k-9");
        assertThat(DatabaseConnector.encodeCursor(Types.DATE, java.sql.Date.valueOf("2026-10-01")))
                .isEqualTo("D:2026-10-01");

        String url = h2();
        sql(url, "CREATE TABLE \"l\" (\"seq\" BIGINT, \"day\" DATE, \"code\" VARCHAR(5))",
                "INSERT INTO \"l\" VALUES (9, DATE '2026-01-09', 'a9'), (10, DATE '2026-01-10', 'b10'), "
                        + "(11, DATE '2026-01-11', 'c11')");
        DatabaseConnector db = connector(url);
        Map<String, Object> bySeq = Map.of("table", "l", "watermarkColumn", "seq");
        for (String cursor : List.of("N:9", "n:9")) {
            assertThat(db.read(direct(new RecordingGuard()), bySeq, cursor, 10).records())
                    .as(cursor).extracting(r -> r.get("seq")).containsExactly("10", "11");
        }
        Map<String, Object> byDay = Map.of("table", "l", "watermarkColumn", "day");
        for (String cursor : List.of("D:2026-01-10", "d:2026-01-10")) {
            assertThat(db.read(direct(new RecordingGuard()), byDay, cursor, 10).records())
                    .as(cursor).extracting(r -> r.get("seq")).containsExactly("11");
        }
        Map<String, Object> byCode = Map.of("table", "l", "watermarkColumn", "code");
        for (String cursor : List.of("S:b10", "s:b10", "b10")) { // "b10": an untyped legacy value
            assertThat(db.read(direct(new RecordingGuard()), byCode, cursor, 10).records())
                    .as(cursor).extracting(r -> r.get("code")).containsExactly("c11");
        }
    }

    @Test
    void nestedRecordValuesAreWrittenAsJsonText() throws Exception {
        String url = h2();
        sql(url, "CREATE TABLE \"w\" (\"id\" INT PRIMARY KEY, \"meta\" VARCHAR(100), \"n\" INT)");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", 1);
        record.put("meta", Map.of("k", "v"));
        record.put("n", null);
        connector(url).write(direct(new RecordingGuard()), Map.of("table", "w"),
                new OutboundBatch("k", "VALIDATED_RECORDS", "JSON", List.of(record), null, null, null));
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"meta\", \"n\" FROM \"w\"")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("{\"k\":\"v\"}");
            assertThat(rs.getObject(2)).isNull();
        }
    }

    @Test
    void authorizeAsksTheGuardAboutHostThenTableBeforeValidating() {
        RecordingGuard guard = new RecordingGuard();
        DatabaseConnector db = connector(h2());
        ConnectionContext withSchema = TestContexts.agent(ConnectionType.DATABASE,
                config(Map.of("schema", "modocument", "port", "6432")), Map.of(), guard);
        db.authorize(withSchema, Map.of("table", "v_customers", "columns", List.of("id")), Operation.READ);
        assertThat(guard.calls).containsExactly("host db.bank.example:6432", "table modocument.v_customers");

        // A dotted spec table is never valid SQL here, but the guard answers first.
        RecordingGuard refusing = new RecordingGuard("table ");
        assertThatThrownBy(() -> db.authorize(TestContexts.agent(ConnectionType.DATABASE, config(Map.of()),
                Map.of(), refusing), Map.of("table", "public.v_customers"), Operation.READ))
                .isInstanceOf(SecurityException.class).hasMessageContaining("public.v_customers");
        // Allowed by the guard, still refused by the identifier rule.
        assertThatThrownBy(() -> db.authorize(TestContexts.agent(ConnectionType.DATABASE, config(Map.of()),
                Map.of(), new RecordingGuard()), Map.of("table", "public.v_customers"), Operation.READ))
                .isInstanceOf(IllegalArgumentException.class);
        // TEST touches no table.
        RecordingGuard testGuard = new RecordingGuard();
        db.authorize(TestContexts.agent(ConnectionType.DATABASE, config(Map.of()), Map.of(), testGuard),
                Map.of(), Operation.TEST);
        assertThat(testGuard.calls).containsExactly("host db.bank.example:5432");
    }

    @Test
    void thePasswordIsRequiredOnlyWhereMoDocumentHoldsTheSecret() throws Exception {
        DatabaseConnector db = connector(h2());
        assertThatThrownBy(() -> db.validate(TestContexts.direct(ConnectionType.DATABASE, config(Map.of()),
                Map.of(), new RecordingGuard()), Map.of("table", "t"), false))
                .hasMessageContaining("password");
        // The agent: a bank database may trust the agent's host; no password property is sent.
        String url = h2();
        sql(url, "CREATE TABLE \"t\" (\"id\" INT)");
        RecordingGuard guard = new RecordingGuard();
        connector(url).read(TestContexts.agent(ConnectionType.DATABASE, config(Map.of()), Map.of(), guard),
                Map.of("table", "t"), null, 1);
        assertThat(lastProperties.get().getProperty("password")).isNull();
        assertThat(lastProperties.get().getProperty("user")).isEqualTo("reader");
        assertThat(guard.calls).containsExactly("host db.bank.example:5432"); // right before connecting
    }

    @Test
    void theGuardRefusalStopsTheConnection() {
        List<String> opened = new ArrayList<>();
        DatabaseConnector db = new DatabaseConnector(ConnectorLimits.defaults(), (dialect, url, properties) -> {
            opened.add(url);
            throw new AssertionError("must not connect");
        });
        assertThatThrownBy(() -> db.read(direct(new RecordingGuard("host")), Map.of("table", "t"), null, 1))
                .isInstanceOf(SecurityException.class);
        assertThat(opened).isEmpty();
    }
}
