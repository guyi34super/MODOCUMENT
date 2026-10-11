package com.modocument.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.modocument.agent.secrets.LocalSecrets;
import com.modocument.agent.state.CredentialStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Secrets never leave the agent through its logs or its reports: neither
 * the client secret MoDocument issued at enrolment, nor the enrolment
 * token, nor the bank's local database password — even when a driver
 * echoes it in an error message, the run's reported error masks it.
 */
class SecretsNeverLoggedTest {

    private static final String DB_PASSWORD = "Pw-very-secret-4711";
    private static final String ALLOW = """
            destinations:
              - host: db.bank.local
                ports: [5432]
            database:
              tables: [v_customers]
            """;

    @TempDir
    Path root;

    private FakeCloud cloud;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() throws Exception {
        cloud = new FakeCloud();
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        rootLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        cloud.close();
    }

    @Test
    void neitherCredentialsNorBankSecretsAppearInLogsOrReports() throws Exception {
        Files.createDirectories(root.resolve("secrets"));
        Files.writeString(root.resolve("secrets").resolve("core-db"),
                "{\"password\":\"" + DB_PASSWORD + "\"}\n");
        AgentHarness agent = new AgentHarness(cloud, root, ALLOW, Map.of(), (url, properties) -> {
            // A driver that echoes the credentials it was given.
            throw new SQLException("FATAL: password authentication failed for user "
                    + properties.getProperty("user") + " with password "
                    + properties.getProperty("password"));
        }, FakeCloud.TOKEN);

        agent.loop.ensureEnrolled();
        cloud.queue(AgentHarness.dbTask(70, "READ", Map.of("table", "v_customers", "columns", List.of("id"))));
        agent.loop.pollOnce(0);
        agent.loop.heartbeatOnce();

        String reported = (String) cloud.results.get(0).get("error");
        assertThat(reported).contains("password authentication failed").contains("***")
                .doesNotContain(DB_PASSWORD);

        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
            assertThat(line).doesNotContain(DB_PASSWORD)
                    .doesNotContain(FakeCloud.CLIENT_SECRET)
                    .doesNotContain(FakeCloud.TOKEN)
                    .doesNotContain(FakeCloud.ACCESS_TOKEN);
        }
    }

    @Test
    void valueObjectsRedactThemselves() {
        LocalSecrets.Secret secret = new LocalSecrets.Secret(Map.of("password", DB_PASSWORD));
        assertThat(secret.toString()).contains("password").doesNotContain(DB_PASSWORD);
        CredentialStore.Credentials credentials = new CredentialStore.Credentials(5, "agt_5",
                FakeCloud.CLIENT_SECRET, "https://x/api/integration/oauth/token");
        assertThat(credentials.toString()).doesNotContain(FakeCloud.CLIENT_SECRET);
    }
}
