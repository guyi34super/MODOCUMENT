package com.modocument.infrastructure.extraction.connector;

import com.modocument.application.dto.ConnectorResult;
import com.modocument.application.port.Connector;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.document.RawRecord;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.model.ExtractionTaskType;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import com.modocument.infrastructure.extraction.Hashing;
import com.modocument.infrastructure.extraction.web.CompliantWebAccess;
import com.modocument.infrastructure.extraction.web.HtmlContentExtractor;
import com.modocument.infrastructure.extraction.web.UrlNormalizer;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compliant website crawler: seeds from the configured URLs, fetches through
 * CompliantWebAccess (robots + allow-list + rate limits + audit), extracts
 * tables/text with jsoup, and follows same-allow-listed-domain links up to
 * the configured depth. Page-count caps are enforced by the task executor
 * when follow-ups are enqueued.
 */
@Component
public class WebConnector implements Connector {

    static final String PAYLOAD_URL = "url";
    static final String PAYLOAD_DEPTH = "depth";

    private final CompliantWebAccess web;
    private final ExtractionProperties properties;

    public WebConnector(CompliantWebAccess web, ExtractionProperties properties) {
        this.web = web;
        this.properties = properties;
    }

    @Override
    public SourceType type() {
        return SourceType.WEBSITE;
    }

    @Override
    public List<ExtractionTask> seedTasks(ExtractionJob job, ExtractionSource source,
                                          ConnectorState state) {
        Set<String> seeds = new LinkedHashSet<>();
        for (String url : source.getWeb().getSeedUrls()) {
            String normalized = UrlNormalizer.normalize(url);
            if (normalized != null) {
                seeds.add(normalized);
            }
        }
        List<ExtractionTask> tasks = new ArrayList<>();
        for (String url : seeds) {
            tasks.add(newFetchTask(job, source, url, 0));
        }
        return tasks;
    }

    @Override
    public ConnectorResult execute(ExtractionTask task, ExtractionJob job,
                                   ExtractionSource source, ConnectorState state) throws Exception {
        WebSourceConfig config = source.getWeb();
        String url = task.getPayload().get(PAYLOAD_URL);
        int depth = Integer.parseInt(task.getPayload().getOrDefault(PAYLOAD_DEPTH, "0"));

        CompliantWebAccess.Access access = web.fetch(job.getId(), source.getId(), config, url, state);
        switch (access.outcome()) {
            case BLOCKED_DOMAIN, BLOCKED_ROBOTS, NOT_MODIFIED, SKIPPED_HTTP -> {
                return ConnectorResult.empty();
            }
            case RETRYABLE_HTTP -> throw new IllegalStateException(
                    "HTTP " + access.result().status() + " fetching " + url);
            case OK -> {
                // fall through below
            }
        }

        RawRecord raw = new RawRecord();
        raw.setJobId(job.getId());
        raw.setSourceId(source.getId());
        raw.setUri(url);
        raw.setContentType(access.result().contentType());
        raw.setBody(access.result().body());
        raw.setTruncated(access.result().truncated());
        raw.setContentHash(Hashing.sha256(access.result().body()));
        raw.setHttpStatus(access.result().status());
        raw.setEtag(access.result().etag());
        raw.setLastModified(access.result().lastModified());
        raw.setFetchedAt(Instant.now());

        List<Map<String, String>> rows = isHtml(access.result().contentType())
                ? HtmlContentExtractor.rows(access.result().body(), url)
                : List.of(Map.of("url", url, "content", access.result().body()));

        List<ExtractionTask> followUps = new ArrayList<>();
        int maxDepth = config.getMaxDepth() != null
                ? config.getMaxDepth() : properties.getWeb().getMaxDepthDefault();
        if (depth < maxDepth && isHtml(access.result().contentType())) {
            for (String link : HtmlContentExtractor.links(access.result().body(), url)) {
                if (CompliantWebAccess.domainAllowed(UrlNormalizer.hostOf(link), config)) {
                    followUps.add(newFetchTask(job, source, link, depth + 1));
                }
            }
        }

        Map<String, String> stateUpdates = new LinkedHashMap<>();
        if (access.result().etag() != null) {
            stateUpdates.put("etag:" + url, access.result().etag());
        }
        if (access.result().lastModified() != null) {
            stateUpdates.put("lm:" + url, access.result().lastModified());
        }

        return new ConnectorResult(
                List.of(new ConnectorResult.FetchedItem(raw, rows)), followUps, stateUpdates);
    }

    private ExtractionTask newFetchTask(ExtractionJob job, ExtractionSource source,
                                        String url, int depth) {
        ExtractionTask task = new ExtractionTask();
        task.setJobId(job.getId());
        task.setSourceId(source.getId());
        task.setType(ExtractionTaskType.FETCH_URL);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_URL, url);
        payload.put(PAYLOAD_DEPTH, String.valueOf(depth));
        task.setPayload(payload);
        task.setDedupKey("url:" + url);
        task.setMaxAttempts(properties.getWorker().getMaxAttempts());
        return task;
    }

    private static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("html");
    }
}
