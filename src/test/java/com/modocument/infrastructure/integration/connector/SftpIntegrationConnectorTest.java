package com.modocument.infrastructure.integration.connector;

import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SFTP connector against an embedded Apache MINA SFTP server (ws25,
 * AC-INT-07): pinned host key (an unknown/changed key is refused), password
 * auth, folder confinement, the size cap, the two-phase read (download, then
 * processed/ only once acknowledged), and the atomic temp-upload-then-rename
 * write.
 */
class SftpIntegrationConnectorTest {

    private static final String USER = "bank";
    private static final String PASSWORD = "s3cret-sftp-password";

    private static SshServer server;
    private static Path root;
    private static String fingerprint;
    private static final List<String> egressChecks = new ArrayList<>();

    @TempDir
    static Path sharedRoot;

    private SftpIntegrationConnector connector;

    @BeforeAll
    static void startServer() throws Exception {
        root = sharedRoot.resolve("sftp-root");
        Files.createDirectories(root);
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

    @BeforeEach
    void setUp() throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .filter(p -> !p.equals(root))
                    .forEach(p -> p.toFile().delete());
        }
        Files.createDirectories(root.resolve("in/inbox"));
        Files.createDirectories(root.resolve("in/outbox"));
        EgressGuard guard = (bankId, host, port) -> egressChecks.add(host + ":" + port);
        connector = new SftpIntegrationConnector(guard,
                ConnectorLimits.defaults().withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(10))
                        .withMaxFileBytes(1024),
                Clock.systemUTC());
    }

    private ConnectionContext context(String pinned, String password) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("host", "127.0.0.1");
        config.put("port", String.valueOf(server.getPort()));
        config.put("username", USER);
        config.put("folder", "/in");
        config.put("hostKeyFingerprint", pinned);
        config.put("authType", "BASIC");
        config.put("sftpAuth", "PASSWORD");
        return new ConnectionContext(1, 7, "sftp", ConnectionType.SFTP, ConnectionMode.DIRECT, config,
                new SecretMaterial(Map.of("password", password)));
    }

    @Test
    void testConnectionPassesWithThePinnedKeyAndChecksEgressFirst() {
        egressChecks.clear();
        ConnectionTestResult result = connector.test(context(fingerprint, PASSWORD));
        assertThat(result.ok()).as(result.message()).isTrue();
        assertThat(egressChecks).containsExactly("127.0.0.1:" + server.getPort());
    }

    @Test
    void aChangedHostKeyIsRefusedBeforeAuthenticating() {
        String other = "SHA256:" + "A".repeat(43);
        ConnectionTestResult result = connector.test(context(other, PASSWORD));
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).containsIgnoringCase("host key");
        assertThatThrownBy(() -> connector.read(context(other, PASSWORD), Map.of("folder", "inbox"), null, 10))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void anUnpinnedHostKeyIsNeverAccepted() {
        assertThatThrownBy(() -> connector.validate(context("", PASSWORD), Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hostKeyFingerprint");
    }

    @Test
    void aWrongPasswordFailsWithoutEchoingIt() {
        ConnectionTestResult result = connector.test(context(fingerprint, "wrong-password-value"));
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).doesNotContain("wrong-password-value");
    }

    @Test
    void pathTraversalIsRefused() {
        for (String folder : List.of("../etc", "/etc", "inbox/../../etc", "a\\b", "./x")) {
            assertThatThrownBy(() -> connector.validate(context(fingerprint, PASSWORD),
                    Map.of("folder", folder), false))
                    .as(folder).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> connector.validate(context(fingerprint, PASSWORD),
                Map.of("folder", "inbox", "glob", "../*.csv"), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connector.validate(context(fingerprint, PASSWORD),
                Map.of("fileNamePattern", "../{name}"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readMatchesTheGlobDownloadsAndMovesToProcessed() throws Exception {
        Files.writeString(root.resolve("in/inbox/a.csv"), "id,name\n1,Ada\n");
        Files.writeString(root.resolve("in/inbox/b.csv"), "id,name\n2,Bob\n");
        Files.writeString(root.resolve("in/inbox/skip.txt"), "no");
        Files.write(root.resolve("in/inbox/huge.csv"), new byte[2048]);

        ReadBatch batch = connector.read(context(fingerprint, PASSWORD),
                Map.of("folder", "inbox", "glob", "*.csv"), null, 10);

        assertThat(batch.files()).extracting(ReadBatch.InboundFile::fileName)
                .containsExactly("a.csv", "b.csv");
        assertThat(new String(batch.files().get(0).content(), StandardCharsets.UTF_8)).contains("Ada");
        assertThat(batch.files().get(0).remotePath()).isEqualTo("inbox/a.csv");
        // Two-phase read: nothing moves until the caller acknowledges.
        assertThat(root.resolve("in/inbox/a.csv")).exists();
        assertThat(root.resolve("in/inbox/processed")).doesNotExist();
        assertThat(batch.notes()).singleElement().asString().contains("huge.csv");
        batch.acknowledgeAll();
        assertThat(root.resolve("in/inbox/processed/a.csv")).exists();
        assertThat(root.resolve("in/inbox/a.csv")).doesNotExist();
        // Over the size cap: skipped and left in place.
        assertThat(root.resolve("in/inbox/huge.csv")).exists();
        assertThat(root.resolve("in/inbox/skip.txt")).exists();
    }

    @Test
    void readCanDeleteInsteadOfMoving() throws Exception {
        Files.writeString(root.resolve("in/inbox/c.csv"), "x\n1\n");
        ReadBatch batch = connector.read(context(fingerprint, PASSWORD),
                Map.of("folder", "inbox", "afterRead", "DELETE"), null, 10);
        assertThat(batch.files()).hasSize(1);
        assertThat(root.resolve("in/inbox/c.csv")).exists();
        batch.acknowledgeAll();
        assertThat(root.resolve("in/inbox/c.csv")).doesNotExist();
        assertThat(root.resolve("in/inbox/processed/c.csv")).doesNotExist();
    }

    @Test
    void writeUploadsToATempNameThenRenamesAndIsIdempotent() throws Exception {
        OutboundBatch batch = new OutboundBatch("abcdef0123456789abcdef", PayloadKind.VALIDATED_RECORDS,
                PayloadFormat.CSV, List.of(Map.of("id", "1")), "result.csv", "text/csv",
                "id\n1\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> target = Map.of("folder", "outbox", "fileNamePattern", "{base}-{key}.{ext}");

        WriteResult result = connector.write(context(fingerprint, PASSWORD), target, batch);

        assertThat(result.remoteReference()).isEqualTo("result-abcdef0123456789.csv");
        Path written = root.resolve("in/outbox/result-abcdef0123456789.csv");
        assertThat(Files.readString(written)).isEqualTo("id\n1\n");
        try (Stream<Path> files = Files.list(root.resolve("in/outbox"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".tmp-"));
        }
        // The same batch again: already delivered, not overwritten twice.
        WriteResult again = connector.write(context(fingerprint, PASSWORD), target, batch);
        assertThat(again.remoteReference()).contains("already present");
    }

    @Test
    void listFilesPreviewsWithoutMovingAnything() throws Exception {
        Files.writeString(root.resolve("in/inbox/d.csv"), "x\n");
        List<Map<String, Object>> rows = connector.listFiles(context(fingerprint, PASSWORD),
                Map.of("folder", "inbox"), 20);
        assertThat(rows).extracting(row -> row.get("fileName")).containsExactly("d.csv");
        assertThat(root.resolve("in/inbox/d.csv")).exists();
    }
}
