package com.modocument.connector.core.sftp;

import com.modocument.connector.core.TestContexts;
import com.modocument.connector.core.TestContexts.RecordingGuard;
import com.modocument.connector.core.spi.ConnectionContext;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.Operation;
import com.modocument.connector.core.spi.OutboundBatch;
import com.modocument.connector.core.spi.ReadBatch;
import com.modocument.connector.core.support.ConnectorLimits;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SFTP connector's resolved drift (connector-sharing.md) against an
 * embedded MINA SSHD server: two-phase reads (nothing moves until the caller
 * acknowledges, and only the acknowledged files move), oversized files
 * skipped with a note, the auth pick without an explicit setting, the guard
 * asked about the folder before the connection settings are validated, and
 * the idempotent temp-then-rename write.
 */
class SftpConnectorTest {

    private static final String PASSWORD = "sftp-password-1";

    @TempDir
    Path root;

    private SshServer server;
    private String fingerprint;
    private final SftpConnector connector = new SftpConnector(ConnectorLimits.defaults()
            .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(10)).withMaxFileBytes(1024), Clock.systemUTC());

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(root.resolve("files/base/in"));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider();
        keys.setAlgorithm(KeyUtils.EC_ALGORITHM);
        server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((user, password, session) -> "bank".equals(user) && PASSWORD.equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root.resolve("files")));
        server.start();
        KeyPair hostKey = ((KeyPairProvider) keys).loadKeys(null).iterator().next();
        fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey.getPublic());
    }

    @AfterEach
    void stop() throws Exception {
        server.stop(true);
    }

    private Map<String, String> config(Map<String, String> extra) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("host", "127.0.0.1");
        config.put("port", String.valueOf(server.getPort()));
        config.put("username", "bank");
        config.put("folder", "/base");
        config.put("hostKeyFingerprint", fingerprint);
        config.putAll(extra);
        return config;
    }

    private ConnectionContext context(RecordingGuard guard) {
        return TestContexts.agent(ConnectionType.SFTP, config(Map.of()), Map.of("password", PASSWORD), guard);
    }

    private Path in(String name) {
        return root.resolve("files/base/in").resolve(name);
    }

    @Test
    void aReadMovesOnlyTheAcknowledgedFiles() throws Exception {
        Files.writeString(in("a.csv"), "a");
        Files.writeString(in("b.csv"), "b");
        ReadBatch batch = connector.read(context(new RecordingGuard()), Map.of("folder", "in", "glob", "*.csv"),
                null, 10);
        assertThat(batch.files()).extracting(ReadBatch.InboundFile::remotePath).containsExactly("in/a.csv", "in/b.csv");
        assertThat(in("a.csv")).exists();
        assertThat(in("b.csv")).exists();

        // Only a.csv was stored safely: b.csv stays for the next run.
        batch.acknowledgement().acknowledge(List.of("in/a.csv", "in/not-from-this-batch.csv"));
        assertThat(in("processed/a.csv")).exists();
        assertThat(in("a.csv")).doesNotExist();
        assertThat(in("b.csv")).exists();
        assertThat(connector.read(context(new RecordingGuard()), Map.of("folder", "in", "glob", "*.csv"), null, 10)
                .files()).extracting(ReadBatch.InboundFile::fileName).containsExactly("b.csv");
    }

    @Test
    void deleteHappensOnAcknowledgementAndAMissingFileIsReported() throws Exception {
        Files.writeString(in("c.csv"), "c");
        Files.writeString(in("d.csv"), "d");
        ReadBatch batch = connector.read(context(new RecordingGuard()), Map.of("folder", "in", "afterRead", "DELETE"),
                null, 10);
        Files.delete(in("d.csv")); // removed by someone else meanwhile
        assertThatThrownBy(batch::acknowledgeAll).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 pulled file(s) could not be deleted");
        assertThat(in("c.csv")).doesNotExist();
    }

    @Test
    void anOversizedFileIsSkippedWithANoteAndLeftInPlace() throws Exception {
        Files.writeString(in("small.csv"), "x");
        Files.write(in("huge.csv"), new byte[2048]);
        ReadBatch batch = connector.read(context(new RecordingGuard()), Map.of("folder", "in"), null, 10);
        assertThat(batch.files()).extracting(ReadBatch.InboundFile::fileName).containsExactly("small.csv");
        assertThat(batch.notes()).singleElement().asString().contains("huge.csv").contains("1024-byte cap");
        batch.acknowledgeAll();
        assertThat(in("huge.csv")).exists();
    }

    @Test
    void withoutAnAuthSettingASecretHoldingOnlyAKeyUsesIt() {
        ConnectionContext keyOnly = TestContexts.agent(ConnectionType.SFTP, config(Map.of()),
                Map.of("privateKeyPem", "-----BEGIN"), new RecordingGuard());
        ConnectionContext both = TestContexts.agent(ConnectionType.SFTP, config(Map.of()),
                Map.of("privateKeyPem", "k", "password", "p"), new RecordingGuard());
        ConnectionContext explicit = TestContexts.agent(ConnectionType.SFTP, config(Map.of("sftpAuth", "PASSWORD")),
                Map.of("privateKeyPem", "k"), new RecordingGuard());
        assertThat(SftpConnector.usesPrivateKey(keyOnly)).isTrue();
        assertThat(SftpConnector.usesPrivateKey(both)).isFalse();
        assertThat(SftpConnector.usesPrivateKey(explicit)).isFalse();
        // The agent's secret is checked at call time: no password, no connection.
        RecordingGuard guard = new RecordingGuard();
        assertThatThrownBy(() -> connector.read(TestContexts.agent(ConnectionType.SFTP, config(Map.of()), Map.of(),
                guard), Map.of(), null, 1)).hasMessageContaining("'password'");
        assertThat(guard.calls).isEmpty();
    }

    @Test
    void authorizeAsksAboutHostAndFolderBeforeValidatingTheConnection() {
        RecordingGuard refusing = new RecordingGuard("folder");
        ConnectionContext unpinned = TestContexts.agent(ConnectionType.SFTP,
                config(Map.of("hostKeyFingerprint", "SHA256:x", "folder", "/home/bank")), Map.of(), refusing);
        assertThatThrownBy(() -> connector.authorize(unpinned, Map.of("folder", "inbox"), Operation.READ))
                .isInstanceOf(SecurityException.class).hasMessageContaining("folder /home/bank/inbox");
        assertThat(refusing.calls).containsExactly("host 127.0.0.1:" + server.getPort(), "folder /home/bank/inbox");
        // A folder escaping the base is not a plain path, whatever the guard says.
        assertThatThrownBy(() -> connector.authorize(context(new RecordingGuard()), Map.of("folder", "../../etc"),
                Operation.READ)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a plain path");
        RecordingGuard test = new RecordingGuard();
        connector.authorize(context(test), Map.of(), Operation.TEST);
        assertThat(test.calls).containsExactly("host 127.0.0.1:" + server.getPort(), "folder /base");
    }

    @Test
    void writesAreAtomicAndIdempotent() throws Exception {
        OutboundBatch batch = new OutboundBatch("0123456789abcdef-tail", "VALIDATED_RECORDS", "CSV",
                List.of(Map.of("id", "1")), "out.csv", "text/csv", "id\n1\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> target = Map.of("folder", "in", "fileNamePattern", "{base}-{key}.{ext}");
        assertThat(connector.write(context(new RecordingGuard()), target, batch).remoteReference())
                .isEqualTo("out-0123456789abcdef.csv");
        assertThat(connector.write(context(new RecordingGuard()), target, batch).remoteReference())
                .contains("already present");
        try (Stream<Path> files = Files.list(root.resolve("files/base/in"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("out-0123456789abcdef.csv");
        }
        assertThatThrownBy(() -> connector.write(context(new RecordingGuard()), Map.of("folder", "in"),
                new OutboundBatch("k", "VALIDATED_RECORDS", "JSON", List.of(Map.of("a", "1")), null, null, null)))
                .hasMessageContaining("needs a file batch");
    }
}
