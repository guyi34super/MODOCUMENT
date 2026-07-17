package com.modocument.application.port;

/**
 * robots.txt gate consulted before every web fetch. Fail-closed: when the
 * robots file cannot be retrieved (server error) or parsed, the URL is
 * treated as disallowed.
 */
public interface RobotsPolicy {

    boolean isAllowed(String url);

    /** Crawl-delay for the URL's host in millis; 0 when none declared. */
    long crawlDelayMillis(String url);
}
