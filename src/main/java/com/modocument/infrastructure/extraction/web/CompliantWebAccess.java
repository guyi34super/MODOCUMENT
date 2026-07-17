package com.modocument.infrastructure.extraction.web;

import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.HostRateLimiter;
import com.modocument.application.port.RobotsPolicy;
import com.modocument.application.port.WebFetcher;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.model.AuditAction;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import com.modocument.infrastructure.extraction.worker.ExtractionMetrics;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The single gate every web fetch must pass: domain allow-list, robots.txt
 * (fail-closed), per-host rate limiting honoring crawl-delay, conditional
 * GETs from saved state, and an audit record for every attempt — including
 * the ones that were blocked. Auth walls and paywalls (401/402/403) are
 * terminal skips, never retried and never worked around.
 */
@Component
public class CompliantWebAccess {

    public enum Outcome { OK, BLOCKED_DOMAIN, BLOCKED_ROBOTS, NOT_MODIFIED, SKIPPED_HTTP, RETRYABLE_HTTP }

    public record Access(Outcome outcome, FetchResult result) {
    }

    private final WebFetcher fetcher;
    private final RobotsPolicy robots;
    private final HostRateLimiter rateLimiter;
    private final ExtractionAuditLog audit;
    private final ExtractionMetrics metrics;
    private final ExtractionProperties properties;

    public CompliantWebAccess(WebFetcher fetcher, RobotsPolicy robots,
                              HostRateLimiter rateLimiter, ExtractionAuditLog audit,
                              ExtractionMetrics metrics, ExtractionProperties properties) {
        this.fetcher = fetcher;
        this.robots = robots;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.metrics = metrics;
        this.properties = properties;
    }

    public Access fetch(String jobId, String sourceId, WebSourceConfig config, String url,
                        ConnectorState state) throws Exception {
        String host = UrlNormalizer.hostOf(url);
        if (!domainAllowed(host, config)) {
            metrics.domainBlocked();
            audit.add(ExtractionAuditEvent.of(jobId, sourceId, AuditAction.DOMAIN_BLOCKED, url,
                    "blocked", "Host is not in the source's allowed domains."));
            return new Access(Outcome.BLOCKED_DOMAIN, null);
        }
        if (!robots.isAllowed(url)) {
            metrics.robotsBlocked();
            audit.add(ExtractionAuditEvent.of(jobId, sourceId, AuditAction.ROBOTS_BLOCKED, url,
                    "blocked", "Disallowed by robots.txt (or robots.txt unavailable)."));
            return new Access(Outcome.BLOCKED_ROBOTS, null);
        }

        long delay = properties.getWeb().getDefaultDelayMillis();
        delay = Math.max(delay, robots.crawlDelayMillis(url));
        if (config.getPerHostDelayMillis() != null) {
            delay = Math.max(delay, config.getPerHostDelayMillis());
        }
        rateLimiter.acquire(host, delay);

        String etag = state == null ? null : state.getEtags().get(url);
        String lastModified = state == null ? null : state.getLastModified().get(url);
        FetchResult result = fetcher.fetch(new FetchRequest(url, config.isRenderJs(), etag, lastModified));
        metrics.fetch();
        audit.add(ExtractionAuditEvent.of(jobId, sourceId, AuditAction.FETCH, url,
                "HTTP " + result.status(), null));

        if (result.notModified()) {
            metrics.notModified();
            return new Access(Outcome.NOT_MODIFIED, result);
        }
        if (result.ok()) {
            return new Access(Outcome.OK, result);
        }
        if (isTerminal(result.status())) {
            return new Access(Outcome.SKIPPED_HTTP, result);
        }
        return new Access(Outcome.RETRYABLE_HTTP, result);
    }

    /**
     * Access denials and paywalls are final: retrying (or circumventing) an
     * auth wall is exactly what this connector must never do.
     */
    private static boolean isTerminal(int status) {
        return status == 401 || status == 402 || status == 403 || status == 404
                || status == 410 || status == 451;
    }

    public static boolean domainAllowed(String host, WebSourceConfig config) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (String domain : config.getAllowedDomains()) {
            String allowed = domain.toLowerCase(Locale.ROOT).trim();
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }
}
