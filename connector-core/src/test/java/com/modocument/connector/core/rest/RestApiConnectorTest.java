package com.modocument.connector.core.rest;

import com.modocument.connector.core.TestContexts;
import com.modocument.connector.core.TestContexts.RecordingGuard;
import com.modocument.connector.core.spi.ConnectionContext;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.Operation;
import com.modocument.connector.core.spi.OutboundBatch;
import com.modocument.connector.core.spi.ReadBatch;
import com.modocument.connector.core.support.ConnectorLimits;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REST_API connector's resolved drift (connector-sharing.md): PAGE
 * without a configured size reads until an empty page, a next link must keep
 * the configured path, a cursor is capped, {@code $.} paths are accepted,
 * a missing secret key fails before anything is sent, the guard is asked
 * about the host before every request and about the path in authorize, and a
 * file batch is pushed as the raw file.
 */
class RestApiConnectorTest {

    private HttpServer server;
    private String base;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, String>> headers = Collections.synchronizedList(new ArrayList<>());
    private final List<byte[]> bodies = Collections.synchronizedList(new ArrayList<>());
    private RestApiConnector connector;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        connector = new RestApiConnector(new OutboundHttp(ConnectorLimits.defaults().withPlainHttp(true)
                .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)), Clock.systemUTC()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                + (query.isEmpty() ? "" : "?" + query));
        Map<String, String> h = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        headers.add(h);
        bodies.add(exchange.getRequestBody().readAllBytes());
        String body = "{}";
        switch (exchange.getRequestURI().getPath()) {
            case "/api/sized" -> {
                // The API's own page size is 3; 7 items in all.
                int page = Integer.parseInt(query.replaceAll(".*page=(\\d+).*", "$1"));
                int from = (page - 1) * 3;
                StringBuilder items = new StringBuilder("[");
                for (int i = from; i < Math.min(7, from + 3); i++) {
                    items.append(i == from ? "" : ",").append("{\"id\":").append(i + 1).append('}');
                }
                body = "{\"data\":" + items.append(']') + "}";
            }
            case "/api/cursor" -> body = "{\"items\":[{\"id\":1}],\"meta\":{\"next\":\""
                    + "x".repeat(query.contains("big") ? 1001 : 5) + "\"}}";
            case "/api/linked" -> {
                exchange.getResponseHeaders().add("Link", "</api/other?p=2>; rel=\"next\"");
                body = "[{\"id\":1}]";
            }
            default -> { }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ConnectionContext context(String authType, Map<String, String> secret, RecordingGuard guard) {
        return TestContexts.agent(ConnectionType.REST_API, Map.of("baseUrl", base, "authType", authType),
                secret, guard);
    }

    private List<ReadBatch> readAll(ConnectionContext context, Map<String, Object> spec) throws Exception {
        List<ReadBatch> pages = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 10; i++) {
            ReadBatch page = connector.read(context, spec, cursor, 100);
            pages.add(page);
            if (!page.hasMore()) {
                return pages;
            }
            cursor = page.nextCursor();
        }
        throw new AssertionError("no end");
    }

    @Test
    void pageWithoutAConfiguredSizeReadsUntilAnEmptyPage() throws Exception {
        List<ReadBatch> pages = readAll(context("NONE", Map.of(), new RecordingGuard()),
                Map.of("path", "sized", "recordsPath", "$.data", "pagination", Map.of("type", "PAGE")));
        assertThat(pages).extracting(p -> p.records().size()).containsExactly(3, 3, 1, 0);
        assertThat(pages.get(0).records().get(0)).containsEntry("id", "1");
    }

    @Test
    void pageWithAConfiguredSizeStopsAtAShortPage() throws Exception {
        List<ReadBatch> pages = readAll(context("NONE", Map.of(), new RecordingGuard()),
                Map.of("path", "sized", "recordsPath", "data", "pagination", Map.of("type", "PAGE", "pageSize", 3)));
        assertThat(pages).extracting(p -> p.records().size()).containsExactly(3, 3, 1);
    }

    @Test
    void aNextLinkMustKeepTheConfiguredPath() {
        assertThatThrownBy(() -> connector.read(context("NONE", Map.of(), new RecordingGuard()),
                Map.of("path", "linked", "pagination", Map.of("type", "LINK")), null, 10))
                .isInstanceOf(SecurityException.class).hasMessageContaining("next-page link");
    }

    @Test
    void anOverlongCursorEndsTheRun() throws Exception {
        ConnectionContext context = context("NONE", Map.of(), new RecordingGuard());
        Map<String, Object> spec = Map.of("path", "cursor", "recordsPath", "items",
                "pagination", Map.of("type", "CURSOR", "cursorParam", "c", "cursorPath", "$.meta.next"));
        assertThat(connector.read(context, spec, null, 10).nextCursor()).isEqualTo("xxxxx");
        ReadBatch big = connector.read(context, spec, "big", 10);
        assertThat(big.hasMore()).isFalse();
        assertThat(big.nextCursor()).isNull();
    }

    @Test
    void aSecretKeyTheAuthNeedsIsCheckedBeforeAnythingIsSent() {
        ConnectionContext agentWithoutToken = context("BEARER", Map.of(), new RecordingGuard());
        connector.validate(agentWithoutToken, Map.of("path", "sized"), false); // the bank's secret: not validated
        assertThatThrownBy(() -> connector.read(agentWithoutToken, Map.of("path", "sized"), null, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'token'");
        assertThat(requests).isEmpty();
        ConnectionContext direct = TestContexts.direct(ConnectionType.REST_API,
                Map.of("baseUrl", base, "authType", "BEARER"), Map.of(), new RecordingGuard());
        assertThatThrownBy(() -> connector.validate(direct, Map.of(), false)).hasMessageContaining("'token'");
    }

    @Test
    void theGuardIsAskedAboutTheHostBeforeEveryRequestAndThePathInAuthorize() throws Exception {
        RecordingGuard guard = new RecordingGuard();
        ConnectionContext context = context("BEARER", Map.of("token", "t-1"), guard);
        connector.authorize(context, Map.of("path", "/sized"), Operation.READ);
        int port = server.getAddress().getPort();
        assertThat(guard.calls).containsExactly("host 127.0.0.1:" + port, "path /api/sized");
        guard.calls.clear();
        readAll(context, Map.of("path", "sized", "recordsPath", "data", "pagination", Map.of("type", "PAGE")));
        assertThat(guard.calls).hasSize(requests.size()).allMatch(call -> call.equals("host 127.0.0.1:" + port));
        assertThat(headers).allSatisfy(h -> assertThat(h).containsEntry("authorization", "Bearer t-1"));

        RecordingGuard refusing = new RecordingGuard("path");
        assertThatThrownBy(() -> connector.authorize(context("NONE", Map.of(), refusing),
                Map.of("path", "admin/users"), Operation.READ)).isInstanceOf(SecurityException.class)
                .hasMessageContaining("/api/admin/users");
    }

    @Test
    void aFileBatchIsPushedAsTheRawFile() throws Exception {
        connector.write(context("NONE", Map.of(), new RecordingGuard()), Map.of("path", "push"),
                new OutboundBatch("k-1", "RELEASED_DOCUMENTS", "PDF", List.of(), "a b.pdf", "application/pdf",
                        new byte[] {1, 2, 3}));
        assertThat(requests).containsExactly("POST /api/push");
        assertThat(headers.get(0)).containsEntry("content-type", "application/pdf")
                .containsEntry("x-modocument-file-name", "a_b.pdf").containsEntry("idempotency-key", "k-1");
        assertThat(bodies.get(0)).containsExactly(1, 2, 3);
    }
}
