package com.modocument.infrastructure.extraction.connector;

import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.HostRateLimiter;
import com.modocument.application.port.JobSecrets;
import com.modocument.domain.document.ApiSourceConfig;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import com.modocument.infrastructure.extraction.worker.ExtractionMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RestApiConnectorTest {

    private final RestApiConnector connector = new RestApiConnector(
            new ExtractionProperties(), mock(JobSecrets.class), mock(HostRateLimiter.class),
            mock(ExtractionAuditLog.class), new ExtractionMetrics(),
            com.modocument.infrastructure.net.OutboundHostPolicy.disabled());

    private ExtractionSource source(ApiSourceConfig config) {
        ExtractionSource source = new ExtractionSource();
        source.setId("s1");
        source.setType(SourceType.REST_API);
        source.setApi(config);
        return source;
    }

    private ExtractionJob job(boolean incremental) {
        ExtractionJob job = new ExtractionJob();
        job.setId("j1");
        job.setSourceId("s1");
        job.setIncremental(incremental);
        return job;
    }

    @Test
    void seedsFirstPageForPageMode() {
        ApiSourceConfig config = new ApiSourceConfig();
        config.setBaseUrl("https://api.example.com");
        config.setPath("v1/items");
        config.setPaginationMode(ApiSourceConfig.PaginationMode.PAGE);
        config.setSizeParam("per_page");
        config.setPageSize(50);

        List<ExtractionTask> seeds = connector.seedTasks(job(false), source(config), null);

        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).getPayload().get("url"))
                .isEqualTo("https://api.example.com/v1/items?page=1&per_page=50");
        assertThat(seeds.get(0).getDedupKey()).startsWith("api:");
    }

    @Test
    void incrementalSeedUsesSavedCursor() {
        ApiSourceConfig config = new ApiSourceConfig();
        config.setBaseUrl("https://api.example.com/items");
        config.setIncrementalParam("since");
        ConnectorState state = new ConnectorState("s1");
        state.setLastCursor("2026-01-01T00:00:00Z");

        List<ExtractionTask> seeds = connector.seedTasks(job(true), source(config), state);

        assertThat(seeds.get(0).getPayload().get("url"))
                .contains("since=2026-01-01T00%3A00%3A00Z");
    }

    @Test
    void buildUrlHandlesExistingQueryAndSlashes() {
        ApiSourceConfig config = new ApiSourceConfig();
        config.setBaseUrl("https://api.example.com/v1?key=1");
        config.setPath("");

        assertThat(RestApiConnector.buildUrl(config, Map.of("page", "2")))
                .isEqualTo("https://api.example.com/v1?key=1&page=2");
    }

    @Test
    void replaceParamUpdatesOrAppends() {
        assertThat(RestApiConnector.replaceParam("https://x/api?page=1&size=5", "page", "2"))
                .isEqualTo("https://x/api?page=2&size=5");
        assertThat(RestApiConnector.replaceParam("https://x/api", "page", "2"))
                .isEqualTo("https://x/api?page=2");
        assertThat(RestApiConnector.replaceParam("https://x/api?size=5", "cursor", "a b"))
                .isEqualTo("https://x/api?size=5&cursor=a+b");
    }
}
