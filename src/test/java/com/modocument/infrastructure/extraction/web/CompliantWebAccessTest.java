package com.modocument.infrastructure.extraction.web;

import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.HostRateLimiter;
import com.modocument.application.port.RobotsPolicy;
import com.modocument.application.port.WebFetcher;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.model.AuditAction;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionAuditLog;
import com.modocument.infrastructure.extraction.worker.ExtractionMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompliantWebAccessTest {

    private final WebFetcher fetcher = mock(WebFetcher.class);
    private final RobotsPolicy robots = mock(RobotsPolicy.class);
    private final HostRateLimiter rateLimiter = mock(HostRateLimiter.class);
    private final ExtractionAuditLog audit = new InMemoryExtractionAuditLog();
    private final ExtractionProperties properties = new ExtractionProperties();
    private final CompliantWebAccess access = new CompliantWebAccess(
            fetcher, robots, rateLimiter, audit, new ExtractionMetrics(), properties,
            com.modocument.infrastructure.net.OutboundHostPolicy.disabled());

    private WebSourceConfig config() {
        WebSourceConfig config = new WebSourceConfig();
        config.setAllowedDomains(List.of("example.com"));
        return config;
    }

    @Test
    void blocksHostsOutsideTheAllowList() throws Exception {
        CompliantWebAccess.Access result = access.fetch("j1", "s1", config(),
                "https://evil.org/page", null);

        assertThat(result.outcome()).isEqualTo(CompliantWebAccess.Outcome.BLOCKED_DOMAIN);
        verify(fetcher, never()).fetch(any());
        assertThat(audit.findByJob("j1", null, 10))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo(AuditAction.DOMAIN_BLOCKED));
    }

    @Test
    void subdomainsOfAllowedDomainsAreAllowed() {
        assertThat(CompliantWebAccess.domainAllowed("docs.example.com", config())).isTrue();
        assertThat(CompliantWebAccess.domainAllowed("notexample.com", config())).isFalse();
        assertThat(CompliantWebAccess.domainAllowed("example.com.evil.org", config())).isFalse();
    }

    @Test
    void robotsDisallowedIsAuditedAndNeverFetched() throws Exception {
        when(robots.isAllowed(anyString())).thenReturn(false);

        CompliantWebAccess.Access result = access.fetch("j1", "s1", config(),
                "https://example.com/private", null);

        assertThat(result.outcome()).isEqualTo(CompliantWebAccess.Outcome.BLOCKED_ROBOTS);
        verify(fetcher, never()).fetch(any());
        assertThat(audit.findByJob("j1", null, 10))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo(AuditAction.ROBOTS_BLOCKED));
    }

    @Test
    void okFetchIsRateLimitedWithCrawlDelayAndAudited() throws Exception {
        when(robots.isAllowed(anyString())).thenReturn(true);
        when(robots.crawlDelayMillis(anyString())).thenReturn(4000L);
        when(fetcher.fetch(any())).thenReturn(
                new FetchResult(200, "text/html", "<html/>", "W/\"1\"", null, false));

        CompliantWebAccess.Access result = access.fetch("j1", "s1", config(),
                "https://example.com/page", null);

        assertThat(result.outcome()).isEqualTo(CompliantWebAccess.Outcome.OK);
        // Effective delay honors the crawl-delay (larger than the 1000ms default).
        verify(rateLimiter).acquire(eq("example.com"), eq(4000L));
        assertThat(audit.findByJob("j1", null, 10))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo(AuditAction.FETCH));
    }

    @Test
    void conditionalHeadersComeFromSavedState() throws Exception {
        when(robots.isAllowed(anyString())).thenReturn(true);
        when(fetcher.fetch(any())).thenReturn(new FetchResult(304, null, "", null, null, false));
        ConnectorState state = new ConnectorState("s1");
        state.getEtags().put("https://example.com/page", "W/\"9\"");

        CompliantWebAccess.Access result = access.fetch("j1", "s1", config(),
                "https://example.com/page", state);

        assertThat(result.outcome()).isEqualTo(CompliantWebAccess.Outcome.NOT_MODIFIED);
        ArgumentCaptor<FetchRequest> request = ArgumentCaptor.forClass(FetchRequest.class);
        verify(fetcher).fetch(request.capture());
        assertThat(request.getValue().etag()).isEqualTo("W/\"9\"");
    }

    @Test
    void authWallsArePermanentSkipsNeverRetries() throws Exception {
        when(robots.isAllowed(anyString())).thenReturn(true);
        for (int status : new int[]{401, 402, 403, 404, 410, 451}) {
            when(fetcher.fetch(any())).thenReturn(
                    new FetchResult(status, null, "", null, null, false));

            CompliantWebAccess.Access result = access.fetch("j1", "s1", config(),
                    "https://example.com/locked", null);

            assertThat(result.outcome()).isEqualTo(CompliantWebAccess.Outcome.SKIPPED_HTTP);
        }
    }

    @Test
    void serverErrorsAreRetryable() throws Exception {
        when(robots.isAllowed(anyString())).thenReturn(true);
        when(fetcher.fetch(any())).thenReturn(new FetchResult(503, null, "", null, null, false));

        CompliantWebAccess.Access result = access.fetch("j1", "s1", config(),
                "https://example.com/flaky", null);

        assertThat(result.outcome()).isEqualTo(CompliantWebAccess.Outcome.RETRYABLE_HTTP);
    }
}
