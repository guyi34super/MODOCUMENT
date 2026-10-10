package com.modocument.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A JDK HttpServer fake of MoDocument's side of the agent protocol
 * (contracts §7.4) plus the client-credentials token endpoint: one-time
 * enrolment, token, long-poll tasks (from a queue), records, payload,
 * result, heartbeat. Every authenticated call must carry the issued token.
 */
public final class FakeCloud implements AutoCloseable {

    public static final String TOKEN = "mda1.5." + "A".repeat(43);
    public static final String CLIENT_ID = "agt_5";
    public static final String CLIENT_SECRET = "cs-very-secret-value";
    public static final String ACCESS_TOKEN = "at-fake-access-token";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RUN = Pattern.compile("/api/agent/v1/runs/(\\d+)/(records|payload|result)");

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Map<String, Object>> tasks = new ConcurrentLinkedQueue<>();
    public final List<Map<String, Object>> enrolments = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> results = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> heartbeats = new CopyOnWriteArrayList<>();
    public final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile boolean enrolled;
    private volatile Map<String, Object> payload = Map.of();

    public FakeCloud() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void queue(Map<String, Object> task) {
        tasks.add(task);
    }

    public void payload(Map<String, Object> batch) {
        this.payload = batch;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        paths.add(exchange.getRequestMethod() + " " + path);
        try {
            if (path.equals("/api/agent/v1/enroll")) {
                Map<String, Object> body = body(exchange);
                enrolments.add(body);
                if (!TOKEN.equals(body.get("token")) || enrolled) {
                    send(exchange, 401, Map.of("error", "invalid", "code", "AGENT_ENROLMENT_INVALID"));
                    return;
                }
                enrolled = true;
                send(exchange, 200, Map.of("agentId", 5, "clientId", CLIENT_ID, "secret", CLIENT_SECRET,
                        "tokenUrl", url() + "/api/integration/oauth/token"));
                return;
            }
            if (path.equals("/api/integration/oauth/token")) {
                String expected = "Basic " + Base64.getEncoder().encodeToString(
                        (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    send(exchange, 401, Map.of("error", "bad client"));
                    return;
                }
                send(exchange, 200, Map.of("access_token", ACCESS_TOKEN, "token_type", "Bearer",
                        "expires_in", 600, "scope", "AGENT_CONNECT"));
                return;
            }
            if (!("Bearer " + ACCESS_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            if (path.equals("/api/agent/v1/tasks")) {
                List<Map<String, Object>> batch = new ArrayList<>();
                Map<String, Object> next;
                while ((next = tasks.poll()) != null) {
                    batch.add(next);
                }
                send(exchange, 200, Map.of("tasks", batch));
                return;
            }
            if (path.equals("/api/agent/v1/heartbeat")) {
                heartbeats.add(body(exchange));
                send(exchange, 200, Map.of("latestVersion", "9.9.9", "pollSeconds", 30));
                return;
            }
            Matcher run = RUN.matcher(path);
            if (run.matches()) {
                switch (run.group(2)) {
                    case "records" -> {
                        Map<String, Object> body = body(exchange);
                        records.add(Map.of("runId", Long.parseLong(run.group(1)), "body", body));
                        send(exchange, 204, null);
                    }
                    case "result" -> {
                        Map<String, Object> body = new java.util.LinkedHashMap<>(body(exchange));
                        body.put("runId", Long.parseLong(run.group(1)));
                        results.add(body);
                        send(exchange, 204, null);
                    }
                    default -> send(exchange, 200, payload);
                }
                return;
            }
            send(exchange, 404, Map.of("error", "not found"));
        } catch (RuntimeException e) {
            send(exchange, 500, Map.of("error", e.getClass().getSimpleName()));
        }
    }

    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return MAPPER.readValue(bytes, new TypeReference<>() { });
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
