package com.modocument.infrastructure.extraction.web;

import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;
import com.modocument.application.port.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The extraction web fetcher. Every fetch is a plain HTTP fetch: JavaScript
 * rendering (headless Chromium via Playwright) was removed in ws29 — its
 * ~200 MB driver bundle was the largest part of the application jar and the
 * slim image never shipped the browsers, so it was never on in practice. A
 * source that asks for {@code renderJs} (or {@code
 * extraction.web.render-js-enabled=true}) still works and gets the
 * server-rendered HTML, the same degradation it always had.
 */
@Component
@Primary
public class CompositeWebFetcher implements WebFetcher {

    private static final Logger log = LoggerFactory.getLogger(CompositeWebFetcher.class);

    private final HttpWebFetcher http;
    private final AtomicBoolean renderJsNoticeLogged = new AtomicBoolean();

    public CompositeWebFetcher(HttpWebFetcher http) {
        this.http = http;
    }

    @Override
    public FetchResult fetch(FetchRequest request) throws Exception {
        if (request.renderJs() && renderJsNoticeLogged.compareAndSet(false, true)) {
            log.info("JavaScript rendering is not available in this build; renderJs sources are "
                    + "fetched as plain HTTP (server-rendered HTML only)");
        }
        return http.fetch(request);
    }
}
