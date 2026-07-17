package com.modocument.infrastructure.extraction.web;

import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;
import com.modocument.application.port.WebFetcher;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Routes fetches: Playwright when JS rendering is requested, enabled, and
 * actually available; plain HTTP otherwise. Mirrors CompositeDocumentParser's
 * degradation approach.
 */
@Component
@Primary
public class CompositeWebFetcher implements WebFetcher {

    private final HttpWebFetcher http;
    private final PlaywrightWebFetcher playwright;
    private final ExtractionProperties properties;

    public CompositeWebFetcher(HttpWebFetcher http, PlaywrightWebFetcher playwright,
                               ExtractionProperties properties) {
        this.http = http;
        this.playwright = playwright;
        this.properties = properties;
    }

    @Override
    public FetchResult fetch(FetchRequest request) throws Exception {
        if (request.renderJs() && properties.getWeb().isRenderJsEnabled()
                && playwright.isAvailable()) {
            return playwright.fetch(request);
        }
        return http.fetch(request);
    }
}
