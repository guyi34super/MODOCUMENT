package com.modocument.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The ONE way the application resolves "the client's IP" — rate-limit keys,
 * audit rows, refresh-token rows and the logging MDC all go through here.
 *
 * <p>It deliberately reads ONLY {@link HttpServletRequest#getRemoteAddr()}.
 * With {@code server.forward-headers-strategy: framework} (application.yml)
 * Spring's ForwardedHeaderFilter has already replaced the socket address with
 * the client address the fronting proxy wrote into {@code X-Forwarded-For} —
 * and that proxy (frontend/nginx.conf) OVERWRITES the header with its own
 * {@code $remote_addr} on every proxied request, so a client-supplied chain
 * never survives into it. Hand-parsing {@code X-Forwarded-For} here again
 * would reopen the spoofing hole this replaces: any caller could plant an
 * arbitrary first hop and dodge per-IP rate limits or forge audit IPs. The
 * remaining assumption is deployment-side: the app port must not be reachable
 * except through the proxy (docker-compose no longer publishes it).
 */
public final class ClientIps {

    /** Matches the AuditEvent/RefreshToken Ip column width. */
    private static final int MAX_LENGTH = 64;

    private ClientIps() {
    }

    /**
     * The effective client address (forwarded-aware via the servlet request),
     * truncated to the persistence width. Never null; "" when unknown.
     */
    public static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip == null || ip.isBlank()) {
            return "";
        }
        return ip.length() <= MAX_LENGTH ? ip : ip.substring(0, MAX_LENGTH);
    }
}
