package com.modocument.infrastructure.extraction.web;

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

    private final ExtractionProperties properties;
    private final HttpClient client;

    public HttpWebFetcher(ExtractionProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(properties.getWeb().getRequestTimeoutSeconds()))
                .build();
    }

    @Override
    public FetchResult fetch(FetchRequest request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(Duration.ofSeconds(properties.getWeb().getRequestTimeoutSeconds()))
                .header("User-Agent", properties.getWeb().getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .GET();
        if (request.etag() != null) {
            builder.header("If-None-Match", request.etag());
        }
        if (request.lastModified() != null) {
            builder.header("If-Modified-Since", request.lastModified());
        }

        HttpResponse<byte[]> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());

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
