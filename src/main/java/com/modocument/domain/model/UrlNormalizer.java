package com.modocument.domain.model;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Canonicalizes URLs so the crawl frontier and dedup keys treat equivalent
 * URLs as one: lowercased scheme/host, default ports and fragments dropped,
 * tracking parameters (utm_*) removed, query parameters sorted.
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /** Resolves href against base and normalizes; null when not http(s). */
    public static String resolve(String baseUrl, String href) {
        try {
            URI resolved = URI.create(baseUrl).resolve(href.trim());
            return normalize(resolved.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static String normalize(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            if (host == null) {
                return null;
            }
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    ? "/" : uri.getRawPath();
            String query = normalizeQuery(uri.getRawQuery());
            StringBuilder result = new StringBuilder(scheme).append("://").append(host);
            if (port > 0) {
                result.append(':').append(port);
            }
            result.append(path);
            if (query != null && !query.isEmpty()) {
                result.append('?').append(query);
            }
            return result.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        return Arrays.stream(query.split("&"))
                .filter(param -> !param.toLowerCase(Locale.ROOT).startsWith("utm_"))
                .filter(param -> !param.isEmpty())
                .sorted()
                .collect(Collectors.joining("&"));
    }

    public static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }
}
