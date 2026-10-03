package com.modocument.infrastructure.security;

import com.modocument.domain.model.Uuid7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Gives every request ONE id for its whole lifetime (E9): an inbound
 * {@code X-Request-Id} is honored when it is a safe token
 * ({@code [A-Za-z0-9-]{1,64}} — anything else, e.g. log-injection attempts,
 * is discarded), otherwise a UUIDv7 is minted. The id is
 * <ul>
 *   <li>put on the logging MDC ({@code requestId}, printed by every log
 *       line via logback-spring.xml and carried onto async threads by
 *       TenantTaskDecorator),</li>
 *   <li>echoed back in the {@code X-Request-Id} response header so callers
 *       can quote it, and</li>
 *   <li>stamped on every AuditEvent row the request produces
 *       (JpaAuditRecorder reads the MDC key).</li>
 * </ul>
 * The best-effort client ip lands on the MDC the same way ({@code ip} —
 * first X-Forwarded-For hop, trustworthy because the app always sits behind
 * its reverse proxy, matching AuthController's reading).
 *
 * <p>Registered as a plain servlet filter at HIGHEST_PRECEDENCE — BEFORE
 * the security filter chain, so even a 401 from bearer authentication logs
 * and echoes the id. Both MDC keys are cleared in a finally block; pooled
 * request threads never leak one request's id into the next.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_CLIENT_IP = "ip";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String requestId = inbound != null && SAFE_REQUEST_ID.matcher(inbound).matches()
                ? inbound
                : Uuid7.generate().toString();
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CLIENT_IP, clientIp(request));
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_CLIENT_IP);
        }
    }

    /**
     * Best-effort client address: the first X-Forwarded-For hop when the
     * reverse proxy set one, else the socket address (same trust reasoning
     * as AuthController#clientIp). Truncated to the audit column's width.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",", 2)[0].trim()
                : request.getRemoteAddr();
        if (ip == null) {
            return "";
        }
        return ip.length() <= 64 ? ip : ip.substring(0, 64);
    }
}
