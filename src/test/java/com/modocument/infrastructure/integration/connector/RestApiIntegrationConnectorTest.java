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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REST_API connector against a JDK {@link HttpServer} stub (ws25,
 * AC-INT-04): PAGE/CURSOR/LINK pagination with a records path, batched
 * POST/PUT with the Idempotency-Key, BASIC/BEARER/API-key auth, OAuth2
 * client credentials with the token cached until expiry, no redirect ever
 * followed, the egress guard asked before every call.
 */
class RestApiIntegrationConnectorTest {

    private HttpServer server;
    private String base;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, String>> headers = Collections.synchronizedList(new ArrayList<>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final List<String> egress = Collections.synchronizedList(new ArrayList<>());
    private RestApiIntegrationConnector connector;
    private OutboundHttp http;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        EgressGuard guard = (bankId, host, port) -> egress.add(host + ":" + port);
        http = new OutboundHttp(guard, ConnectorLimits.defaults().withPlainHttp(true)
                .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)), Clock.systemUTC());
        connector = new RestApiIntegrationConnector(http);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        requests.add(exchange.getRequestMethod() + " " + uri);
        Map<String, String> h = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        headers.add(h);
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
        int status = 200;
        String body = "{}";
        switch (path) {
            case "/api/customers" -> {
                if (query.contains("page=1")) {
                    body = "{\"data\":{\"items\":[{\"id\":1,\"name\":\"Ada\"},{\"id\":2,\"name\":\"Bob\"}]}}";
                } else if (query.contains("page=2")) {
                    body = "{\"data\":{\"items\":[{\"id\":3,\"name\":\"Cy\"}]}}";
                } else {
                    body = "{\"data\":{\"items\":[]}}";
                }
            }
            case "/api/cursor" -> body = query.contains("after=t1")
                    ? "{\"items\":[{\"id\":2}],\"next\":null}"
                    : "{\"items\":[{\"id\":1}],\"next\":\"t1\"}";
            case "/api/linked" -> {
                exchange.getResponseHeaders().add("Link", "<http://evil.example.com/next>; rel=\"next\"");
                body = "[{\"id\":1}]";
            }
            case "/api/oauth/token" -> {
                tokenCalls.incrementAndGet();
                body = "{\"access_token\":\"tok-" + tokenCalls.get() + "\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":3600}";
            }
            case "/api/redirect" -> {
                status = 302;
                exchange.getResponseHeaders().add("Location", "/api/elsewhere");
            }
            case "/api/denied" -> status = 401;
            case "/api/push" -> status = 201;
            default -> { }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 302 ? -1 : bytes.length);
        if (status != 302) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private ConnectionContext context(String authType, Map<String, String> extraConfig,
                                      Map<String, String> secret) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("baseUrl", base);
        config.put("authType", authType);
        config.putAll(extraConfig);
        return new ConnectionContext(1, 11, "rest", ConnectionType.REST_API, ConnectionMode.DIRECT,
                config, new SecretMaterial(secret));
    }

    @Test
    void pagePaginationFollowsPagesWithTheRecordsPath() throws Exception {
        ConnectionContext ctx = context("BEARER", Map.of(), Map.of("token", "bank-token"));
        Map<String, Object> spec = Map.of("path", "/customers", "recordsPath", "data.items",
                "pagination", Map.of("type", "PAGE", "pageParam", "page", "sizeParam", "size",
                        "pageSize", "2"));

        ReadBatch first = connector.read(ctx, spec, null, 100);
        assertThat(first.records()).hasSize(2);
        assertThat(first.records().get(0)).containsEntry("name", "Ada");
        assertThat(first.hasMore()).isTrue();
        ReadBatch second = connector.read(ctx, spec, first.nextCursor(), 100);
        assertThat(second.records()).extracting(r -> r.get("id")).containsExactly("3");
        assertThat(second.hasMore()).isFalse();

        assertThat(requests.get(0)).isEqualTo("GET /api/customers?page=1&size=2");
        assertThat(headers.get(0)).containsEntry("authorization", "Bearer bank-token");
        assertThat(egress).allMatch(entry -> entry.equals("127.0.0.1:" + server.getAddress().getPort()));
    }

    @Test
    void cursorPaginationReadsTheNextTokenFromTheBody() throws Exception {
        ConnectionContext ctx = context("NONE", Map.of(), Map.of());
        Map<String, Object> spec = Map.of("path", "cursor", "recordsPath", "items",
                "pagination", Map.of("type", "CURSOR", "cursorParam", "after", "cursorPath", "next"));
        ReadBatch first = connector.read(ctx, spec, null, 10);
        assertThat(first.nextCursor()).isEqualTo("t1");
        ReadBatch second = connector.read(ctx, spec, first.nextCursor(), 10);
        assertThat(second.records()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void aNextLinkToAnotherHostIsRefused() {
        ConnectionContext ctx = context("NONE", Map.of(), Map.of());
        assertThatThrownBy(() -> connector.read(ctx, Map.of("path", "linked",
                "pagination", Map.of("type", "LINK")), null, 10))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void oauth2TokensAreCachedUntilExpiry() throws Exception {
        ConnectionContext ctx = context("OAUTH2_CLIENT_CREDENTIALS",
                Map.of("tokenUrl", base + "/oauth/token", "clientId", "mod-client", "scope", "read"),
                Map.of("clientSecret", "client-secret-value"));
        Map<String, Object> spec = Map.of("path", "customers", "recordsPath", "data.items",
                "pagination", Map.of("type", "PAGE"));
        connector.read(ctx, spec, null, 10);
        connector.read(ctx, spec, "2", 10);
        assertThat(tokenCalls.get()).isEqualTo(1);
        int tokenRequest = requests.indexOf("POST /api/oauth/token");
        assertThat(bodies.get(tokenRequest)).contains("grant_type=client_credentials").contains("scope=read");
        String basic = headers.get(tokenRequest).get("authorization");
        assertThat(new String(Base64.getDecoder().decode(basic.substring("Basic ".length())),
                StandardCharsets.UTF_8)).isEqualTo("mod-client:client-secret-value");
        assertThat(headers.get(requests.size() - 1)).containsEntry("authorization", "Bearer tok-1");
        assertThat(http.cachedTokens()).isEqualTo(1);
    }

    @Test
    void redirectsAreNeverFollowed() {
        ConnectionContext ctx = context("BASIC", Map.of("username", "u"), Map.of("password", "p"));
        assertThatThrownBy(() -> connector.read(ctx, Map.of("path", "redirect"), null, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redirect");
        assertThat(requests).noneMatch(request -> request.contains("/api/elsewhere"));
    }

    @Test
    void pushSendsTheBatchWithItsIdempotencyKeyAndApiKey() throws Exception {
        ConnectionContext ctx = context("API_KEY_HEADER", Map.of("apiKeyHeader", "X-Bank-Key"),
                Map.of("apiKey", "k-123"));
        OutboundBatch batch = new OutboundBatch("idem-1", PayloadKind.VALIDATED_RECORDS,
                PayloadFormat.JSON, List.of(Map.of("id", "1"), Map.of("id", "2")), null, null, null);
        WriteResult result = connector.write(ctx, Map.of("method", "PUT", "path", "push"), batch);
        assertThat(result.recordsWritten()).isEqualTo(2);
        assertThat(result.remoteReference()).isEqualTo("HTTP 201");
        assertThat(requests.get(0)).isEqualTo("PUT /api/push");
        assertThat(headers.get(0)).containsEntry("idempotency-key", "idem-1")
                .containsEntry("x-bank-key", "k-123");
        assertThat(bodies.get(0)).isEqualTo("[{\"id\":\"1\"},{\"id\":\"2\"}]");
    }

    @Test
    void testReportsRefusedAuthenticationWithoutTheSecret() {
        ConnectionContext ctx = context("BEARER", Map.of("testPath", "denied"),
                Map.of("token", "super-secret-token"));
        ConnectionTestResult result = connector.test(ctx);
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("401").doesNotContain("super-secret-token");
    }

    @Test
    void unsafeSpecsAndUrlsAreRefused() {
        ConnectionContext ctx = context("NONE", Map.of(), Map.of());
        assertThatThrownBy(() -> connector.validate(ctx, Map.of("path", "../admin"), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connector.validate(ctx, Map.of("path", "http://other/x"), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connector.validate(ctx, Map.of("sql", "select 1"), false))
                .isInstanceOf(IllegalArgumentException.class);
        ConnectionContext withCredentials = new ConnectionContext(1, 1, "x", ConnectionType.REST_API,
                ConnectionMode.DIRECT, Map.of("baseUrl", "http://user:pw@127.0.0.1/", "authType", "NONE"),
                SecretMaterial.EMPTY);
        assertThatThrownBy(() -> connector.validate(withCredentials, Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
        OutboundHttp httpsOnly = new OutboundHttp((b, h, p) -> { }, ConnectorLimits.defaults(),
                Clock.systemUTC());
        assertThatThrownBy(() -> httpsOnly.baseUri(ctx)).hasMessageContaining("https");
    }

    @Test
    void resolveKeepsCallsUnderTheBaseUrl() {
        URI uri = OutboundHttp.resolve(URI.create("https://bank.example.com/api/v1"), "/customers",
                Map.of("q", "a b"));
        assertThat(uri.toString()).isEqualTo("https://bank.example.com/api/v1/customers?q=a+b");
    }
}
