package com.modocument.api.security;

import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.model.AuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.Map;

/**
 * The resource server's 401 entry point, unchanged on the wire
 * ({@link BearerTokenAuthenticationEntryPoint}: {@code WWW-Authenticate:
 * Bearer error="invalid_token"}), plus the ws28 audit: a bearer token that
 * was PRESENTED and refused — bad signature, expired, revoked jti, bumped
 * TokenVersion, disabled user, an MFA challenge token used as an access
 * token — is recorded {@code ACCESS_DENIED} (kind {@code INVALID_TOKEN},
 * entity BearerToken, the request path and method in the values). A request
 * that simply carries no token is not audited (every anonymous probe of the
 * API would otherwise be a row). Nobody is authenticated here, so the row is
 * keyed by the client ip; the recorder aggregates it (one row per ip per
 * minute, with a count), which is what keeps a token-spraying client from
 * flooding the table. The token itself is never recorded.
 */
public class AuditingBearerEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(AuditingBearerEntryPoint.class);

    private final AuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
    private final AuditRecorder audit;

    public AuditingBearerEntryPoint(AuditRecorder audit) {
        this.audit = audit;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (authException instanceof OAuth2AuthenticationException && audit != null) {
            try {
                audit.record(AuditEventSpec.builder(AuditActions.ACCESS_DENIED, AuditOutcome.DENIED)
                        .entity("BearerToken", null)
                        .newValues(Map.of("kind", AuditActions.DENIED_KIND_INVALID_TOKEN,
                                "path", truncate(request.getRequestURI()),
                                "method", request.getMethod()))
                        .build());
            } catch (RuntimeException e) {
                // The 401 must go out whatever happens to the audit store.
                log.warn("ACCESS_DENIED (invalid token) could not be audited: {}", e.toString());
            }
        }
        try {
            delegate.commence(request, response, authException);
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 200 ? value : value.substring(0, 200);
    }
}
