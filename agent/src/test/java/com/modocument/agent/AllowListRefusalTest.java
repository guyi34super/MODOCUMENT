package com.modocument.agent;

import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.allowlist.AllowListViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bank's local allow-list is the last word: a task MoDocument sends for
 * a table, host, port or folder the bank did not list is REFUSED before any
 * connection is opened or any secret is looked up, and reported as a failed
 * run — so a compromised cloud account cannot make the agent read arbitrary
 * data.
 */
class AllowListRefusalTest {

    private static final String ALLOW = """
            destinations:
              - host: db.bank.local
                ports: [5432]
              - host: sftp.bank.local
                ports: [22]
            database:
              tables: [modocument.v_customers]
            sftp:
              folders: [/exports/modocument]
            """;

    @TempDir
    Path root;

    private FakeCloud cloud;
    private final AtomicInteger opened = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        cloud = new FakeCloud();
    }

    @AfterEach
    void tearDown() {
        cloud.close();
    }

    private AgentHarness agent(String allowList) throws Exception {
        AgentHarness agent = new AgentHarness(cloud, root, allowList, Map.of(), (url, properties) -> {
            opened.incrementAndGet();
            throw new java.sql.SQLException("must not connect");
        }, FakeCloud.TOKEN);
        agent.loop.ensureEnrolled();
        return agent;
    }

    @Test
    void aTableTheBankDidNotListIsRefusedWithoutConnecting() throws Exception {
        AgentHarness agent = agent(ALLOW);
        cloud.queue(task(1, "db.bank.local", "5432", Map.of("table", "app_users",
                "columns", List.of("email", "password_hash"))));
        // Same table name in another schema: still not the listed one.
        cloud.queue(task(2, "db.bank.local", "5432", Map.of("table", "public.v_customers",
                "columns", List.of("id"))));
        agent.loop.pollOnce(0);

        assertThat(cloud.results).hasSize(2).allSatisfy(result -> {
            assertThat(result).containsEntry("success", false);
            assertThat((String) result.get("error")).startsWith("Refused by the agent's local allow-list");
        });
        assertThat(cloud.records).isEmpty();
        assertThat(opened).hasValue(0);
    }

    @Test
    void anUnlistedHostOrPortIsRefused() throws Exception {
        AgentHarness agent = agent(ALLOW);
        cloud.queue(task(3, "hr.bank.local", "5432", Map.of("table", "modocument.v_customers",
                "columns", List.of("id"))));
        cloud.queue(task(4, "db.bank.local", "6432", Map.of("table", "modocument.v_customers",
                "columns", List.of("id"))));
        agent.loop.pollOnce(0);
        assertThat(cloud.results).extracting(r -> (String) r.get("error"))
                .containsExactly(
                        "Refused by the agent's local allow-list: destination hr.bank.local:5432 is not allowed",
                        "Refused by the agent's local allow-list: destination db.bank.local:6432 is not allowed");
        assertThat(opened).hasValue(0);
    }

    @Test
    void withoutAnAllowListFileEverythingIsRefused() throws Exception {
        AgentHarness agent = agent(null);
        cloud.queue(task(5, "db.bank.local", "5432", Map.of("table", "modocument.v_customers",
                "columns", List.of("id"))));
        agent.loop.pollOnce(0);
        assertThat(cloud.results).singleElement()
                .satisfies(result -> assertThat(result).containsEntry("success", false));
        assertThat(opened).hasValue(0);
    }

    @Test
    void sftpFoldersOutsideTheListOrEscapingTheConnectionFolderAreRefused() throws Exception {
        AgentHarness agent = agent(ALLOW);
        cloud.queue(sftpTask(6, "/exports", "../../etc"));
        cloud.queue(sftpTask(7, "/home/bank", "inbox"));
        agent.loop.pollOnce(0);
        assertThat(cloud.results).hasSize(2).allSatisfy(result ->
                assertThat(result).containsEntry("success", false));
        assertThat((String) cloud.results.get(0).get("error")).contains("not a plain path");
        assertThat((String) cloud.results.get(1).get("error"))
                .contains("folder /home/bank/inbox is not allowed");
    }

    @Test
    void aListedTableIsAllowedAndTheSchemaComesFromTheConnection() throws Exception {
        Path file = root.resolve("only.yml");
        Files.writeString(file, ALLOW);
        AllowList allowList = new AllowList(file);
        assertThatCode(() -> allowList.checkTable("MODOCUMENT.V_CUSTOMERS")).doesNotThrowAnyException();
        assertThatCode(() -> allowList.checkDestination("DB.bank.local", 5432)).doesNotThrowAnyException();
        assertThatCode(() -> allowList.checkFolder("/exports/modocument/in")).doesNotThrowAnyException();
        assertThatThrownBy(() -> allowList.checkFolder("/exports/modocument-evil"))
                .isInstanceOf(AllowListViolation.class);

        // Connection schema "modocument" + spec table "v_customers" is the listed name.
        AgentHarness agent = agent(ALLOW);
        Map<String, Object> listed = new java.util.HashMap<>(task(8, "db.bank.local", "5432",
                Map.of("table", "v_customers", "columns", List.of("id"))));
        listed.put("config", Map.of("vendor", "POSTGRESQL", "host", "db.bank.local", "port", "5432",
                "database", "core", "schema", "modocument"));
        cloud.queue(listed);
        agent.loop.pollOnce(0);
        // Allowed by the list: the agent did try to connect (the fake DB refuses).
        assertThat(opened).hasValue(1);
        assertThat((String) cloud.results.get(0).get("error")).doesNotContain("allow-list");
    }

    private static Map<String, Object> task(long runId, String host, String port, Map<String, Object> spec) {
        return Map.of("runId", runId, "connectionId", 12, "type", "DATABASE", "operation", "READ",
                "config", Map.of("vendor", "POSTGRESQL", "host", host, "port", port, "database", "core"),
                "spec", spec, "maxRecords", 100);
    }

    private static Map<String, Object> sftpTask(long runId, String base, String folder) {
        return Map.of("runId", runId, "connectionId", 13, "type", "SFTP", "operation", "READ",
                "config", Map.of("host", "sftp.bank.local", "folder", base, "username", "u",
                        "hostKeyFingerprint", "SHA256:x"),
                "spec", Map.of("folder", folder, "glob", "*.csv"), "maxRecords", 10);
    }
}
