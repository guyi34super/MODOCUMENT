package com.modocument.infrastructure.extraction.web;

import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import com.modocument.infrastructure.net.OutboundHostPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA, optimization round, test plan S1 for the web crawler (ws28 SSRF fix,
 * the twin of {@code RestApiConnectorRedirectTest}): {@link HttpWebFetcher}
 * follows redirects by hand and re-checks every hop against the egress
 * policy, so a page bouncing the crawler to the cloud metadata address, a
 * private address, an unlisted loopback name or a non-HTTP scheme is refused
 * BEFORE that target is contacted; an allowed redirect is followed; a
 * redirect chain whose second hop is disallowed is refused at that hop;
 * loops are bounded.
 */
class HttpWebFetcherRedirectQaTest {

    private final List<HttpServer> servers = new ArrayList<>();
    /** "port path" of every request a stub received. */
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
    }

    /** A loopback stub; {@code location} null = answer 200 with a small page. */
    private int stub(String path, String location) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext(path, exchange -> {
            hits.add(port + " " + exchange.getRequestURI().getPath());
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
                exchange.sendResponseHeaders(302, -1);
            } else {
                byte[] body = "<html><body>ok</body></html>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        servers.add(server);
        return port;
    }

    private static HttpWebFetcher fetcher(String... allowedHosts) {
        // Enforcing: loopback is reachable only through the explicit allow-list.
        return new HttpWebFetcher(new ExtractionProperties(), new OutboundHostPolicy(true, allowedHosts));
    }

    @Test
    void redirectsToMetadataPrivateAndUnlistedTargetsAreRefusedBeforeContact() throws Exception {
        for (String target : List.of("http://169.254.169.254/latest/meta-data/iam/",
                "http://10.0.0.5/internal", "http://192.168.1.1/", "http://localhost:1/x",
                "http://[::1]/x")) {
            hits.clear();
            int port = stub("/page-" + Math.abs(target.hashCode()), target);
            String url = "http://127.0.0.1:" + port + "/page-" + Math.abs(target.hashCode());
            assertThatThrownBy(() -> fetcher("127.0.0.1").fetch(FetchRequest.plain(url)))
                    .as("redirect to %s", target)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(hits).as("only the allowed first hop was contacted for %s", target).hasSize(1);
        }
    }

    @Test
    void aNonHttpRedirectIsRefused() throws Exception {
        int port = stub("/file", "file:///etc/passwd");
        assertThatThrownBy(() -> fetcher("127.0.0.1").fetch(
                FetchRequest.plain("http://127.0.0.1:" + port + "/file")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-HTTP");
    }

    @Test
    void anAllowedRedirectIsFollowedAndADisallowedSecondHopIsRefused() throws Exception {
        int target = stub("/final", null);
        int origin = stub("/start", "http://localhost:" + target + "/final");

        FetchResult result = fetcher("127.0.0.1", "localhost").fetch(
                FetchRequest.plain("http://127.0.0.1:" + origin + "/start"));
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("ok");
        assertThat(hits).containsExactly(origin + " /start", target + " /final");

        // allowed -> allowed -> metadata: refused at the second redirect, nothing contacted there.
        hits.clear();
        int second = stub("/hop2", "http://169.254.169.254/latest/meta-data/");
        int first = stub("/hop1", "http://localhost:" + second + "/hop2");
        assertThatThrownBy(() -> fetcher("127.0.0.1", "localhost").fetch(
                FetchRequest.plain("http://127.0.0.1:" + first + "/hop1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hits).containsExactly(first + " /hop1", second + " /hop2");
    }

    @Test
    void redirectLoopsAreBounded() throws Exception {
        int port = stub("/", "/loop");
        assertThatThrownBy(() -> fetcher("127.0.0.1").fetch(
                FetchRequest.plain("http://127.0.0.1:" + port + "/first")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many redirects");
        assertThat(hits).hasSize(HttpWebFetcher.MAX_REDIRECTS + 1);
    }
}
