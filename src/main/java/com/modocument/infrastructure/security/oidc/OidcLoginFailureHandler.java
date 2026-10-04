package com.modocument.infrastructure.security.oidc;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.model.AuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.util.Map;

/**
 * A failed OIDC handshake (user cancelled at the IdP, invalid state/code,
 * unverifiable ID token): audited LOGIN_FAILED with the oidc marker, answered
 * with an opaque {@code /login?error=oidc} redirect — the browser never sees
 * provider error detail, which stays in the server log.
 *
 * <p>Registered only by {@link OidcClientConfig}.
 */
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginFailureHandler.class);

    private final AuditRecorder audit;

    public OidcLoginFailureHandler(AuditRecorder audit) {
        this.audit = audit;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("OIDC handshake failed: {}", exception.getMessage());
        audit.record(AuditEventSpec.builder("LOGIN_FAILED", AuditOutcome.FAILED)
                .newValues(Map.of("method", "oidc"))
                .build());
        response.sendRedirect(OidcLoginSuccessHandler.FAILURE_REDIRECT);
    }
}
