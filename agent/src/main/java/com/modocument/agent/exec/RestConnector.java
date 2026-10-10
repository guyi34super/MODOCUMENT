package com.modocument.agent.exec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.cloud.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * REST_API connections over the JDK HttpClient: GET pull (pagination NONE)
 * and POST/PUT push in batches with an {@code Idempotency-Key}. Auth from
 * the local secret: NONE, BASIC ({@code username} config + {@code password}),
 * BEARER ({@code token}) or API_KEY header ({@code apiKeyHeader} config +
 * {@code apiKey}). Redirects are never followed (a redirect could leave the
 * allow-listed host with the credentials); responses are capped at 20 MB;
 * connect and request timeouts on every call. Paths are relative to the
 * connection's {@code baseUrl}: absolute, no {@code ..}, no scheme.
 */
public class RestConnector implements Connector {

    private static final long MAX_RESPONSE_BYTES = 20L * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper mapper;
    private final HttpClient http;

    public RestConnector(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "REST_API";
    }

    @Override
    public void authorize(TaskContext task, AllowList allowList) {
        URI base = base(task);
        allowList.checkDestination(base.getHost(), port(base));
        String operation = task.task().operation();
        if ("READ".equals(operation) || "WRITE".equals(operation)) {
            allowList.checkRestPath(fullPath(base, path(task)));
        }
    }

