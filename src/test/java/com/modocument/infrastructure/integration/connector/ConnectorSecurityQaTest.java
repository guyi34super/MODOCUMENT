package com.modocument.infrastructure.integration.connector;

import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.connector.core.webhook.WebhookSigner;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import com.sun.net.httpserver.HttpServer;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA's connector-level security cases (docs/qa/integration-security-tests.md,
 * Wave 4 map) that the connector suites did not pin down:
 * <ul>
 *   <li>S9 — the DATABASE read path runs on a read-only connection, set
 *       before any statement is prepared;</li>
 *   <li>S13 — a symbolic link inside the SFTP folder that points outside it
 *       is not read (nor moved);</li>
 *   <li>S21 — a webhook retry is signed at SEND time, so a retry after the
 *       5-minute window still verifies, and a captured old delivery does not.</li>
 * </ul>
 */
class ConnectorSecurityQaTest {

    @TempDir
    Path temp;

    // ---------------------------------------------------------------- S9

    @Test
    void s09DatabaseReadsRunOnAReadOnlyConnection() throws Exception {
        String url = "jdbc:h2:mem:qa" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection setup = DriverManager.getConnection(url, "sa", "");
             Statement statement = setup.createStatement()) {
            statement.execute("CREATE TABLE \"customers\" (\"id\" INT PRIMARY KEY, \"name\" VARCHAR(40))");
            statement.execute("INSERT INTO \"customers\" VALUES (1, 'Ada'), (2, 'Bob')");
        }
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        DatabaseIntegrationConnector connector = new DatabaseIntegrationConnector((bank, host, port) -> { },
                ConnectorLimits.defaults(), (dialect, jdbcUrl, properties) -> recording(
                        DriverManager.getConnection(url, "sa", ""), calls));
        Map<String, String> config = new LinkedHashMap<>();
        config.put("vendor", "POSTGRESQL");
        config.put("host", "db.bank.example");
        config.put("database", "core");
        config.put("username", "reader");
        ConnectionContext context = new ConnectionContext(1, 3, "core", ConnectionType.DATABASE,
                ConnectionMode.DIRECT, config, new SecretMaterial(Map.of("password", "pw")));

        ReadBatch batch = connector.read(context, Map.of("table", "customers", "columns", List.of("id", "name")),
                null, 10);

