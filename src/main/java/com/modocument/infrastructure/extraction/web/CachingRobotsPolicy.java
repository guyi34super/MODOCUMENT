package com.modocument.infrastructure.extraction.web;

import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;
import com.modocument.application.port.RobotsPolicy;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * robots.txt gate with a per-host TTL cache. Fail-closed by design: a robots
 * file that cannot be fetched (server error, network failure) or parsed
 * means the host is off-limits until the cache entry expires; only an
 * explicit 404/410 (no robots file) means everything is allowed.
 */
@Component
public class CachingRobotsPolicy implements RobotsPolicy {

    private static final Logger log = LoggerFactory.getLogger(CachingRobotsPolicy.class);

    private record CacheEntry(RobotsRules rules, Instant expires) {
    }

    private final HttpWebFetcher fetcher;
    private final ExtractionProperties properties;
    private final RobotsTxtParser parser = new RobotsTxtParser();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CachingRobotsPolicy(HttpWebFetcher fetcher, ExtractionProperties properties) {
        this.fetcher = fetcher;
        this.properties = properties;
    }

    @Override
    public boolean isAllowed(String url) {
        URI uri = URI.create(url);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        return rulesFor(uri).isAllowed(path);
    }

    @Override
    public long crawlDelayMillis(String url) {
        return rulesFor(URI.create(url)).crawlDelayMillis();
    }

    private RobotsRules rulesFor(URI uri) {
        String hostKey = uri.getScheme() + "://" + uri.getAuthority();
        CacheEntry entry = cache.get(hostKey);
        if (entry != null && entry.expires().isAfter(Instant.now())) {
            return entry.rules();
        }
        RobotsRules rules = load(hostKey);
        cache.put(hostKey, new CacheEntry(rules,
                Instant.now().plus(Duration.ofSeconds(properties.getWeb().getRobotsTtlSeconds()))));
        return rules;
    }

    private RobotsRules load(String hostKey) {
        try {
            FetchResult result = fetcher.fetch(FetchRequest.plain(hostKey + "/robots.txt"));
            if (result.ok()) {
                return parser.parse(result.body(), userAgentToken());
            }
            if (result.status() == 404 || result.status() == 410) {
                return RobotsRules.allowAll();
            }
            log.warn("robots.txt for {} returned {}; failing closed", hostKey, result.status());
            return RobotsRules.disallowAll();
        } catch (Exception e) {
            log.warn("robots.txt for {} could not be fetched ({}); failing closed",
                    hostKey, e.getMessage());
            return RobotsRules.disallowAll();
        }
    }

    /** The product token before any '/' or space, e.g. "MoDocumentBot". */
    private String userAgentToken() {
        String userAgent = properties.getWeb().getUserAgent();
        int cut = userAgent.indexOf('/');
        if (cut < 0) {
            cut = userAgent.indexOf(' ');
        }
        return cut < 0 ? userAgent : userAgent.substring(0, cut);
    }
}