    @Override
    public void test(TaskContext task) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = send(task, HttpRequest.newBuilder(base(task)).GET());
        try (InputStream body = response.body()) {
            body.readNBytes(1024);
        }
        if (response.statusCode() >= 500 || response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("The API answered HTTP " + response.statusCode() + ".");
        }
    }

    @Override
    public ReadPage read(TaskContext task, String cursor, int max) throws IOException, InterruptedException {
        Object pagination = task.spec("pagination");
        if (pagination instanceof Map<?, ?> map && map.get("type") != null
                && !"NONE".equalsIgnoreCase(String.valueOf(map.get("type")))) {
            throw new IllegalArgumentException("This agent version reads REST sources without "
                    + "pagination only (pagination type NONE).");
        }
        URI uri = resolve(task, path(task), query(task));
        HttpResponse<InputStream> response = send(task, HttpRequest.newBuilder(uri)
                .header("Accept", "application/json").GET());
        byte[] body = readCapped(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("The API answered HTTP " + response.statusCode() + ".");
        }
        JsonNode node = mapper.readTree(body);
        String recordsPath = task.specText("recordsPath");
        if (recordsPath != null && !recordsPath.isEmpty()) {
            for (String part : recordsPath.split("\\.")) {
                node = node == null ? null : node.get(part);
            }
        }
        if (node == null || !node.isArray()) {
            throw new IOException("The API response has no record array at '"
                    + (recordsPath == null ? "" : recordsPath) + "'.");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (JsonNode item : node) {
            if (records.size() >= Math.max(1, max)) {
                break;
            }
            if (item.isObject()) {
                records.add(mapper.convertValue(item, MAP));
            }
        }
        return new ReadPage(records, cursor, false, null);
    }

    @Override
    public WriteOutcome write(TaskContext task, Protocol.OutboundBatch batch)
            throws IOException, InterruptedException {
        String method = task.specText("method") == null ? "POST" : task.specText("method").toUpperCase(Locale.ROOT);
        if (!"POST".equals(method) && !"PUT".equals(method)) {
            throw new IllegalArgumentException("REST writes use POST or PUT.");
        }
        int batchSize = 100;
        if (task.specText("batchSize") != null) {
            batchSize = Math.max(1, Math.min(1000, Integer.parseInt(task.specText("batchSize"))));
        }
        URI uri = resolve(task, path(task), Map.of());
        List<Map<String, Object>> records = batch.records();
        int written = 0;
        int lastStatus = 0;
        for (int from = 0, chunk = 0; from < Math.max(1, records.size()); from += batchSize, chunk++) {
            List<Map<String, Object>> slice = records.subList(from, Math.min(records.size(), from + batchSize));
            String key = batch.idempotencyKey() == null ? null
                    : batch.idempotencyKey() + (records.size() > batchSize ? "-" + chunk : "");
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(slice)));
            if (key != null && key.matches("[A-Za-z0-9._:-]{1,200}")) {
                request.header("Idempotency-Key", key);
            }
            HttpResponse<InputStream> response = send(task, request);
            readCapped(response);
            lastStatus = response.statusCode();
            if (lastStatus < 200 || lastStatus >= 300) {
                throw new IOException("The API answered HTTP " + lastStatus + " after "
                        + written + " record(s).");
            }
            written += slice.size();
            if (records.isEmpty()) {
                break;
            }
        }
        return new WriteOutcome(written, "HTTP " + lastStatus);
    }

    // ---------------------------------------------------------------- helpers

    private HttpResponse<InputStream> send(TaskContext task, HttpRequest.Builder request)
            throws IOException, InterruptedException {
        request.timeout(REQUEST_TIMEOUT);
        String auth = task.config("authType") == null ? "NONE" : task.config("authType").toUpperCase(Locale.ROOT);
        switch (auth) {
            case "NONE" -> { }
            case "BASIC" -> request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (task.requireConfig("username") + ":" + required(task, "password"))
                            .getBytes(StandardCharsets.UTF_8)));
            case "BEARER" -> request.header("Authorization", "Bearer " + required(task, "token"));
            case "API_KEY", "API_KEY_HEADER" -> {
                String header = task.config("apiKeyHeader") == null ? "X-API-Key" : task.config("apiKeyHeader");
                if (!HEADER.matcher(header).matches()) {
                    throw new IllegalArgumentException("The API key header name is not a header name.");
                }
                request.header(header, required(task, "apiKey"));
            }
            default -> throw new IllegalArgumentException("Auth type " + auth
                    + " is not supported by this agent version.");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String required(TaskContext task, String key) {
        String value = task.secret().get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("The local secret has no '" + key + "'.");
        }
        return value;
    }

    private static byte[] readCapped(HttpResponse<InputStream> response) throws IOException {
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes((int) MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("The API response exceeds 20 MB.");
            }
            return bytes;
        }
    }

    static URI base(TaskContext task) {
        String raw = task.requireConfig("baseUrl");
        URI uri;
        try {
            uri = URI.create(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The connection baseUrl is not a URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("The connection baseUrl must be http(s)://host[:port][/path].");
        }
        return uri;
    }

    private static int port(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String path(TaskContext task) {
        String path = task.specText("path");
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (!PATH.matcher(path).matches() || path.contains("..") || path.contains("//")) {
            throw new IllegalArgumentException("The REST path must be an absolute path without '..'.");
        }
        return path;
    }

    private static String fullPath(URI base, String path) {
        String prefix = base.getRawPath() == null ? "" : base.getRawPath();
        return prefix + path;
    }

    private static Map<String, String> query(TaskContext task) {
        Object query = task.spec("query");
        Map<String, String> values = new TreeMap<>();
        if (query instanceof Map<?, ?> map) {
            map.forEach((k, v) -> values.put(String.valueOf(k), String.valueOf(v)));
        }
        return values;
    }

    static URI resolve(TaskContext task, String path, Map<String, String> query) {
        URI base = base(task);
        StringBuilder uri = new StringBuilder(base.toString()).append(path);
        if (!query.isEmpty()) {
            uri.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    uri.append('&');
                }
                first = false;
                uri.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        URI resolved = URI.create(uri.toString());
        if (!base.getHost().equalsIgnoreCase(resolved.getHost()) || port(base) != port(resolved)) {
            throw new IllegalArgumentException("The REST path leaves the connection's host.");
        }
        return resolved;
    }
}
