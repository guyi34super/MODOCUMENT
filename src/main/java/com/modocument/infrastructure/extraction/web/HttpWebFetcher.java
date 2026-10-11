package com.modocument.infrastructure.extraction.web;

import com.modocument.infrastructure.net.OutboundHostPolicy;
import java.util.Optional;

import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;
import com.modocument.application.port.WebFetcher;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

/**
 * Plain HTTP fetcher: honest User-Agent on every request, conditional GET
 * headers for incremental crawls, and a hard body-size cap. Never sends
 * cookies or credentials — pages behind a login are simply not fetchable.
 */
@Component("httpWebFetcher")
public class HttpWebFetcher implements WebFetcher {

    /** Redirect hops followed per fetch before giving up. */
    static final int MAX_REDIRECTS = 5;

    private final ExtractionProperties properties;
    private final OutboundHostPolicy egress;
    private final HttpClient client;

    public HttpWebFetcher(ExtractionProperties properties, OutboundHostPolicy egress) {
        this.properties = properties;
        this.egress = egress;
        // ws28 SSRF fix: redirects are followed by hand (see #fetch), each
        // hop's host re-checked — the crawl gate only ever saw the first URL.
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.getWeb().getRequestTimeoutSeconds()))
                .build();
    }

    @Override
    public FetchResult fetch(FetchRequest request) throws Exception {
        URI target = URI.create(request.url());
        HttpResponse<byte[]> response;
        for (int hop = 0; ; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(properties.getWeb().getRequestTimeoutSeconds()))
                    .header("User-Agent", properties.getWeb().getUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                    .GET();
            if (hop == 0 && request.etag() != null) {
                builder.header("If-None-Match", request.etag());
            }
            if (hop == 0 && request.lastModified() != null) {
                builder.header("If-Modified-Since", request.lastModified());
            }
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            boolean redirect = status == 301 || status == 302 || status == 303 || status == 307
                    || status == 308;
            Optional<String> location = response.headers().firstValue("Location");
            if (!redirect || location.isEmpty()) {
                break;
            }
            if (hop >= MAX_REDIRECTS) {
                throw new IllegalStateException("Too many redirects fetching " + request.url());
            }
            URI next = target.resolve(location.get().trim());
            String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalStateException("Refusing a redirect to a non-HTTP URL.");
            }
            // SSRF: the redirect target must be an allowed destination too.
            egress.checkUrl(next.toString());
            target = next;
        }

        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        int cap = properties.getWeb().getMaxBodyBytes();
        boolean truncated = bytes.length > cap;
        if (truncated) {
            bytes = Arrays.copyOf(bytes, cap);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String body = new String(bytes, charsetOf(contentType));
        return new FetchResult(
                response.statusCode(),
                contentType,
                body,
                response.headers().firstValue("ETag").orElse(null),
                response.headers().firstValue("Last-Modified").orElse(null),
                truncated);
    }

    private static Charset charsetOf(String contentType) {
        int index = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (index >= 0) {
            String name = contentType.substring(index + 8).split("[;\\s]")[0].trim();
            try {
                return Charset.forName(name);
            } catch (Exception e) {
                // Fall through to UTF-8.
            }
        }
        return StandardCharsets.UTF_8;
    }
}
