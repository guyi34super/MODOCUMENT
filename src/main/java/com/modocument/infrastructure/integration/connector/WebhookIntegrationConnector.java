package com.modocument.infrastructure.integration.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.OutboundAuthType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * WEBHOOK connector (ws25), write only: POSTs a JSON envelope to the bank's
 * HTTPS endpoint ({@code baseUrl} + the target spec's relative {@code path})
 * signed with the connection's {@code hmacSecret} (see {@link WebhookSigner}),
 * plus the batch's {@code Idempotency-Key}. {@link #test} sends a signed
 * {@code ping}.
 *
 * <p>Envelope: {@code {type:"delivery", kind, idempotencyKey, sentAt,
 * records:[…]}} for record batches; a file batch adds {@code file:{name,
 * contentType, contentBase64}}.
 */
@Component
public class WebhookIntegrationConnector implements IntegrationConnector {

    static final Set<String> TARGET_KEYS = Set.of("path");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OutboundHttp http;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookIntegrationConnector(OutboundHttp http) {
        this(http, Clock.systemUTC());
    }

    public WebhookIntegrationConnector(OutboundHttp http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.WEBHOOK;
    }

    @Override
    public void validate(ConnectionContext connection, Map<String, Object> spec, boolean outbound) {
        if (!outbound) {
            throw new IllegalArgumentException("A WEBHOOK connection can only send (outbound flows).");
        }
        http.baseUri(connection);
        OutboundAuthType auth = OutboundHttp.authType(connection);
        if (auth != OutboundAuthType.HMAC_SIGNATURE && auth != OutboundAuthType.NONE) {
            throw new IllegalArgumentException("A WEBHOOK connection is signed with HMAC_SIGNATURE.");
        }
        if (connection.mode() == ConnectionMode.DIRECT
                && (connection.secret().get("hmacSecret") == null
                || connection.secret().get("hmacSecret").length() < 16)) {
            throw new IllegalArgumentException("The connection secret needs an 'hmacSecret' of at "
                    + "least 16 characters.");
        }
        Specs.allowOnly(spec, "target spec", TARGET_KEYS);
        Specs.relativePath(Specs.string(spec, "path"), "path", true);
    }

    @Override
    public ConnectionTestResult test(ConnectionContext connection) {
        long started = System.nanoTime();
        try {
            validate(connection, Map.of(), true);
            Map<String, Object> ping = new LinkedHashMap<>();
            ping.put("type", "ping");
            ping.put("sentAt", clock.instant().toString());
            HttpResponse<byte[]> response = post(connection, Map.of(), MAPPER.writeValueAsBytes(ping),
                    null);
            long millis = (System.nanoTime() - started) / 1_000_000;
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return ConnectionTestResult.ok("The endpoint accepted a signed ping (HTTP " + status
                        + ").", millis);
            }
            return ConnectionTestResult.failed("The endpoint answered HTTP " + status + ".", millis);
        } catch (Exception e) {
            return ConnectionTestResult.failed(ConnectorErrors.safeMessage(e),
                    (System.nanoTime() - started) / 1_000_000);
        }
    }

    @Override
    public ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec,
                          String cursor, int maxRecords) {
        throw new UnsupportedOperationException("A WEBHOOK connection can only send.");
    }

    @Override
    public WriteResult write(ConnectionContext connection, Map<String, Object> targetSpec,
                             OutboundBatch batch) throws Exception {
        validate(connection, targetSpec, true);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "delivery");
        envelope.put("kind", batch.kind() == null ? null : batch.kind().name());
        envelope.put("idempotencyKey", batch.idempotencyKey());
        envelope.put("sentAt", clock.instant().toString());
        envelope.put("records", batch.records());
        if (batch.fileContent() != null) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("name", batch.fileName());
            file.put("contentType", batch.contentType());
            file.put("contentBase64", Base64.getEncoder().encodeToString(batch.fileContent()));
            envelope.put("file", file);
        }
        HttpResponse<byte[]> response = post(connection, targetSpec,
                MAPPER.writeValueAsBytes(envelope), batch.idempotencyKey());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("The webhook endpoint answered HTTP " + status + ".");
        }
        return new WriteResult(Math.max(batch.records().size(), batch.fileContent() == null ? 0 : 1),
                "HTTP " + status);
    }

    private HttpResponse<byte[]> post(ConnectionContext connection, Map<String, Object> spec,
                                      byte[] body, String idempotencyKey) throws Exception {
        URI uri = OutboundHttp.resolve(http.baseUri(connection), Specs.string(spec, "path"), Map.of());
        HttpClient client = http.client(connection, OutboundAuthType.NONE);
        HttpRequest.Builder builder = http.request(connection, OutboundAuthType.NONE, client, uri)
                .header("Content-Type", "application/json");
        long timestamp = clock.instant().getEpochSecond();
        String secret = connection.secret().get("hmacSecret");
        if (secret != null) {
            builder.header(WebhookSigner.TIMESTAMP_HEADER, String.valueOf(timestamp));
            builder.header(WebhookSigner.SIGNATURE_HEADER,
                    WebhookSigner.signature(secret, timestamp, body));
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(client, builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }
}
