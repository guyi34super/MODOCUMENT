package com.modocument.agent;

import com.modocument.agent.cloud.CloudException;
import com.modocument.agent.runtime.HealthFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent end to end against a JDK HttpServer fake of MoDocument: enrol
 * with the one-time token (credentials stored 0600), long-poll, READ a table
 * of the bank's (H2) database by identifier with a watermark, upload the
 * records, report the result; WRITE an outbound batch in one transaction;
 * heartbeat with version and connection health; restart without the token.
 */
class AgentEndToEndTest {

    private static final String ALLOW = """
            destinations:
              - host: db.bank.local
                ports: [5432]
            database:
              tables:
                - v_customers
                - staging_results
            """;

    @TempDir
    Path root;

    private FakeCloud cloud;
    private AgentHarness agent;

    @BeforeEach
    void setUp() throws Exception {
        cloud = new FakeCloud();
        agent = new AgentHarness(cloud, root, ALLOW, Map.of("MODOCUMENT_SECRET_CORE_DB", "db-pass-123"),
                null, FakeCloud.TOKEN);
        try (Connection db = agent.bankDb(); Statement sql = db.createStatement()) {
            sql.execute("CREATE TABLE v_customers (id INT PRIMARY KEY, name VARCHAR(50), "
                    + "secret_note VARCHAR(50), updated_at TIMESTAMP)");
            sql.execute("INSERT INTO v_customers VALUES "
                    + "(1, 'Ada', 'x', TIMESTAMP '2026-10-01 10:00:00'), "
                    + "(2, 'Grace', 'y', TIMESTAMP '2026-10-02 10:00:00'), "
                    + "(3, 'Linus', 'z', TIMESTAMP '2026-10-03 10:00:00')");
            sql.execute("CREATE TABLE staging_results (ref VARCHAR(20) PRIMARY KEY, amount DECIMAL(10,2), ok BOOLEAN)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection db = agent.bankDb(); Statement sql = db.createStatement()) {
            sql.execute("DROP ALL OBJECTS");
        }
        cloud.close();
    }

    @Test
    void enrolsPollsReadsFromTheBankDatabaseUploadsAndReports() throws Exception {
        agent.loop.ensureEnrolled();
        assertThat(cloud.enrolments).hasSize(1);
        assertThat(cloud.enrolments.get(0)).containsEntry("token", FakeCloud.TOKEN)
                .containsEntry("version", "1.0.0").containsEntry("name", "test-host");
        Path credentials = agent.stateDir.resolve("credentials.json");
        assertThat(Files.readString(credentials)).contains(FakeCloud.CLIENT_ID);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(credentials)))
                    .isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(agent.stateDir)))
                    .isEqualTo("rwx------");
        }

        cloud.queue(AgentHarness.dbTask(41, "READ", Map.of("table", "v_customers",
                "columns", List.of("id", "name"), "watermarkColumn", "updated_at")));
        assertThat(agent.loop.pollOnce(0)).isEqualTo(1);

        assertThat(cloud.records).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) cloud.records.get(0).get("body");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("records");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsKeys("id", "name", "updated_at").doesNotContainKey("secret_note");
        assertThat(page.get("last")).isEqualTo(true);
        assertThat(page.get("nextCursor")).isEqualTo("T:2026-10-03T10:00");
        assertThat(cloud.results).singleElement().satisfies(result -> {
            assertThat(result).containsEntry("runId", 41L).containsEntry("success", true)
                    .containsEntry("recordsIn", 3);
        });
        assertThat(HealthFile.isFresh(agent.stateDir)).isTrue();

        // The next read resumes after the watermark (a cursor an agent before
        // 1.2.0 stored, typed "ts:", still binds as a timestamp).
        cloud.queue(Map.of("runId", 42, "connectionId", 12, "type", "DATABASE", "operation", "READ",
                "config", Map.of("vendor", "POSTGRESQL", "host", "db.bank.local", "database", "core"),
                "secretRef", "core-db", "spec", Map.of("table", "v_customers", "columns", List.of("id"),
                        "watermarkColumn", "updated_at"),
                "cursor", "ts:2026-10-02T10:00", "maxRecords", 100));
        agent.loop.pollOnce(0);
        assertThat(cloud.results.get(1)).containsEntry("runId", 42L).containsEntry("recordsIn", 1);
    }

    @Test
    void writesAnOutboundBatchInOneTransactionAndReportsTheCount() throws Exception {
        agent.loop.ensureEnrolled();
        cloud.payload(Map.of("idempotencyKey", "run-50-1", "kind", "VALIDATED_RECORDS", "format", "JSON",
                "records", List.of(Map.of("ref", "A-1", "amount", 10.5, "ok", true),
                        Map.of("ref", "A-2", "amount", 7, "ok", false))));
        cloud.queue(AgentHarness.dbTask(50, "WRITE", Map.of("table", "staging_results", "mode", "INSERT")));
        agent.loop.pollOnce(0);

        assertThat(cloud.results).singleElement().satisfies(result ->
                assertThat(result).containsEntry("success", true).containsEntry("recordsOut", 2));
        try (Connection db = agent.bankDb(); Statement sql = db.createStatement();
             ResultSet rs = sql.executeQuery("SELECT count(*) FROM staging_results")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }

        // All-or-nothing: a batch whose second row violates the key writes nothing.
        cloud.payload(Map.of("idempotencyKey", "run-51-1", "records",
                List.of(Map.of("ref", "B-1", "amount", 1), Map.of("ref", "A-1", "amount", 2))));
        cloud.queue(AgentHarness.dbTask(51, "WRITE", Map.of("table", "staging_results", "mode", "INSERT")));
        agent.loop.pollOnce(0);
        assertThat(cloud.results.get(1)).containsEntry("success", false);
        try (Connection db = agent.bankDb(); Statement sql = db.createStatement();
             ResultSet rs = sql.executeQuery("SELECT count(*) FROM staging_results WHERE ref = 'B-1'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void heartbeatsCarryVersionAndConnectionHealth() throws Exception {
        agent.loop.ensureEnrolled();
        cloud.queue(AgentHarness.dbTask(60, "TEST", Map.of()));
        agent.loop.pollOnce(0);

        var response = agent.loop.heartbeatOnce();
        assertThat(response.latestVersion()).isEqualTo("9.9.9");
        assertThat(cloud.heartbeats).singleElement().satisfies(beat -> {
            assertThat(beat).containsEntry("version", "1.0.0").containsEntry("status", "OK");
            assertThat(beat.get("connections").toString()).contains("connectionId=12", "ok=true");
        });
    }

    @Test
    void aRestartUsesTheStoredCredentialsAndTheTokenIsSingleUse() throws Exception {
        agent.loop.ensureEnrolled();
        AgentHarness restarted = new AgentHarness(cloud, root, ALLOW, Map.of(), null, FakeCloud.TOKEN);
        restarted.loop.ensureEnrolled();
        assertThat(cloud.enrolments).hasSize(1);
        restarted.loop.pollOnce(0);

        // A fresh state dir cannot reuse the token.
        Path other = root.resolve("other");
        Files.createDirectories(other);
        AgentHarness stranger = new AgentHarness(cloud, other, ALLOW, Map.of(), null, FakeCloud.TOKEN);
        assertThatThrownBy(stranger.loop::ensureEnrolled)
                .isInstanceOf(CloudException.class)
                .hasMessageContaining("401").hasMessageContaining("AGENT_ENROLMENT_INVALID");
        AgentHarness noToken = new AgentHarness(cloud, root.resolve("third"), ALLOW, Map.of(), null, null);
        assertThatThrownBy(noToken.loop::ensureEnrolled).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_ENROLMENT_TOKEN");
    }
}
