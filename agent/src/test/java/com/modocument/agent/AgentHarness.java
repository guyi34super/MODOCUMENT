package com.modocument.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.cloud.CloudClient;
import com.modocument.agent.exec.DatabaseConnector;
import com.modocument.agent.exec.RestConnector;
import com.modocument.agent.exec.SftpConnector;
import com.modocument.agent.exec.TaskExecutor;
import com.modocument.agent.runtime.AgentLoop;
import com.modocument.agent.secrets.LocalSecrets;
import com.modocument.agent.state.CredentialStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Wires a real agent (no Spring) against a {@link FakeCloud} and an in-memory H2 "bank database". */
final class AgentHarness {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    final Path stateDir;
    final Path allowList;
    final Path secretsDir;
    final String h2Url;
    final AtomicInteger connectionsOpened = new AtomicInteger();
    final AgentLoop loop;

    AgentHarness(FakeCloud cloud, Path root, String allowListYaml, Map<String, String> env,
                 DatabaseConnector.ConnectionFactory factory, String token) throws IOException {
        this.stateDir = root.resolve("state");
        this.allowList = root.resolve("allowlist.yml");
        this.secretsDir = root.resolve("secrets");
        Files.createDirectories(secretsDir);
        if (allowListYaml != null) {
            Files.writeString(allowList, allowListYaml);
        }
        this.h2Url = "jdbc:h2:mem:bank-" + root.getFileName() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        DatabaseConnector.ConnectionFactory connections = factory != null ? factory : (url, properties) -> {
            connectionsOpened.incrementAndGet();
            return DriverManager.getConnection(h2Url, "sa", "");
        };
        CloudClient client = new CloudClient(cloud.url(), true, MAPPER);
        TaskExecutor executor = new TaskExecutor(List.of(new DatabaseConnector(connections, MAPPER),
                new RestConnector(MAPPER), new SftpConnector(MAPPER)), new AllowList(allowList),
                new LocalSecrets(secretsDir, env::get, MAPPER), client);
        this.loop = new AgentLoop(client, new CredentialStore(stateDir, MAPPER), executor, stateDir,
                "1.0.0", "test-host", token, 0, Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    Connection bankDb() throws SQLException {
        return DriverManager.getConnection(h2Url, "sa", "");
    }

    static Map<String, Object> dbTask(long runId, String operation, Map<String, Object> spec) {
        return Map.of("runId", runId, "connectionId", 12, "type", "DATABASE", "operation", operation,
                "config", Map.of("vendor", "POSTGRESQL", "host", "db.bank.local", "port", "5432",
                        "database", "core", "username", "modoc_reader"),
                "secretRef", "core-db", "spec", spec, "maxRecords", 100);
    }
}
