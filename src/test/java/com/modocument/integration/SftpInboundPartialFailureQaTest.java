package com.modocument.integration;

import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.IntegrationRunEvent;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.integration.engine.IntegrationJson;
import com.modocument.infrastructure.integration.engine.IntegrationScheduler;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunEventJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA, connector-core merge (Wave 2): the two-phase SFTP read through the
 * real flow engine ({@code FlowRunner}, which had no test for it). An
 * inbound run over three files whose MIDDLE one cannot be ingested (a
 * ".csv" carrying a NUL byte, refused by the upload content check):
 * <ul>
 *   <li>the good files before AND after it are ingested (a failure no longer
 *       stops the run's remaining files) and moved to {@code processed/};</li>
 *   <li>the failed file stays in the folder, so the next run sees it again;</li>
 *   <li>the run FAILS with the reason, and its log names the file;</li>
 *   <li>an oversized file is skipped, left in place, and reported as a run
 *       note.</li>
 * </ul>
 * Embedded Apache MINA SFTP server with a pinned host key, as
 * {@code SftpIntegrationConnectorTest}; the IntegrationTestSupport context.
 */
class SftpInboundPartialFailureQaTest extends IntegrationTestSupport {

    private static final String USER = "bank";
    private static final String PASSWORD = "qa-sftp-password";
    private static final String GOOD = "CustomerName,AccountNo,Amount\n%s,123456789,10\n";

    @TempDir
    static Path sharedRoot;
    private static Path root;
    private static SshServer server;
    private static String fingerprint;

    @Autowired
    private IntegrationScheduler scheduler;
    @Autowired
    private IntegrationRunJpaRepository runs;
    @Autowired
    private IntegrationRunEventJpaRepository runEvents;
    @Autowired
    private IngestionJobJpaRepository jobs;

    @BeforeAll
    static void startServer() throws Exception {
        root = Files.createDirectories(sharedRoot.resolve("sftp-root"));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider();
        keys.setAlgorithm(KeyUtils.EC_ALGORITHM);
        server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((user, password, session) ->
                USER.equals(user) && PASSWORD.equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        KeyPair hostKey = ((KeyPairProvider) keys).loadKeys(null).iterator().next();
        fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey.getPublic());
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop(true);
        }
    }

    private IntegrationConnection sftpConnection() {
        IntegrationConnection connection = new IntegrationConnection();
        connection.setBankId(bank.getBankId());
        connection.setName("qa-sftp");
        connection.setType(ConnectionType.SFTP);
        connection.setMode(ConnectionMode.DIRECT);
        Map<String, String> config = new LinkedHashMap<>();
        config.put("host", "127.0.0.1");
        config.put("port", String.valueOf(server.getPort()));
        config.put("username", USER);
        config.put("folder", "/in");
        config.put("hostKeyFingerprint", fingerprint);
        config.put("sftpAuth", "PASSWORD");
        connection.setConfigJson(IntegrationJson.write(config));
        connection.setAuthType("BASIC");
        SecretVault.Sealed sealed = vault.seal(bank.getBankId(),
                new SecretMaterial(Map.of("password", PASSWORD)));
        connection.setSecretCiphertext(sealed.ciphertext());
        connection.setSecretKeyVersion(sealed.keyVersion());
        connection.setCreatedBy(admin.getAppUserId());
        connection.setCreatedAt(Instant.now());
        return connections.save(connection);
    }

    @Test
    void aFileThatFailsIngestionStaysBehindTheOthersGoThroughAndTheRunFails() throws Exception {
        Path inbox = Files.createDirectories(root.resolve("in/inbox"));
        Files.writeString(inbox.resolve("a.csv"), GOOD.formatted("Ada Lovelace"));
        Files.write(inbox.resolve("b.csv"), "CustomerName,AccountNo\nBad\0Row,1\n".getBytes(StandardCharsets.UTF_8));
        Files.writeString(inbox.resolve("c.csv"), GOOD.formatted("Bob Stone"));

        IntegrationFlow flow = flow("qa sftp pull", sftpConnection(), FlowDirection.INBOUND,
                FlowTrigger.SCHEDULE, Map.of("folder", "inbox", "glob", "*.csv"));
        flow.setCronExpression("0 */5 * * * *");
        flow.setEnabled(true);
        flow.setNextRunAt(Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MINUTES));
        flow = flows.save(flow);

        List<Long> started = scheduler.tick();
        assertThat(started).hasSize(1);
        IntegrationRun run = runs.findById(started.get(0)).orElseThrow();
        List<String> log = runEvents.findByBankIdAndRunIdOrderByIdAsc(bank.getBankId(), run.getId())
                .stream().map(e -> e.getLevel() + " " + e.getMessage()).toList();

        assertThat(run.getStatus()).as("run log %s", log).isEqualTo(RunStatus.FAILED);
        assertThat(log).anyMatch(line -> line.startsWith("WARN") && line.contains("b.csv")
                && line.contains("stays in the folder"));
        assertThat(log).anyMatch(line -> line.contains("a.csv") && line.contains("ingestion job"));
        assertThat(log).as("the file after the failure is still ingested")
                .anyMatch(line -> line.contains("c.csv") && line.contains("ingestion job"));

        // Second phase: only the stored files moved.
        assertThat(inbox.resolve("processed/a.csv")).exists();
        assertThat(inbox.resolve("processed/c.csv")).exists();
        assertThat(inbox.resolve("b.csv")).as("the failed file stays for the next run").exists();
        assertThat(inbox.resolve("a.csv")).doesNotExist();
        assertThat(inbox.resolve("processed/b.csv")).doesNotExist();

        String origin = "flow:" + flow.getId() + "/run:" + run.getId();
        assertThat(jobs.findAll().stream().filter(j -> origin.equals(j.getOrigin())).count())
                .as("one ingestion job per good file").isEqualTo(2);
    }

    @Test
    void anOversizedFileIsSkippedLeftInPlaceAndNoted() throws Exception {
        Path inbox = Files.createDirectories(root.resolve("in/big"));
        Files.writeString(inbox.resolve("ok.csv"), GOOD.formatted("Cy Young"));
        // Over the default 50 MB file cap of the integration connectors.
        try (var out = Files.newOutputStream(inbox.resolve("huge.csv"))) {
            byte[] block = ("x".repeat(1023) + "\n").getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 51 * 1024; i++) {
                out.write(block);
            }
        }

        IntegrationFlow flow = flow("qa sftp big", sftpConnection(), FlowDirection.INBOUND,
                FlowTrigger.SCHEDULE, Map.of("folder", "big", "glob", "*.csv"));
        flow.setCronExpression("0 */5 * * * *");
        flow.setEnabled(true);
        flow.setNextRunAt(Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MINUTES));
        flows.save(flow);

        List<Long> started = scheduler.tick();
        assertThat(started).hasSize(1);
        IntegrationRun run = runs.findById(started.get(0)).orElseThrow();
        List<IntegrationRunEvent> events = runEvents.findByBankIdAndRunIdOrderByIdAsc(bank.getBankId(),
                run.getId());

        assertThat(run.getStatus()).as(run.getError()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(events).anyMatch(e -> e.getLevel().equals("WARN") && e.getMessage().contains("huge.csv"));
        assertThat(inbox.resolve("huge.csv")).as("left in place").exists();
        assertThat(inbox.resolve("processed/ok.csv")).exists();
    }
}
