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
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST_API connections over the JDK HttpClient. GET pull, paginated like the
 * server's REST connector (contracts §7.3 {@code sourceSpec.pagination}):
 * <ul>
 *   <li>{@code NONE}: one request;</li>
 *   <li>{@code PAGE}: {@code pageParam} (default {@code page}) counting up
 *       from {@code startPage} (default 1), {@code sizeParam} =
 *       {@code pageSize} (default 100, at most 1000 per request); stops at a
 *       short page (an empty one when no size is configured) or after
 *       {@code maxPages};</li>
 *   <li>{@code CURSOR}: {@code cursorParam} = the value at {@code cursorPath}
 *       (a dot path, optionally starting {@code $.}) of the previous answer;
 *       stops when that value is missing, empty or repeats;</li>
 *   <li>{@code LINK}: the {@code Link: <…>; rel="next"} header, followed only
 *       on the same origin AND the same path as the configured one (the path
 *       the allow-list approved; only the query may change).</li>
 * </ul>
 * The read cursor is the next page number, cursor value or link; the
 * TaskExecutor posts every page with it. Push: POST/PUT in batches with an
 * {@code Idempotency-Key}. Auth from
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
    private static final Pattern PARAM = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,64}");
    private static final Pattern DOT_PATH = Pattern.compile("(\\$\\.)?[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*");
    private static final Pattern LINK_NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");
    private static final Set<String> PAGINATION_KEYS = Set.of("type", "pageParam", "sizeParam",
            "pageSize", "startPage", "cursorParam", "cursorPath", "maxPages");
    /** The server keeps a run's cursor up to 1000 characters (agent protocol). */
    private static final int MAX_CURSOR = 1000;
    /** Records per request: the server accepts at most 1000 per records POST. */
    static final int MAX_PAGE_SIZE = 1000;

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
        if ("READ".equals(operation)) {
            Paging.of(task.spec("pagination")); // a bad spec fails before any secret is read
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
        Paging paging = Paging.of(task.spec("pagination"));
        URI base = base(task);
        String path = path(task);
        Map<String, String> query = query(task);
        URI uri;
        int page = 0;
        if (paging.type() == PagingType.LINK && cursor != null) {
            uri = nextLink(base, path, cursor);
        } else {
            switch (paging.type()) {
                case PAGE -> {
                    page = cursor == null ? paging.startPage() : pageNumber(cursor);
                    query.put(paging.pageParam(), String.valueOf(page));
                    paging.sizeTo(query);
                }
                case CURSOR -> {
                    if (cursor != null) {
                        query.put(paging.cursorParam(), cursor);
                    }
                    paging.sizeTo(query);
                }
                case LINK -> paging.sizeTo(query);
                case NONE -> { }
            }
            uri = resolve(task, path, query);
        }
        HttpResponse<InputStream> response = send(task, HttpRequest.newBuilder(uri)
                .header("Accept", "application/json").GET());
        byte[] body = readCapped(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("The API answered HTTP " + response.statusCode() + ".");
        }
        JsonNode root = mapper.readTree(body);
        String recordsPath = task.specText("recordsPath");
        JsonNode node = at(root, recordsPath);
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
        int received = node.size();

        String next = null;
        switch (paging.type()) {
            case PAGE -> {
                boolean full = paging.sizeKnown() ? received >= paging.pageSize() : received > 0;
                if (full && paging.underCap(page - paging.startPage() + 1)) {
                    next = String.valueOf(page + 1);
                }
            }
            case CURSOR -> {
                JsonNode token = at(root, paging.cursorPath());
                if (received > 0 && token != null && token.isValueNode() && !token.isNull()) {
                    String value = token.asText();
                    if (!value.isEmpty() && !value.equals(cursor) && value.length() <= MAX_CURSOR) {
                        next = value;
                    }
                }
            }
            case LINK -> {
                String link = response.headers().firstValue("Link").orElse(null);
                if (received > 0 && link != null) {
                    Matcher matcher = LINK_NEXT.matcher(link);
                    if (matcher.find()) {
                        URI candidate = nextLink(base, path, uri.resolve(matcher.group(1)).toString());
                        next = candidate.toString();
                    }
                }
            }
            case NONE -> { }
        }
        return new ReadPage(records, next, next != null, null);
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

    // ------------------------------------------------------------- pagination

    enum PagingType { NONE, PAGE, CURSOR, LINK }

    /** The validated {@code sourceSpec.pagination} (same keys and limits as the server's). */
    record Paging(PagingType type, String pageParam, String sizeParam, int pageSize,
                  boolean sizeKnown, int startPage, String cursorParam, String cursorPath,
                  Integer maxPages) {

        static Paging of(Object raw) {
            if (raw == null) {
                return new Paging(PagingType.NONE, "page", null, 100, false, 1, null, null, null);
            }
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("The REST pagination must be an object.");
            }
            for (Object key : map.keySet()) {
                if (!PAGINATION_KEYS.contains(String.valueOf(key))) {
                    throw new IllegalArgumentException("Unknown pagination setting '"
                            + abbreviate(String.valueOf(key)) + "'.");
                }
            }
            String typeName = text(map, "type");
            PagingType type;
            try {
                type = typeName == null ? PagingType.NONE
                        : PagingType.valueOf(typeName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Pagination type must be NONE, PAGE, CURSOR or LINK.");
            }
            String pageParam = param(map, "pageParam");
            String sizeParam = param(map, "sizeParam");
            String cursorParam = param(map, "cursorParam");
            String cursorPath = text(map, "cursorPath");
            if (cursorPath != null && !DOT_PATH.matcher(cursorPath).matches()) {
                throw new IllegalArgumentException("'cursorPath' must be a dot path such as meta.next.");
            }
            Integer pageSize = number(map, "pageSize", 1, 10_000);
            Integer startPage = number(map, "startPage", 0, 1_000_000);
            Integer maxPages = number(map, "maxPages", 1, 100_000);
            if (type == PagingType.CURSOR && (cursorParam == null || cursorPath == null)) {
                throw new IllegalArgumentException("CURSOR pagination needs 'cursorParam' and 'cursorPath'.");
            }
            return new Paging(type, pageParam == null ? "page" : pageParam, sizeParam,
                    pageSize == null ? 100 : Math.min(pageSize, MAX_PAGE_SIZE),
                    sizeParam != null || pageSize != null, startPage == null ? 1 : startPage,
                    cursorParam, cursorPath, maxPages);
        }

        void sizeTo(Map<String, String> query) {
            if (sizeParam != null) {
                query.put(sizeParam, String.valueOf(pageSize));
            }
        }

        boolean underCap(int pagesRead) {
            return maxPages == null || pagesRead < maxPages;
        }

        private static String text(Map<?, ?> map, String key) {
            Object value = map.get(key);
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value).strip();
            return text.isEmpty() ? null : text;
        }

        private static String param(Map<?, ?> map, String key) {
            String value = text(map, key);
            if (value != null && !PARAM.matcher(value).matches()) {
                throw new IllegalArgumentException("'" + key + "' is not a valid parameter name.");
            }
            return value;
        }

        private static Integer number(Map<?, ?> map, String key, int min, int max) {
            String value = text(map, key);
            if (value == null) {
                return null;
            }
            try {
                int number = Integer.parseInt(value);
                if (number < min || number > max) {
                    throw new NumberFormatException();
                }
                return number;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + key + "' must be a whole number from "
                        + min + " to " + max + ".");
            }
        }

        private static String abbreviate(String value) {
            return value.length() > 40 ? value.substring(0, 40) + "..." : value;
        }
    }

    private static int pageNumber(String cursor) {
        try {
            int page = Integer.parseInt(cursor.strip());
            if (page < 0) {
                throw new NumberFormatException();
            }
            return page;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The PAGE cursor is not a page number.");
        }
    }

    /**
     * A next-page link: absolute, on the base's scheme, host and port, with
     * exactly the configured path (the one the allow-list approved) — only
     * its query may differ.
     */
    static URI nextLink(URI base, String path, String raw) {
        URI candidate;
        try {
            candidate = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The next-page link is not a URL.");
        }
        if (!candidate.isAbsolute()
                || !base.getScheme().equalsIgnoreCase(candidate.getScheme())
                || candidate.getHost() == null || !base.getHost().equalsIgnoreCase(candidate.getHost())
                || port(base) != port(candidate)
                || candidate.getRawUserInfo() != null || candidate.getRawFragment() != null
                || !fullPath(base, path).equals(candidate.getRawPath())
                || raw.length() > MAX_CURSOR) {
            throw new IllegalArgumentException("The next-page link leaves the connection's host or "
                    + "the configured path; refused.");
        }
        return candidate;
    }

    /** The node at a dot path ({@code data.items} or {@code $.data.items}); the root for none. */
    static JsonNode at(JsonNode root, String dotPath) {
        if (dotPath == null || dotPath.isBlank()) {
            return root;
        }
        String path = dotPath.strip();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            if (node == null) {
                return null;
            }
            node = node.get(part);
        }
        return node;
    }
}
