package com.modocument.infrastructure.integration.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.OutboundAuthType;
import com.modocument.infrastructure.extraction.connector.JsonFlattener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST_API connector (ws25). Inbound: GET pull, one page per
 * {@link #read} call, with the extraction connector's pagination modes
 * (PAGE, CURSOR, LINK header; records at a dot path, flattened by
 * {@link JsonFlattener}). Outbound: one POST/PUT per batch — a JSON array of
 * records, or the raw file for a file-shaped batch — carrying the batch's
 * {@code Idempotency-Key}. Auth, egress checks, timeouts and the no-redirect
 * rule live in {@link OutboundHttp}.
 *
 * <p>Source spec: {@code {path, query:{}, pagination:{type:"NONE|PAGE|CURSOR|LINK",
 * pageParam, sizeParam, pageSize, startPage, cursorParam, cursorPath, maxPages},
 * recordsPath}}. Target spec: {@code {method:"POST|PUT", path, batchSize}}.
 */
@Component
public class RestApiIntegrationConnector implements IntegrationConnector {

    static final Set<String> SOURCE_KEYS = Set.of("path", "query", "pagination", "recordsPath");
    static final Set<String> TARGET_KEYS = Set.of("method", "path", "batchSize");
    static final Set<String> PAGINATION_KEYS = Set.of("type", "pageParam", "sizeParam",
            "pageSize", "startPage", "cursorParam", "cursorPath", "maxPages");

    private static final Pattern PARAM = Pattern.compile("^[A-Za-z0-9_.\\[\\]-]{1,64}$");
    private static final Pattern DOT_PATH = Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$");
    private static final Pattern LINK_NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum Pagination { NONE, PAGE, CURSOR, LINK }

    private final OutboundHttp http;

    public RestApiIntegrationConnector(OutboundHttp http) {
        this.http = http;
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.REST_API;
    }

    @Override
    public void validate(ConnectionContext connection, Map<String, Object> spec, boolean outbound) {
        http.baseUri(connection);
        http.validateAuth(connection, authType(connection));
        if (outbound) {
            Specs.allowOnly(spec, "target spec", TARGET_KEYS);
            String method = Specs.string(spec, "method");
            if (method != null && !method.equalsIgnoreCase("POST") && !method.equalsIgnoreCase("PUT")) {
                throw new IllegalArgumentException("'method' must be POST or PUT.");
            }
            Specs.relativePath(Specs.string(spec, "path"), "path", true);
            Specs.integer(spec, "batchSize", 1, 1000);
            return;
        }
        Specs.allowOnly(spec, "source spec", SOURCE_KEYS);
        Specs.relativePath(Specs.string(spec, "path"), "path", true);
        query(spec);
        String recordsPath = Specs.string(spec, "recordsPath");
        if (recordsPath != null && !DOT_PATH.matcher(recordsPath).matches()) {
            throw new IllegalArgumentException("'recordsPath' must be a dot path such as data.items.");
        }
        Map<String, Object> pagination = Specs.map(spec, "pagination");
        Specs.allowOnly(pagination, "pagination", PAGINATION_KEYS);
        Pagination type = Specs.enumValue(pagination, "type", Pagination.class, Pagination.NONE);
        for (String key : List.of("pageParam", "sizeParam", "cursorParam")) {
            String value = Specs.string(pagination, key);
            if (value != null && !PARAM.matcher(value).matches()) {
                throw new IllegalArgumentException("'" + key + "' is not a valid parameter name.");
            }
        }
        String cursorPath = Specs.string(pagination, "cursorPath");
        if (cursorPath != null && !DOT_PATH.matcher(cursorPath).matches()) {
            throw new IllegalArgumentException("'cursorPath' must be a dot path.");
        }
        Specs.integer(pagination, "pageSize", 1, 10_000);
        Specs.integer(pagination, "startPage", 0, 1_000_000);
        Specs.integer(pagination, "maxPages", 1, 100_000);
        if (type == Pagination.CURSOR && (Specs.string(pagination, "cursorParam") == null
                || cursorPath == null)) {
            throw new IllegalArgumentException("CURSOR pagination needs 'cursorParam' and 'cursorPath'.");
        }
    }

    @Override
    public ConnectionTestResult test(ConnectionContext connection) {
        long started = System.nanoTime();
        try {
            URI base = http.baseUri(connection);
            OutboundAuthType auth = authType(connection);
            http.validateAuth(connection, auth);
            String testPath = connection.config("testPath");
            URI uri = OutboundHttp.resolve(base, testPath == null ? "" : testPath, Map.of());
            HttpClient client = http.client(connection, auth);
            HttpRequest request = http.request(connection, auth, client, uri)
                    .header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> response = http.send(client, request);
            long millis = elapsed(started);
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                return ConnectionTestResult.failed("Authentication was refused (HTTP " + status + ").",
                        millis);
            }
            if (status >= 500 || (status >= 300 && status < 400)) {
                return ConnectionTestResult.failed("The API answered HTTP " + status + ".", millis);
            }
            return ConnectionTestResult.ok("Reached the API (HTTP " + status + ").", millis);
        } catch (Exception e) {
            return ConnectionTestResult.failed(ConnectorErrors.safeMessage(e), elapsed(started));
        }
    }

    @Override
    public ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec,
                          String cursor, int maxRecords) throws Exception {
        validate(connection, sourceSpec, false);
        URI base = http.baseUri(connection);
        Map<String, Object> pagination = Specs.map(sourceSpec, "pagination");
        Pagination mode = Specs.enumValue(pagination, "type", Pagination.class, Pagination.NONE);
        int limit = Math.max(1, maxRecords);
        int pageSize = Math.min(limit, valueOr(Specs.integer(pagination, "pageSize", 1, 10_000), 100));
        Map<String, String> query = query(sourceSpec);
        URI uri;
        int page = 0;
        if (mode == Pagination.LINK && cursor != null) {
            uri = URI.create(cursor);
            if (!OutboundHttp.sameOrigin(base, uri)) {
                throw new SecurityException("The next-page link points outside the connection's "
                        + "base URL; refused.");
            }
        } else {
            switch (mode) {
                case PAGE -> {
                    page = cursor == null
                            ? valueOr(Specs.integer(pagination, "startPage", 0, 1_000_000), 1)
                            : Integer.parseInt(cursor);
                    query.put(valueOr(Specs.string(pagination, "pageParam"), "page"),
                            String.valueOf(page));
                    sizeParam(pagination, query, pageSize);
                }
                case CURSOR -> {
                    if (cursor != null) {
                        query.put(Specs.string(pagination, "cursorParam"), cursor);
                    }
                    sizeParam(pagination, query, pageSize);
                }
                case LINK -> sizeParam(pagination, query, pageSize);
                case NONE -> { }
            }
            uri = OutboundHttp.resolve(base, Specs.string(sourceSpec, "path"), query);
        }

        OutboundAuthType auth = authType(connection);
        HttpClient client = http.client(connection, auth);
        HttpRequest request = http.request(connection, auth, client, uri)
                .header("Accept", "application/json").GET().build();
        HttpResponse<byte[]> response = http.send(client, request);
        requireSuccess(connection, auth, response);

        JsonNode root = MAPPER.readTree(response.body().length == 0 ? "{}".getBytes() : response.body());
        List<Map<String, String>> flat = JsonFlattener.records(root, Specs.string(sourceSpec, "recordsPath"));
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, String> row : flat) {
            if (records.size() >= limit) {
                break;
            }
            records.add(new LinkedHashMap<>(row));
        }

        String next = null;
        boolean more = false;
        Integer maxPages = Specs.integer(pagination, "maxPages", 1, 100_000);
        switch (mode) {
            case PAGE -> {
                int start = valueOr(Specs.integer(pagination, "startPage", 0, 1_000_000), 1);
                boolean underCap = maxPages == null || (page - start + 1) < maxPages;
                more = flat.size() >= pageSize && underCap;
                next = more ? String.valueOf(page + 1) : null;
            }
            case CURSOR -> {
                JsonNode token = JsonFlattener.atPath(root, Specs.string(pagination, "cursorPath"));
                if (token != null && !token.isNull() && !token.asText().isEmpty() && !flat.isEmpty()) {
                    next = token.asText();
                    more = !next.equals(cursor);
                }
            }
            case LINK -> {
                String link = response.headers().firstValue("Link").orElse(null);
                if (link != null && !flat.isEmpty()) {
                    Matcher matcher = LINK_NEXT.matcher(link);
                    if (matcher.find()) {
                        URI candidate = uri.resolve(matcher.group(1));
                        if (!OutboundHttp.sameOrigin(base, candidate)) {
                            throw new SecurityException("The next-page link points outside the "
                                    + "connection's base URL; refused.");
                        }
                        next = candidate.toString();
                        more = true;
                    }
                }
            }
            case NONE -> { }
        }
        return new ReadBatch(records, List.of(), more ? next : null, more);
    }

    @Override
    public WriteResult write(ConnectionContext connection, Map<String, Object> targetSpec,
                             OutboundBatch batch) throws Exception {
        validate(connection, targetSpec, true);
        URI uri = OutboundHttp.resolve(http.baseUri(connection), Specs.string(targetSpec, "path"),
                Map.of());
        String method = valueOr(Specs.string(targetSpec, "method"), "POST").toUpperCase(Locale.ROOT);
        OutboundAuthType auth = authType(connection);
        HttpClient client = http.client(connection, auth);
        HttpRequest.Builder builder = http.request(connection, auth, client, uri);
        if (batch.idempotencyKey() != null) {
            builder.header("Idempotency-Key", batch.idempotencyKey());
        }
        HttpRequest.BodyPublisher body;
        int count;
        if (batch.fileContent() != null) {
            builder.header("Content-Type", batch.contentType() == null
                    ? "application/octet-stream" : batch.contentType());
            if (batch.fileName() != null) {
                builder.header("X-MoDocument-File-Name", batch.fileName().replaceAll("[^A-Za-z0-9._-]", "_"));
            }
            body = HttpRequest.BodyPublishers.ofByteArray(batch.fileContent());
            count = Math.max(1, batch.records().size());
        } else {
            builder.header("Content-Type", "application/json");
            body = HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(batch.records()));
            count = batch.records().size();
        }
        HttpRequest request = builder.method(method, body).build();
        HttpResponse<byte[]> response = http.send(client, request);
        requireSuccess(connection, auth, response);
        return new WriteResult(count, "HTTP " + response.statusCode());
    }

    private void requireSuccess(ConnectionContext connection, OutboundAuthType auth,
                                HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 401 && auth == OutboundAuthType.OAUTH2_CLIENT_CREDENTIALS) {
            http.evictToken(connection);
        }
        if (status >= 300 && status < 400) {
            throw new IllegalStateException("The API answered HTTP " + status
                    + " (a redirect); redirects are not followed.");
        }
        throw new IllegalStateException("The API answered HTTP " + status + ".");
    }

    private static Map<String, String> query(Map<String, Object> spec) {
        Map<String, Object> raw = Specs.map(spec, "query");
        Map<String, String> query = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!PARAM.matcher(entry.getKey()).matches()) {
                throw new IllegalArgumentException("Query parameter '" + Specs.safe(entry.getKey())
                        + "' is not a valid name.");
            }
            String value = Specs.string(raw, entry.getKey());
            if (value != null && value.length() > 500) {
                throw new IllegalArgumentException("Query parameter values are limited to 500 characters.");
            }
            query.put(entry.getKey(), value == null ? "" : value);
        }
        return query;
    }

    private static void sizeParam(Map<String, Object> pagination, Map<String, String> query,
                                  int pageSize) {
        String sizeParam = Specs.string(pagination, "sizeParam");
        if (sizeParam != null) {
            query.put(sizeParam, String.valueOf(pageSize));
        }
    }

    static OutboundAuthType authType(ConnectionContext connection) {
        OutboundAuthType type = OutboundHttp.authType(connection);
        if (type == OutboundAuthType.HMAC_SIGNATURE) {
            throw new IllegalArgumentException("HMAC_SIGNATURE is a WEBHOOK auth type.");
        }
        return type;
    }

    private static <T> T valueOr(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
