package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MTLS outbound auth (ws25, AC-INT-04): the client certificate and key come
 * from the connection secret as PEM, the server's private CA from the
 * config; an HTTPS stub that REQUIRES a client certificate accepts the
 * connector and rejects a call without one. Key material is generated per
 * run with the JDK's keytool — nothing is committed.
 */
class RestApiMtlsTest {

    @TempDir
    static Path dir;

    private static HttpsServer server;
    private static String serverCertPem;
    private static String clientCertPem;
    private static String clientKeyPem;
    private static final AtomicReference<String> clientSubject = new AtomicReference<>();

    @BeforeAll
    static void startHttpsServer() throws Exception {
        Path serverStore = keytool("server", "CN=localhost", "SAN=ip:127.0.0.1,dns:localhost");
        Path clientStore = keytool("client", "CN=modocument-client", null);
        KeyStore serverKeys = load(serverStore);
        KeyStore clientKeys = load(clientStore);
        serverCertPem = pem("CERTIFICATE", serverKeys.getCertificate("server").getEncoded());
        clientCertPem = pem("CERTIFICATE", clientKeys.getCertificate("client").getEncoded());
        clientKeyPem = pem("PRIVATE KEY",
                ((PrivateKey) clientKeys.getKey("client", "changeit".toCharArray())).getEncoded());

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(serverKeys, "changeit".toCharArray());
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("client", clientKeys.getCertificate("client"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = context.getDefaultSSLParameters();
                parameters.setNeedClientAuth(true);
                params.setSSLParameters(parameters);
            }
        });
        server.createContext("/", exchange -> {
            try {
                Certificate[] peer = ((com.sun.net.httpserver.HttpsExchange) exchange).getSSLSession()
                        .getPeerCertificates();
                clientSubject.set(((java.security.cert.X509Certificate) peer[0]).getSubjectX500Principal().getName());
            } catch (Exception e) {
                clientSubject.set(null);
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static Path keytool(String alias, String dname, String san) throws Exception {
        Path store = dir.resolve(alias + ".p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(keytool,
                "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", dname, "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "changeit", "-keypass", "changeit"));
        if (san != null) {
            command.add("-ext");
            command.add(san);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        return store;
    }

    private static KeyStore load(Path path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(path.toFile())) {
            store.load(in, "changeit".toCharArray());
        }
        return store;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private ConnectionContext context(Map<String, String> secret) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("baseUrl", "https://127.0.0.1:" + server.getAddress().getPort() + "/");
        config.put("authType", "MTLS");
        config.put("caCertPem", serverCertPem);
        return new ConnectionContext(1, 3, "mtls", ConnectionType.REST_API, ConnectionMode.DIRECT, config,
                new SecretMaterial(secret));
    }

    private RestApiIntegrationConnector connector() {
        return new RestApiIntegrationConnector(new OutboundHttp((bank, host, port) -> { },
                ConnectorLimits.defaults().withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                Clock.systemUTC()));
    }

    @Test
    void theClientCertificateFromTheSecretAuthenticatesTheCall() {
        ConnectionTestResult result = connector().test(context(Map.of("clientCertPem", clientCertPem,
                "clientKeyPem", clientKeyPem)));
        assertThat(result.ok()).as(result.message()).isTrue();
        assertThat(clientSubject.get()).isEqualTo("CN=modocument-client");
    }

    @Test
    void withoutAClientCertificateTheServerRefuses() {
        Map<String, String> config = new LinkedHashMap<>(context(Map.of()).config());
        config.put("authType", "NONE");
        ConnectionContext anonymous = new ConnectionContext(1, 3, "mtls", ConnectionType.REST_API,
                ConnectionMode.DIRECT, config, SecretMaterial.EMPTY);
        ConnectionTestResult result = connector().test(anonymous);
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).doesNotContain("PRIVATE KEY");
    }
}
