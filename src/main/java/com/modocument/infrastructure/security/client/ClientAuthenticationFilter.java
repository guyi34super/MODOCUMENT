package com.modocument.infrastructure.security.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.modocument.infrastructure.security.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The two header-borne machine credentials (ws26), placed in the security
 * chain BEFORE bearer-token authentication (SecurityConfig):
 * <ul>
 *   <li>{@code Authorization: ApiKey <prefix>.<secret>} — on any path; a key
 *       that does not authenticate is a 401 right here (the request never
 *       reaches the bearer filter or a handler);</li>
 *   <li>a client certificate forwarded by the trusted proxy
 *       ({@link MtlsClientCertificates}) — only on the machine routes
 *       ({@code /api/integration/v1/**}, {@code /api/agent/v1/**}) and only
 *       when the request carries no Authorization header (a bearer token wins,
 *       and is then checked against its own certificate binding).</li>
 * </ul>
 * Success puts a {@link ClientAuthentication} in the security context;
 * SecurityConfig's path policy then admits it to the machine routes only.
 * Bearer tokens (user and client) are left to the resource-server filter.
 */
public class ClientAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_SCHEME = "ApiKey";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiClientAuthenticator authenticator;
    private final MtlsClientCertificates certificates;

    public ClientAuthenticationFilter(ApiClientAuthenticator authenticator,
                                      MtlsClientCertificates certificates) {
        this.authenticator = authenticator;
        this.certificates = certificates;
    }

    /** The routes a machine may call (the enrolment endpoint is public). */
    public static boolean isMachinePath(String path) {
        return path != null && (path.startsWith("/api/integration/v1/")
                || path.startsWith("/api/agent/v1/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        ApiClientAuthenticator.Result result = null;
        if (authorization != null && authorization.regionMatches(true, 0, API_KEY_SCHEME + " ", 0,
                API_KEY_SCHEME.length() + 1)) {
            result = authenticator.authenticateApiKey(
                    authorization.substring(API_KEY_SCHEME.length() + 1).trim(), request);
        } else if (authorization == null && isMachinePath(pathOf(request))) {
            Optional<MtlsClientCertificates.PresentedCertificate> certificate =
                    certificates.presented(request);
            if (certificate.isPresent()) {
                result = authenticator.authenticateCertificate(certificate.get(), null, request);
            }
        }
        if (result == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!result.succeeded()) {
            reject(response, result);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ClientAuthentication(result.principal(), null));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length()) : uri;
        return path.toLowerCase(Locale.ROOT);
    }

    /** 401 (or 429 for the verification throttle) in the API's error shape. */
    static void reject(HttpServletResponse response, ApiClientAuthenticator.Result result)
            throws IOException {
        boolean limited = result.rateLimited();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", limited ? "Too many authentication attempts. Try again later."
                : "Client authentication failed.");
        body.put("code", limited ? "RATE_LIMITED" : "CLIENT_AUTH_FAILED");
        body.put("message", body.get("error"));
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        response.setStatus(limited ? 429 : HttpServletResponse.SC_UNAUTHORIZED);
        if (limited) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "60");
        } else {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    API_KEY_SCHEME + " realm=\"modocument-integration\"");
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        JSON.writeValue(response.getOutputStream(), body);
    }
}
