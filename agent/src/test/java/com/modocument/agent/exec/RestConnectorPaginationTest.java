package com.modocument.agent.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.agent.FakeCloud;
import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.cloud.CloudClient;
import com.modocument.agent.cloud.Protocol;
import com.modocument.agent.secrets.LocalSecrets;
import com.modocument.agent.secrets.LocalSecrets.Secret;
import com.modocument.agent.state.CredentialStore;
import com.modocument.connector.core.rest.RestApiConnector;
import com.modocument.connector.core.support.JsonFlattener;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REST pagination in the agent (contracts §7.3 {@code sourceSpec.pagination},
 * the same keys as the server's REST connector and the flow builder):
 * PAGE (page/size parameters, short-page stop, maxPages), CURSOR (a dot path
 * in the answer, {@code $.} accepted) and LINK (rel="next", same origin and
 * same path only, anything else a SecurityException), against a JDK
 * HttpServer "bank API" of 25 customers. The pagination is connector-core's,
 * the same as the server's REST connector.
 */
class RestConnectorPaginationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOTAL = 25;
    private static HttpServer bank;
    private static final List<String> requests = new CopyOnWriteArrayList<>();

    private final RestConnector connector = new RestConnector();

    @TempDir
    Path dir;

    @BeforeAll
    static void startBank() throws IOException {
        bank = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bank.createContext("/api/customers", exchange -> {
            Map<String, String> query = query(exchange);
            int page = Integer.parseInt(query.getOrDefault("page", "1"));
            int size = Integer.parseInt(query.getOrDefault("size", "7")); // the API's own default
            send(exchange, Map.of("items", slice((page - 1) * size, size), "page", page), null);
        });
        bank.createContext("/api/stream", exchange -> {
            Map<String, String> query = query(exchange);
            int from = Integer.parseInt(query.getOrDefault("after", "0"));
            int size = Integer.parseInt(query.getOrDefault("limit", "10"));
            int to = Math.min(TOTAL, from + size);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("next", to < TOTAL ? String.valueOf(to) : null);
            send(exchange, Map.of("data", slice(from, size), "meta", meta), null);
        });
        bank.createContext("/api/stuck", exchange -> {
            query(exchange);
            send(exchange, Map.of("data", slice(0, 3), "meta", Map.of("next", "same")), null);
        });
        bank.createContext("/api/linked", exchange -> {
            int page = Integer.parseInt(query(exchange).getOrDefault("p", "1"));
            String link = page < 3 ? "</api/linked?p=" + (page + 1) + ">; rel=\"next\"" : null;
            send(exchange, slice((page - 1) * 10, 10), link);
        });
        bank.createContext("/api/escape", exchange -> {
            query(exchange);
            send(exchange, slice(0, 10), "<http://evil.example/api/escape?p=2>; rel=\"next\"");
        });
        bank.createContext("/api/sideways", exchange -> {
            query(exchange);
            send(exchange, slice(0, 10), "</api/admin/users?p=2>; rel=\"next\"");
        });
        bank.start();
    }

    @AfterAll
    static void stopBank() {
        bank.stop(0);
    }

    @BeforeEach
    void clear() {
        requests.clear();
    }

    @Test
    void pagePaginationWalksPagesUntilAShortOne() throws Exception {
        List<List<Map<String, Object>>> pages = readAll(task("/api/customers", "items",
                Map.of("type", "PAGE", "pageParam", "page", "sizeParam", "size", "pageSize", 10)));
        assertThat(pages).extracting(List::size).containsExactly(10, 10, 5);
        assertThat(ids(pages)).hasSize(TOTAL).doesNotHaveDuplicates();
        assertThat(requests).containsExactly("page=1&size=10", "page=2&size=10", "page=3&size=10");
    }

    @Test
    void pagePaginationWithoutASizeStopsAtTheFirstEmptyPage() throws Exception {
        List<List<Map<String, Object>>> pages = readAll(task("/api/customers", "items",
                Map.of("type", "PAGE", "startPage", "1")));
        assertThat(ids(pages)).hasSize(TOTAL);
        assertThat(pages.get(pages.size() - 1)).isEmpty(); // 7+7+7+4, then an empty page 5
        assertThat(requests).hasSize(5);
    }

    @Test
    void maxPagesCapsARun() throws Exception {
        List<List<Map<String, Object>>> pages = readAll(task("/api/customers", "items",
                Map.of("type", "PAGE", "sizeParam", "size", "pageSize", 5, "maxPages", 2)));
        assertThat(pages).extracting(List::size).containsExactly(5, 5);
    }

    @Test
    void cursorPaginationFollowsTheNextCursorInTheAnswer() throws Exception {
        List<List<Map<String, Object>>> pages = readAll(task("/api/stream", "data",
                Map.of("type", "CURSOR", "cursorParam", "after", "cursorPath", "$.meta.next",
                        "sizeParam", "limit", "pageSize", 10)));
        assertThat(pages).extracting(List::size).containsExactly(10, 10, 5);
        assertThat(ids(pages)).hasSize(TOTAL).doesNotHaveDuplicates();
        assertThat(requests).containsExactly("limit=10", "after=10&limit=10", "after=20&limit=10");
    }

    @Test
    void aRepeatedCursorEndsTheRunInsteadOfLooping() throws Exception {
        List<List<Map<String, Object>>> pages = readAll(task("/api/stuck", "data",
                Map.of("type", "CURSOR", "cursorParam", "c", "cursorPath", "meta.next")));
        assertThat(pages).extracting(List::size).containsExactly(3, 3);
        assertThat(requests).containsExactly("", "c=same");
    }

    @Test
    void linkPaginationFollowsRelNextOnTheSamePath() throws Exception {
        List<List<Map<String, Object>>> pages = readAll(task("/api/linked", null, Map.of("type", "LINK")));
        assertThat(pages).extracting(List::size).containsExactly(10, 10, 5);
        assertThat(requests).containsExactly("", "p=2", "p=3");
    }

    @Test
    void aNextLinkToAnotherHostOrPathIsRefused() {
        for (String path : List.of("/api/escape", "/api/sideways")) {
            assertThatThrownBy(() -> readAll(task(path, null, Map.of("type", "LINK"))))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("next-page link");
        }
        URI base = URI.create("http://127.0.0.1:8080");
        assertThat(RestApiConnector.nextLink(base, "/api/x", "http://127.0.0.1:8080/api/x?p=2"))
                .hasToString("http://127.0.0.1:8080/api/x?p=2");
        assertThatThrownBy(() -> RestApiConnector.nextLink(base, "/api/x", "http://127.0.0.1:9090/api/x?p=2"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> RestApiConnector.nextLink(base, "/api/x", "https://127.0.0.1:8080/api/x"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> RestApiConnector.nextLink(base, "/api/x", "http://127.0.0.1:8080/api/y?p=2"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void aBadPaginationSpecIsRefusedBeforeAnySecretIsRead() throws Exception {
        Path allow = dir.resolve("allow.yml");
        Files.writeString(allow, "destinations:\n  - host: 127.0.0.1\n    ports: [" + port() + "]\n");
        AllowList allowList = new AllowList(allow);
        for (Map<String, Object> pagination : List.<Map<String, Object>>of(
                Map.of("type", "CURSOR", "cursorParam", "after"),
                Map.of("type", "PAGE", "pageSize", 0),
                Map.of("type", "PAGE", "pageParam", "page&admin=1"),
                Map.of("type", "OFFSET"),
                Map.of("type", "PAGE", "sql", "drop"))) {
            Connector.TaskContext context = task("/api/customers", "items", pagination);
            assertThatThrownBy(() -> connector.authorize(context, allowList))
                    .as(pagination.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        connector.authorize(task("/api/customers", "items", Map.of("type", "PAGE")), allowList);
    }

    @Test
    void theExecutorPostsEveryPageWithItsCursorAndLastFlag() throws Exception {
        Path allow = dir.resolve("allow.yml");
        Files.writeString(allow, "destinations:\n  - host: 127.0.0.1\n    ports: [" + port() + "]\n"
                + "rest:\n  pathPrefixes: [/api/customers]\n");
        try (FakeCloud cloud = new FakeCloud()) {
            CloudClient client = new CloudClient(cloud.url(), true, MAPPER);
            client.useCredentials(new CredentialStore.Credentials(5, FakeCloud.CLIENT_ID,
                    FakeCloud.CLIENT_SECRET, null));
            TaskExecutor executor = new TaskExecutor(List.of(connector), new AllowList(allow),
                    new LocalSecrets(dir, name -> null, MAPPER), client);
            executor.execute(task("/api/customers", "items",
                    Map.of("type", "PAGE", "sizeParam", "size", "pageSize", 10)).task());

            assertThat(cloud.records).extracting(r -> (Object) ((Map<?, ?>) r.get("body")).get("nextCursor"))
                    .containsExactly("2", "3", null);
            assertThat(cloud.records).extracting(r -> (Object) ((Map<?, ?>) r.get("body")).get("last"))
                    .containsExactly(false, false, true);
            assertThat(cloud.results).singleElement().satisfies(result -> assertThat(result)
                    .containsEntry("success", true).containsEntry("recordsIn", TOTAL));
        }
    }

    @Test
    void pagingHelpers() throws Exception {
        // A page never asks for more than one records POST takes.
        readAll(task("/api/customers", "items", Map.of("type", "PAGE", "sizeParam", "size", "pageSize", 5000)));
        assertThat(requests).containsExactly("page=1&size=" + RestConnector.MAX_PAGE_SIZE);
        // No pagination: one request.
        requests.clear();
        assertThat(readAll(task("/api/customers", "items", Map.of()))).hasSize(1);
        assertThat(requests).containsExactly("");
        assertThat(JsonFlattener.atPath(MAPPER.readTree("{\"a\":{\"b\":1}}"), "$.a.b").asInt()).isEqualTo(1);
        assertThat(JsonFlattener.atPath(MAPPER.readTree("{\"a\":{\"b\":1}}"), "a.c")).isNull();
    }

    // ---------------------------------------------------------------- helpers

    /** Reads like TaskExecutor: page after page until the connector reports no more. */
    private List<List<Map<String, Object>>> readAll(Connector.TaskContext context) throws Exception {
        List<List<Map<String, Object>>> pages = new ArrayList<>();
        String cursor = context.task().cursor();
        for (int i = 0; i < 20; i++) {
            Connector.ReadPage page = connector.read(context, cursor, 10_000);
            pages.add(page.records());
            if (!page.hasMore()) {
                return pages;
            }
            assertThat(page.nextCursor()).isNotNull();
            cursor = page.nextCursor();
        }
        throw new AssertionError("pagination did not end");
    }

    private static Connector.TaskContext task(String path, String recordsPath, Map<String, Object> pagination) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("path", path);
        if (recordsPath != null) {
            spec.put("recordsPath", recordsPath);
        }
        spec.put("pagination", pagination);
        return new Connector.TaskContext(new Protocol.AgentTask(1, 2, "REST_API", "READ",
                Map.of("baseUrl", "http://127.0.0.1:" + port(), "authType", "NONE"), null, spec,
                null, 1000), Secret.EMPTY);
    }

    private static int port() {
        return bank.getAddress().getPort();
    }

    private static List<Object> ids(List<List<Map<String, Object>>> pages) {
        return pages.stream().flatMap(List::stream).map(r -> r.get("id")).toList();
    }

    private static List<Map<String, Object>> slice(int from, int size) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = from; i < Math.min(TOTAL, from + size); i++) {
            items.add(Map.of("id", i + 1, "name", "customer-" + (i + 1)));
        }
        return items;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        requests.add(raw == null ? "" : raw);
        Map<String, String> values = new LinkedHashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                values.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        return values;
    }

    private static void send(HttpExchange exchange, Object body, String link) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (link != null) {
            exchange.getResponseHeaders().add("Link", link);
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
