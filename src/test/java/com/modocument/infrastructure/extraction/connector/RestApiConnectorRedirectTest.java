package com.modocument.infrastructure.extraction.connector;

import com.modocument.application.dto.ConnectorResult;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.HostRateLimiter;
import com.modocument.application.port.JobSecrets;
import com.modocument.domain.document.ApiSourceConfig;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import com.modocument.infrastructure.extraction.worker.ExtractionMetrics;
import com.modocument.infrastructure.net.OutboundHostPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ws28 SSRF fix: the REST connector never lets the HTTP client follow a
 * redirect on its own. Each hop is re-checked against the egress policy (a
 * public API bouncing the worker to the cloud metadata address is refused
 * BEFORE that address is contacted), and credentials never follow a
 * redirect to another origin.
 */
class RestApiConnectorRedirectTest {

    private final List<HttpServer> servers = new java.util.ArrayList<>();
    /** "port path authorization-header" of every request a stub received. */
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
    }

    /** A loopback stub; {@code location} null = answer 200 with a JSON array. */
    private int stub(String path, String location) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext(path, exchange -> {
            hits.add(port + " " + exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
                exchange.sendResponseHeaders(302, -1);
            } else {
                byte[] body = "[{\"id\":1}]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        servers.add(server);
        return port;
    }

    private RestApiConnector connector(String... allowedHosts) {
        JobSecrets secrets = mock(JobSecrets.class);
        when(secrets.get("j1")).thenReturn(Optional.of(Map.of("apiToken", "secret-token")));
        // Enforcing policy: loopback is only reachable through the explicit allow-list.
        return new RestApiConnector(new ExtractionProperties(), secrets,
                mock(HostRateLimiter.class), mock(ExtractionAuditLog.class),
                new ExtractionMetrics(), new OutboundHostPolicy(true, allowedHosts));
    }

    private static ConnectorResult execute(RestApiConnector connector, String url) throws Exception {
        ApiSourceConfig config = new ApiSourceConfig();
        config.setBaseUrl(url);
        config.setAuthType(ApiSourceConfig.AuthType.BEARER);
        ExtractionSource source = new ExtractionSource();
        source.setId("s1");
        source.setType(SourceType.REST_API);
        source.setApi(config);
        ExtractionJob job = new ExtractionJob();
        job.setId("j1");
        job.setSourceId("s1");
        ExtractionTask task = new ExtractionTask();
        task.setJobId("j1");
        task.setSourceId("s1");
        task.setPayload(new LinkedHashMap<>(Map.of("url", url, "page", "1")));
        return connector.execute(task, job, source, null);
    }

    @Test
    void aRedirectToTheMetadataAddressIsRefusedBeforeItIsContacted() throws Exception {
        int port = stub("/api", "http://169.254.169.254/latest/meta-data/iam/");
        RestApiConnector connector = connector("127.0.0.1");

        assertThatThrownBy(() -> execute(connector, "http://127.0.0.1:" + port + "/api/items"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an allowed destination");
        assertThat(hits).as("only the allowed first hop was contacted").hasSize(1);
    }

    @Test
    void aRedirectToAnUnlistedLoopbackNameIsRefusedToo() throws Exception {
        int port = stub("/api", null);
        int bouncer = stub("/start", "http://localhost:" + port + "/api/items");
        RestApiConnector connector = connector("127.0.0.1");

        assertThatThrownBy(() -> execute(connector, "http://127.0.0.1:" + bouncer + "/start"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hits).hasSize(1);
    }

    @Test
    void anAllowedRedirectIsFollowedButCredentialsStayWithTheOrigin() throws Exception {
        int target = stub("/api", null);
        int origin = stub("/start", "http://localhost:" + target + "/api/items");
        RestApiConnector connector = connector("127.0.0.1", "localhost");

        ConnectorResult result = execute(connector, "http://127.0.0.1:" + origin + "/start");

        assertThat(result.items()).hasSize(1);
        assertThat(hits).containsExactly(
                origin + " /start Bearer secret-token",
                target + " /api/items null");
    }

    @Test
    void aSameOriginRedirectKeepsTheCredentialsAndLoopsAreBounded() throws Exception {
        int[] port = new int[1];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port[0] = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            hits.add(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.start();
        servers.add(server);

        assertThatThrownBy(() -> execute(connector("127.0.0.1"),
                "http://127.0.0.1:" + port[0] + "/first"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many redirects");
        assertThat(hits).hasSize(RestApiConnector.MAX_REDIRECTS + 1);
        assertThat(RestApiConnector.sameOrigin(java.net.URI.create("https://a.example/x"),
                java.net.URI.create("https://a.example:443/y"))).isTrue();
        assertThat(RestApiConnector.sameOrigin(java.net.URI.create("https://a.example/x"),
                java.net.URI.create("http://a.example/y"))).isFalse();
    }
}
