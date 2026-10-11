package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signed outbound webhooks (ws25, AC-INT-08): the receiving stub verifies
 * {@code X-MoDocument-Signature: sha256=hex(HMAC(secret, timestamp + "." +
 * body))} with the documented verifier, which also rejects a stale
 * timestamp; {@code test()} sends a signed ping.
 */
class WebhookIntegrationConnectorTest {

    private static final String SECRET = "a-webhook-secret-of-32-characters!";

    private HttpServer server;
    private final List<Boolean> verified = Collections.synchronizedList(new ArrayList<>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> keys = Collections.synchronizedList(new ArrayList<>());
    private int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            bodies.add(new String(body, StandardCharsets.UTF_8));
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            verified.add(WebhookSigner.verify(SECRET,
                    exchange.getRequestHeaders().getFirst(WebhookSigner.TIMESTAMP_HEADER),
                    exchange.getRequestHeaders().getFirst(WebhookSigner.SIGNATURE_HEADER), body,
                    Instant.now()));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ConnectionContext context() {
        return new ConnectionContext(1, 5, "hook", ConnectionType.WEBHOOK, ConnectionMode.DIRECT,
                Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks",
                        "authType", "HMAC_SIGNATURE"),
                new SecretMaterial(Map.of("hmacSecret", SECRET)));
    }

    private WebhookIntegrationConnector connector() {
        return new WebhookIntegrationConnector(new OutboundHttp((b, h, p) -> { },
                ConnectorLimits.defaults().withPlainHttp(true)
                        .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)), Clock.systemUTC()));
    }

    @Test
    void deliveriesAreSignedOverTimestampDotBody() throws Exception {
        OutboundBatch batch = new OutboundBatch("key-42", PayloadKind.JOB_STATUS, PayloadFormat.JSON,
                List.of(Map.of("jobId", 7)), null, null, null);
        connector().write(context(), Map.of("path", "modocument"), batch);
        assertThat(verified).containsExactly(true);
        assertThat(keys).containsExactly("key-42");
        assertThat(bodies.get(0)).contains("\"type\":\"delivery\"").contains("\"kind\":\"JOB_STATUS\"")
                .contains("\"jobId\":7");
    }

    @Test
    void testSendsASignedPing() {
        ConnectionTestResult result = connector().test(context());
        assertThat(result.ok()).as(result.message()).isTrue();
        assertThat(bodies.get(0)).contains("\"type\":\"ping\"");
        assertThat(verified).containsExactly(true);
    }

    @Test
    void aFailingEndpointRaisesSoTheOutboxRetries() {
        status = 503;
        OutboundBatch batch = new OutboundBatch("k", PayloadKind.EVENTS, PayloadFormat.JSON,
                List.of(Map.of("id", 1)), null, null, null);
        assertThatThrownBy(() -> connector().write(context(), Map.of(), batch))
                .hasMessageContaining("503");
    }

    @Test
    void theVerifierRejectsTamperedBodiesAndStaleTimestamps() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String signature = WebhookSigner.signature(SECRET, now, body);
        assertThat(signature).matches("sha256=[0-9a-f]{64}");
        assertThat(WebhookSigner.verify(SECRET, String.valueOf(now), signature, body, Instant.now())).isTrue();
        assertThat(WebhookSigner.verify(SECRET, String.valueOf(now), signature,
                "{\"a\":2}".getBytes(StandardCharsets.UTF_8), Instant.now())).isFalse();
        long old = now - 301;
        assertThat(WebhookSigner.verify(SECRET, String.valueOf(old),
                WebhookSigner.signature(SECRET, old, body), body, Instant.now())).isFalse();
    }

    @Test
    void webhooksAreWriteOnlyAndNeedASecret() {
        assertThatThrownBy(() -> connector().validate(context(), Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
        ConnectionContext weak = new ConnectionContext(1, 5, "hook", ConnectionType.WEBHOOK,
                ConnectionMode.DIRECT, context().config(), new SecretMaterial(Map.of("hmacSecret", "short")));
        assertThatThrownBy(() -> connector().validate(weak, Map.of(), true))
                .hasMessageContaining("hmacSecret");
    }
}
