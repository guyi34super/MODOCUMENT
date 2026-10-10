package com.modocument.agent;

import com.modocument.agent.cloud.AgentTls;
import com.modocument.agent.cloud.CloudClient;
import com.modocument.agent.cloud.CloudException;
import com.modocument.agent.config.AgentConfiguration;
import com.modocument.agent.config.AgentProperties;
import com.modocument.agent.secrets.LocalSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent authenticates with its OWN client certificate (MTLS): the
 * certificate and key come from the local secret store, the certificate is
 * sent at enrolment (no client secret is issued), the token endpoint is
 * called with {@code client_id} only and the certificate in the TLS
 * handshake, and every later call presents the same certificate (the token
 * is bound to it). The fake MoDocument is an HTTPS server that asks for a
 * client certificate like nginx's {@code ssl_verify_client optional}.
 */
class AgentMtlsTest {

    @TempDir
    static Path pkiDir;

    private static TestPki.Identity server;
    private static TestPki.Identity agentId;
    private static TestPki.Identity intruder;

    @TempDir
    Path root;

    private FakeCloud cloud;

    @BeforeAll
    static void pki() throws Exception {
        server = TestPki.create(pkiDir, "server", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
        agentId = TestPki.create(pkiDir, "agent", "CN=modocument-agent-5", null);
        intruder = TestPki.create(pkiDir, "intruder", "CN=someone-else", null);
    }

    @BeforeEach
    void start() throws Exception {
        // Both client certificates pass the TLS layer; the fake decides by fingerprint, like the app.
        cloud = new FakeCloud(TestPki.server(server, agentId, intruder));
    }

    @AfterEach
    void stop() {
        cloud.close();
    }

    private AgentHarness agent(Path dir, TestPki.Identity client, AgentTls.ClientAuth auth) throws Exception {
        AgentTls.ClientCertificate certificate = client == null ? null
                : new AgentTls.ClientCertificate(client.certificatePem(), client.privateKeyPem());
        SSLContext tls = AgentTls.sslContext(certificate, server.certificatePem());
        return new AgentHarness(cloud, dir, "destinations: []\n", Map.of(), null, FakeCloud.TOKEN, tls, auth);
    }

    @Test
    void enrolsWithItsCertificateAndEveryCallPresentsIt() throws Exception {
        AgentHarness agent = agent(root, agentId, new AgentTls.ClientAuth(AgentTls.ClientAuth.MTLS,
                agentId.certificatePem()));

        agent.loop.ensureEnrolled();
        assertThat(cloud.enrolments).singleElement().satisfies(body -> {
            assertThat(body).containsEntry("authMethod", "MTLS")
                    .containsEntry("certificatePem", agentId.certificatePem());
        });
        String stored = Files.readString(agent.stateDir.resolve("credentials.json"));
        assertThat(stored).contains("\"authMethod\":\"MTLS\"").contains("\"clientSecret\":null");

        assertThat(agent.loop.pollOnce(0)).isZero();
        agent.loop.heartbeatOnce();
        assertThat(cloud.tokenForms).singleElement().isEqualTo("grant_type=client_credentials&client_id="
                + FakeCloud.CLIENT_ID);
        assertThat(cloud.tokenBasicAuth).containsExactly(false);
        assertThat(cloud.heartbeats).hasSize(1);
    }

    @Test
    void aRestartReusesTheStoredMtlsCredentialsButNeedsTheCertificate() throws Exception {
        agent(root, agentId, new AgentTls.ClientAuth(AgentTls.ClientAuth.MTLS, agentId.certificatePem()))
                .loop.ensureEnrolled();

        AgentHarness restarted = agent(root, agentId,
                new AgentTls.ClientAuth(AgentTls.ClientAuth.MTLS, agentId.certificatePem()));
        restarted.loop.ensureEnrolled();
        restarted.loop.pollOnce(0);
        assertThat(cloud.enrolments).hasSize(1);

        // Configured back to client credentials: refused at start, never a silent downgrade.
        AgentHarness downgraded = agent(root, null, AgentTls.ClientAuth.oauth2());
        assertThatThrownBy(downgraded.loop::ensureEnrolled).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_AGENT_AUTH_METHOD=MTLS");
    }

    @Test
    void anotherCertificateCannotUseTheAgentsCredentials() throws Exception {
        agent(root, agentId, new AgentTls.ClientAuth(AgentTls.ClientAuth.MTLS, agentId.certificatePem()))
                .loop.ensureEnrolled();

        AgentHarness stolen = agent(root, intruder,
                new AgentTls.ClientAuth(AgentTls.ClientAuth.MTLS, intruder.certificatePem()));
        stolen.loop.ensureEnrolled(); // the stored file alone is not a credential...
        assertThatThrownBy(() -> stolen.loop.pollOnce(0)).isInstanceOf(CloudException.class)
                .hasMessageContaining("401"); // ...the token endpoint wants THE certificate

        AgentHarness noCertificate = agent(root, null,
                new AgentTls.ClientAuth(AgentTls.ClientAuth.MTLS, null));
        noCertificate.loop.ensureEnrolled();
        assertThatThrownBy(() -> noCertificate.loop.pollOnce(0)).isInstanceOf(CloudException.class)
                .hasMessageContaining("401");
    }

    @Test
    void theCertificateAndKeyComeFromTheLocalSecretStore() throws Exception {
        Path secrets = root.resolve("secrets");
        Path folder = secrets.resolve("agent-client-cert");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(AgentTls.CERTIFICATE_PEM), agentId.certificatePem());
        Files.writeString(folder.resolve(AgentTls.PRIVATE_KEY_PEM), agentId.privateKeyPem());
        LocalSecrets local = new LocalSecrets(secrets, name -> null, AgentHarness.MAPPER);

        AgentTls.ClientCertificate certificate = AgentConfiguration.clientCertificate(
                properties("mtls", null), local);
        assertThat(certificate.certificatePem()).isEqualTo(agentId.certificatePem());
        assertThat(certificate.toString()).doesNotContain("BEGIN");
        assertThat(AgentConfiguration.clientCertificate(properties(null, null), local)).isNull();

        // Only a certificate: refused, naming the missing key (never its content).
        Files.delete(folder.resolve(AgentTls.PRIVATE_KEY_PEM));
        assertThatThrownBy(() -> AgentConfiguration.clientCertificate(properties("MTLS", null), local))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("privateKeyPem");
        assertThatThrownBy(() -> AgentTls.ClientAuth.normalize("API_KEY"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tlsMaterialIsCheckedBeforeAnyCall() {
        String pkcs1 = agentId.privateKeyPem().replace("PRIVATE KEY", "RSA PRIVATE KEY");
        assertThatThrownBy(() -> AgentTls.sslContext(
                new AgentTls.ClientCertificate(agentId.certificatePem(), pkcs1), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PKCS#8")
                .hasMessageNotContaining("MII");
        assertThatThrownBy(() -> AgentTls.sslContext(
                new AgentTls.ClientCertificate("not a certificate", agentId.privateKeyPem()), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(AgentTls.sslContext(null, null)).isNull();
        // A client certificate over plain http would never be presented.
        SSLContext tls = AgentTls.sslContext(null, server.certificatePem());
        assertThatThrownBy(() -> new CloudClient("http://127.0.0.1:1", true, AgentHarness.MAPPER, tls))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
    }

    private AgentProperties properties(String method, Path ca) {
        return new AgentProperties("https://modocument.example", null, null, root.resolve("state"),
                root.resolve("allow.yml"), root.resolve("secrets"), null, null, null, false, false,
                method, null, ca);
    }
}
