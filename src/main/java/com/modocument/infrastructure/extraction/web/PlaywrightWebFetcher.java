package com.modocument.infrastructure.extraction.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;
import com.modocument.application.port.WebFetcher;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JS-rendering fetcher backed by headless Chromium. Availability is probed
 * lazily at first use — when the Playwright browsers are not installed (e.g.
 * the slim Docker image), it reports unavailable and the composite fetcher
 * degrades to plain HTTP, mirroring the OCR/docling degradation pattern.
 */
@Component("playwrightWebFetcher")
public class PlaywrightWebFetcher implements WebFetcher {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightWebFetcher.class);

    private final ExtractionProperties properties;
    private final Object lock = new Object();
    private Playwright playwright;
    private Browser browser;
    private Boolean available;

    public PlaywrightWebFetcher(ExtractionProperties properties) {
        this.properties = properties;
    }

    public boolean isAvailable() {
        synchronized (lock) {
            if (available == null) {
                try {
                    playwright = Playwright.create();
                    browser = playwright.chromium().launch();
                    available = true;
                    log.info("Playwright Chromium available; JS rendering enabled");
                } catch (Throwable t) {
                    available = false;
                    log.info("Playwright unavailable ({}); falling back to plain HTTP fetches",
                            firstLine(t.getMessage()));
                    closeQuietly();
                }
            }
            return available;
        }
    }

    @Override
    public FetchResult fetch(FetchRequest request) {
        if (!isAvailable()) {
            throw new IllegalStateException("Playwright browsers are not installed.");
        }
        synchronized (lock) {
            try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(properties.getWeb().getUserAgent()));
                 Page page = context.newPage()) {
                page.setDefaultTimeout(properties.getWeb().getRequestTimeoutSeconds() * 1000.0);
                Response response = page.navigate(request.url());
                String html = page.content();
                int cap = properties.getWeb().getMaxBodyBytes();
                boolean truncated = html.length() > cap;
                if (truncated) {
                    html = html.substring(0, cap);
                }
                int status = response == null ? 200 : response.status();
                return new FetchResult(status, "text/html", html, null, null, truncated);
            }
        }
    }

    @PreDestroy
    public void close() {
        synchronized (lock) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception e) {
            // Shutdown best-effort.
        } finally {
            browser = null;
            playwright = null;
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "no detail";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
