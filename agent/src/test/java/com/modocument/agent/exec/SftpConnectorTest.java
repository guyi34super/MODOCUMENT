package com.modocument.agent.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.agent.cloud.Protocol;
import com.modocument.agent.secrets.LocalSecrets;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
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
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SFTP against an embedded MINA SSHD server: the pinned host key is
 * enforced (no trust on first use), reads pull matching files and move them
 * to processed/ once accepted, writes land atomically under their final
 * name.
 */
class SftpConnectorTest {

    private static final String PASSWORD = "sftp-pw-123";

    @TempDir
    Path root;

    private SshServer server;
    private String fingerprint;
    private final SftpConnector connector = new SftpConnector(new ObjectMapper());

    @BeforeEach
    void startServer() throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider(root.resolve("hostkey.ser"));
        server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((user, password, session) -> "bank".equals(user) && PASSWORD.equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        Path files = root.resolve("files");
        Files.createDirectories(files.resolve("exports/in"));
        server.setFileSystemFactory(new VirtualFileSystemFactory(files));
        server.start();
        PublicKey hostKey = keys.loadKeys(null).iterator().next().getPublic();
        fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey);
        Files.writeString(files.resolve("exports/in/a.csv"), "id\n1\n");
        Files.writeString(files.resolve("exports/in/b.txt"), "skip");
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop(true);
    }

    @Test
    void readsMatchingFilesAndMovesThemOnceAccepted() throws Exception {
        Connector.ReadPage page = connector.read(task("READ", fingerprint,
                Map.of("folder", "in", "glob", "*.csv", "afterRead", "MOVE")), null, 10);
        assertThat(page.records()).singleElement().satisfies(record -> {
            assertThat(record).containsEntry("fileName", "a.csv").containsEntry("size", 5);
            assertThat(new String(Base64.getDecoder().decode((String) record.get("contentBase64")),
                    StandardCharsets.UTF_8)).isEqualTo("id\n1\n");
        });
        Path in = root.resolve("files/exports/in");
        assertThat(in.resolve("a.csv")).exists();
        page.afterAccepted().run();
        assertThat(in.resolve("a.csv")).doesNotExist();
        assertThat(in.resolve("processed/a.csv")).exists();
        assertThat(in.resolve("b.txt")).exists();
    }

    @Test
    void writesUnderTheFinalNameViaATemporaryFile() throws Exception {
        Connector.WriteOutcome outcome = connector.write(task("WRITE", fingerprint, Map.of("folder", "in")),
                new Protocol.OutboundBatch("run-9-1", "VALIDATED_RECORDS", "CSV", List.of(), "out.csv",
                        "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(outcome.remoteReference()).isEqualTo("/exports/in/out.csv");
        assertThat(Files.readString(root.resolve("files/exports/in/out.csv"))).isEqualTo("a,b\n");
        assertThat(root.resolve("files/exports/in/.out.csv.part")).doesNotExist();
    }

    @Test
    void anUnknownHostKeyIsRefused() {
        assertThatThrownBy(() -> connector.test(task("TEST", "SHA256:" + "A".repeat(43), Map.of())))
                .hasMessageContaining("SFTP connection");
        assertThatThrownBy(() -> connector.test(task("TEST", null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pinned");
    }

    @Test
    void theConnectionTestPassesWithThePinnedKey() throws Exception {
        connector.test(task("TEST", fingerprint, Map.of()));
    }

    private Connector.TaskContext task(String operation, String hostKey, Map<String, Object> spec) {
        Map<String, String> config = new java.util.HashMap<>(Map.of("host", "127.0.0.1",
                "port", String.valueOf(server.getPort()), "username", "bank", "folder", "/exports"));
        if (hostKey != null) {
            config.put("hostKeyFingerprint", hostKey);
        }
        return new Connector.TaskContext(new Protocol.AgentTask(1, 3, "SFTP", operation, config, "sftp",
                spec, null, 10), new LocalSecrets.Secret(Map.of("password", PASSWORD)));
    }
}
