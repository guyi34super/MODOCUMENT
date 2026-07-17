package com.modocument.infrastructure.extraction.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.ConnectorResult;
import com.modocument.application.port.Connector;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.HostRateLimiter;
import com.modocument.application.port.JobSecrets;
import com.modocument.domain.document.ApiSourceConfig;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.document.RawRecord;
import com.modocument.domain.model.AuditAction;
import com.modocument.domain.model.ExtractionTaskType;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.UrlNormalizer;
import com.modocument.infrastructure.extraction.worker.ExtractionMetrics;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Official-API connector: one task fetches one page (natural retry/batch
 * granularity) and enqueues the next page until the pagination mode says
 * stop. Auth secrets come from an env-var reference or the per-run token —
 * never from persisted config. 401/403 are terminal: an API that denies
 * access is not retried, let alone worked around.
 */
@Component
public class RestApiConnector implements Connector {

    static final String PAYLOAD_URL = "url";
    static final String PAYLOAD_PAGE = "page";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LINK_NEXT =
            Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");

    private final ExtractionProperties properties;
    private final JobSecrets secrets;
    private final HostRateLimiter rateLimiter;
    private final ExtractionAuditLog audit;
    private final ExtractionMetrics metrics;
    private final HttpClient client;

    public RestApiConnector(ExtractionProperties properties, JobSecrets secrets,
                            HostRateLimiter rateLimiter, ExtractionAuditLog audit,
                            ExtractionMetrics metrics) {
        this.properties = properties;
        this.secrets = secrets;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.metrics = metrics;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(properties.getWeb().getRequestTimeoutSeconds()))
                .build();
    }

    @Override
    public SourceType type() {
        return SourceType.REST_API;
    }

    @Override
    public List<ExtractionTask> seedTasks(ExtractionJob job, ExtractionSource source,
                                          ConnectorState state) {
        ApiSourceConfig config = source.getApi();
        Map<String, String> params = new LinkedHashMap<>();
        switch (config.getPaginationMode()) {
            case PAGE -> {
                params.put(config.getPageParam(), "1");
                sizeParam(config, params);
            }
            case OFFSET -> {
                params.put(config.getPageParam(), "0");
                sizeParam(config, params);
            }
            case CURSOR, LINK_HEADER, NONE -> sizeParam(config, params);
        }
        if (job.isIncremental() && config.getIncrementalParam() != null
                && state != null && state.getLastCursor() != null) {
            params.put(config.getIncrementalParam(), state.getLastCursor());
        }
        String url = buildUrl(config, params);
        return List.of(newPageTask(job, source, url, 1));
    }

    private static void sizeParam(ApiSourceConfig config, Map<String, String> params) {
        if (config.getSizeParam() != null) {
            params.put(config.getSizeParam(), String.valueOf(config.getPageSize()));
        }
    }

    @Override
    public ConnectorResult execute(ExtractionTask task, ExtractionJob job,
                                   ExtractionSource source, ConnectorState state) throws Exception {
        ApiSourceConfig config = source.getApi();
        String url = task.getPayload().get(PAYLOAD_URL);
        int page = Integer.parseInt(task.getPayload().getOrDefault(PAYLOAD_PAGE, "1"));

        rateLimiter.acquire(UrlNormalizer.hostOf(url), properties.getWeb().getDefaultDelayMillis());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getWeb().getRequestTimeoutSeconds()))
                .header("User-Agent", properties.getWeb().getUserAgent())
                .header("Accept", "application/json")
                .GET();
        config.getHeaders().forEach(builder::header);
        applyAuth(builder, config, job.getId());

        HttpResponse<String> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        metrics.fetch();
        audit.add(ExtractionAuditEvent.of(job.getId(), source.getId(), AuditAction.FETCH, url,
                "HTTP " + response.statusCode(), "API page " + page));

        if (response.statusCode() == 401 || response.statusCode() == 403
                || response.statusCode() == 404) {
            // Denied or missing: terminal, never retried or circumvented.
            return ConnectorResult.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " calling " + url);
        }

        String body = response.body() == null ? "" : response.body();
        JsonNode root = MAPPER.readTree(body.isEmpty() ? "{}" : body);
        List<Map<String, String>> rows = JsonFlattener.records(root, config.getRecordsJsonPath());

        RawRecord raw = new RawRecord();
        raw.setJobId(job.getId());
        raw.setSourceId(source.getId());
        raw.setUri(url);
        raw.setContentType(response.headers().firstValue("Content-Type").orElse("application/json"));
        raw.setBody(body);
        raw.setContentHash(Hashing.sha256(body));
        raw.setHttpStatus(response.statusCode());
        raw.setFetchedAt(Instant.now());

        List<ExtractionTask> followUps = new ArrayList<>();
        Map<String, String> stateUpdates = new LinkedHashMap<>();
        nextPageUrl(config, url, page, rows.size(), root, response)
                .ifPresent(next -> followUps.add(newPageTask(job, source, next, page + 1)));
        if (config.getPaginationMode() == ApiSourceConfig.PaginationMode.CURSOR) {
            JsonNode cursor = JsonFlattener.atPath(root, config.getCursorJsonPath());
            if (cursor != null && !cursor.isNull() && !cursor.asText().isEmpty()) {
                stateUpdates.put("cursor", cursor.asText());
            }
        }

        return new ConnectorResult(
                List.of(new ConnectorResult.FetchedItem(raw, rows)), followUps, stateUpdates);
    }

    private Optional<String> nextPageUrl(ApiSourceConfig config, String url, int page,
                                         int recordCount, JsonNode root,
                                         HttpResponse<String> response) {
        int maxPages = config.getMaxPages() != null ? config.getMaxPages() : Integer.MAX_VALUE;
        if (page >= maxPages || recordCount == 0) {
            return Optional.empty();
        }
        switch (config.getPaginationMode()) {
            case PAGE -> {
                return Optional.of(replaceParam(url, config.getPageParam(),
                        String.valueOf(page + 1)));
            }
            case OFFSET -> {
                return Optional.of(replaceParam(url, config.getPageParam(),
                        String.valueOf((long) page * config.getPageSize())));
            }
            case CURSOR -> {
                JsonNode cursor = JsonFlattener.atPath(root, config.getCursorJsonPath());
                if (cursor == null || cursor.isNull() || cursor.asText().isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(replaceParam(url, config.getCursorParam(), cursor.asText()));
            }
            case LINK_HEADER -> {
                return response.headers().firstValue("Link").flatMap(link -> {
                    Matcher matcher = LINK_NEXT.matcher(link);
                    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
                });
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private void applyAuth(HttpRequest.Builder builder, ApiSourceConfig config, String jobId) {
        String secret = resolveSecret(config, jobId);
        switch (config.getAuthType()) {
            case BEARER -> {
                requireSecret(secret, config);
                builder.header("Authorization", "Bearer " + secret);
            }
            case BASIC -> {
                requireSecret(secret, config);
                builder.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            }
            case API_KEY_HEADER -> {
                requireSecret(secret, config);
                builder.header(config.getAuthHeaderName() == null
                        ? "X-Api-Key" : config.getAuthHeaderName(), secret);
            }
            case NONE -> {
                // Public API.
            }
        }
    }

    /** Per-run token first (never persisted), then the env-var reference. */
    private String resolveSecret(ApiSourceConfig config, String jobId) {
        String fromRun = secrets.get(jobId).map(map -> map.get("apiToken")).orElse(null);
        if (fromRun != null && !fromRun.isBlank()) {
            return fromRun;
        }
        if (config.getAuthEnvVar() != null && !config.getAuthEnvVar().isBlank()) {
            return System.getenv(config.getAuthEnvVar());
        }
        return null;
    }

    private static void requireSecret(String secret, ApiSourceConfig config) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("API auth secret is not available (env var '"
                    + config.getAuthEnvVar() + "' unset and no per-run token supplied).");
        }
    }

    private ExtractionTask newPageTask(ExtractionJob job, ExtractionSource source,
                                       String url, int page) {
        ExtractionTask task = new ExtractionTask();
        task.setJobId(job.getId());
        task.setSourceId(source.getId());
        task.setType(ExtractionTaskType.CALL_API);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_URL, url);
        payload.put(PAYLOAD_PAGE, String.valueOf(page));
        task.setPayload(payload);
        task.setDedupKey("api:" + url);
        task.setMaxAttempts(properties.getWorker().getMaxAttempts());
        return task;
    }

    static String buildUrl(ApiSourceConfig config, Map<String, String> params) {
        StringBuilder url = new StringBuilder(config.getBaseUrl());
        if (config.getPath() != null && !config.getPath().isEmpty()) {
            if (!config.getBaseUrl().endsWith("/") && !config.getPath().startsWith("/")) {
                url.append('/');
            }
            url.append(config.getPath());
        }
        char separator = url.indexOf("?") >= 0 ? '&' : '?';
        for (Map.Entry<String, String> param : params.entrySet()) {
            url.append(separator)
                    .append(encode(param.getKey())).append('=').append(encode(param.getValue()));
            separator = '&';
        }
        return url.toString();
    }

    static String replaceParam(String url, String param, String value) {
        String encodedParam = encode(param);
        Pattern pattern = Pattern.compile("([?&])" + Pattern.quote(encodedParam) + "=[^&]*");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.replaceFirst(matcher.group(1) + encodedParam + "=" + encode(value));
        }
        return url + (url.contains("?") ? "&" : "?") + encodedParam + "=" + encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