        assertThat(batch.records()).hasSize(2);
        int readOnly = calls.indexOf("setReadOnly(true)");
        int prepared = calls.indexOf("prepareStatement");
        assertThat(readOnly).as("calls: %s", calls).isNotNegative();
        assertThat(prepared).as("calls: %s", calls).isGreaterThan(readOnly);
        assertThat(calls).doesNotContain("createStatement"); // never a free-form statement
    }

    private static Connection recording(Connection target, List<String> calls) {
        return (Connection) Proxy.newProxyInstance(ConnectorSecurityQaTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("setReadOnly") || name.equals("setAutoCommit")) {
                        calls.add(name + "(" + args[0] + ")");
                    } else if (name.startsWith("prepare") || name.equals("createStatement")) {
                        calls.add(name);
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ---------------------------------------------------------------- S13

    @Test
    void s13ASymlinkLeavingTheSftpFolderIsNotRead() throws Exception {
        Path root = temp.resolve("sftp-root");
        Path inbox = root.resolve("in/inbox");
        Files.createDirectories(inbox);
        Path outside = temp.resolve("outside-secret.csv");
        Files.writeString(outside, "id,secret\n1,never-leave-the-host\n");
        Files.writeString(inbox.resolve("good.csv"), "id,name\n1,Ada\n");
        Files.createSymbolicLink(inbox.resolve("escape.csv"), outside);

        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider();
        keys.setAlgorithm(KeyUtils.EC_ALGORITHM);
        server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((user, password, session) -> "bank".equals(user) && "pw".equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        try {
            KeyPair hostKey = ((KeyPairProvider) keys).loadKeys(null).iterator().next();
            Map<String, String> config = new LinkedHashMap<>();
            config.put("host", "127.0.0.1");
            config.put("port", String.valueOf(server.getPort()));
            config.put("username", "bank");
            config.put("folder", "/in");
            config.put("hostKeyFingerprint", KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey.getPublic()));
            config.put("authType", "BASIC");
            config.put("sftpAuth", "PASSWORD");
            ConnectionContext context = new ConnectionContext(1, 8, "sftp", ConnectionType.SFTP,
                    ConnectionMode.DIRECT, config, new SecretMaterial(Map.of("password", "pw")));
            SftpIntegrationConnector connector = new SftpIntegrationConnector((bank, host, port) -> { },
                    ConnectorLimits.defaults().withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(10)),
                    Clock.systemUTC());

            ReadBatch batch = connector.read(context, Map.of("folder", "inbox", "glob", "*.csv"), null, 10);

            assertThat(batch.files()).extracting(ReadBatch.InboundFile::fileName).containsExactly("good.csv");
            for (ReadBatch.InboundFile file : batch.files()) {
                assertThat(new String(file.content(), StandardCharsets.UTF_8)).doesNotContain("never-leave");
            }
            assertThat(inbox.resolve("escape.csv")).exists(); // left alone, not moved to processed/
            assertThat(Files.readString(outside)).contains("never-leave-the-host");
        } finally {
            server.stop(true);
        }
    }

    // ---------------------------------------------------------------- S21

    @Test
    void s21AWebhookRetryIsSignedAtSendTime() throws Exception {
        String secret = "qa-webhook-secret-of-32-characters";
        List<String> timestamps = Collections.synchronizedList(new ArrayList<>());
        List<String> signatures = Collections.synchronizedList(new ArrayList<>());
        List<byte[]> bodies = Collections.synchronizedList(new ArrayList<>());
        HttpServer receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/", exchange -> {
            bodies.add(exchange.getRequestBody().readAllBytes());
            timestamps.add(exchange.getRequestHeaders().getFirst(WebhookSigner.TIMESTAMP_HEADER));
            signatures.add(exchange.getRequestHeaders().getFirst(WebhookSigner.SIGNATURE_HEADER));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try {
            Instant first = Instant.parse("2026-10-10T10:00:00Z");
            AtomicReference<Instant> now = new AtomicReference<>(first);
            Clock clock = new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };
            WebhookIntegrationConnector connector = new WebhookIntegrationConnector(
                    new OutboundHttp((b, h, p) -> { }, ConnectorLimits.defaults().withPlainHttp(true)
                            .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)), clock), clock);
            ConnectionContext context = new ConnectionContext(1, 5, "hook", ConnectionType.WEBHOOK,
                    ConnectionMode.DIRECT, Map.of("url", "http://127.0.0.1:" + receiver.getAddress().getPort(),
                    "authType", "HMAC_SIGNATURE"), new SecretMaterial(Map.of("hmacSecret", secret)));
            OutboundBatch batch = new OutboundBatch("delivery-7", PayloadKind.JOB_STATUS, PayloadFormat.JSON,
                    List.of(Map.of("jobId", 7)), null, null, null);

            connector.write(context, Map.of("path", "hooks"), batch);
            now.set(first.plusSeconds(600)); // the outbox retries ten minutes later
            connector.write(context, Map.of("path", "hooks"), batch);

            assertThat(timestamps).containsExactly(String.valueOf(first.getEpochSecond()),
                    String.valueOf(first.plusSeconds(600).getEpochSecond()));
            // The retry verifies at its own send time ...
            assertThat(WebhookSigner.verify(secret, timestamps.get(1), signatures.get(1), bodies.get(1),
                    first.plusSeconds(600))).isTrue();
            // ... while the captured first delivery, replayed now, is stale.
            assertThat(WebhookSigner.verify(secret, timestamps.get(0), signatures.get(0), bodies.get(0),
                    first.plusSeconds(600))).isFalse();
            // A rotated secret does not verify the old signature (no silent acceptance).
            assertThat(WebhookSigner.verify("another-webhook-secret-32-characters", timestamps.get(1),
                    signatures.get(1), bodies.get(1), first.plusSeconds(600))).isFalse();
        } finally {
            receiver.stop(0);
        }
    }
}
