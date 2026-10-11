package com.modocument.connector.core.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.connector.core.rest.OutboundAuthType;
import com.modocument.connector.core.rest.OutboundHttp;
import com.modocument.connector.core.rest.RestApiConnector;
import com.modocument.connector.core.spi.ConnectionContext;
import com.modocument.connector.core.spi.ConnectionTestResult;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.Connector;
import com.modocument.connector.core.spi.Operation;
import com.modocument.connector.core.spi.OutboundBatch;
import com.modocument.connector.core.spi.ReadBatch;
import com.modocument.connector.core.spi.WriteResult;
import com.modocument.connector.core.support.ConnectorErrors;
import com.modocument.connector.core.support.Specs;

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
 * WEBHOOK connector, write only: POSTs a JSON envelope to the bank's HTTPS
 * endpoint ({@code baseUrl}/{@code url} + the target spec's relative
 * {@code path}) signed with the connection's {@code hmacSecret} (see
 * {@link WebhookSigner}; signed at SEND time, so a retry carries a fresh
 * timestamp), plus the batch's {@code Idempotency-Key}. {@link #test} sends
 * a signed {@code ping}.
 *
 * <p>Envelope: {@code {type:"delivery", kind, idempotencyKey, sentAt,
 * records:[…]}} for record batches; a file batch adds {@code file:{name,
 * contentType, contentBase64}}.
 */
public class WebhookConnector implements Connector {

    public static final Set<String> TARGET_KEYS = Set.of("path");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OutboundHttp http;
    private final Clock clock;

    public WebhookConnector(OutboundHttp http, Clock clock) {
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
        if (connection.secretRequired()
                && (connection.secret().get("hmacSecret") == null
                || connection.secret().get("hmacSecret").length() < 16)) {
            throw new IllegalArgumentException("The connection secret needs an 'hmacSecret' of at "
                    + "least 16 characters.");
        }
        Specs.allowOnly(spec, "target spec", TARGET_KEYS);
        Specs.relativePath(Specs.string(spec, "path"), "path", true);
    }

    @Override
    public void authorize(ConnectionContext connection, Map<String, Object> spec, Operation operation) {
        if (operation == Operation.READ) {
            throw new IllegalArgumentException("A WEBHOOK connection can only send (outbound flows).");
        }
        URI base = http.baseUri(connection);
        connection.guard().checkHost(base.getHost(), OutboundHttp.port(base));
        Map<String, Object> target = operation == Operation.TEST ? Map.of() : spec;
        connection.guard().checkPath(RestApiConnector.fullPath(base, Specs.string(target, "path")));
        validate(connection, target, true);
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
            long millis = Connector.elapsedMillis(started);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return ConnectionTestResult.ok("The endpoint accepted a signed ping (HTTP " + status
                        + ").", millis);
            }
            return ConnectionTestResult.failed("The endpoint answered HTTP " + status + ".", millis);
        } catch (Exception e) {
            return ConnectionTestResult.failed(ConnectorErrors.safeMessage(e),
                    Connector.elapsedMillis(started));
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
        envelope.put("kind", batch.kind());
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
