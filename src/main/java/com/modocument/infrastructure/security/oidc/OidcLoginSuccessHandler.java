package com.modocument.infrastructure.security.oidc;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.security.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Lands the browser after the external IdP authenticated it (ws07 bridge
 * pattern): JIT-provisions/links the account ({@link OidcLoginService}),
 * opens a normal MoDocument session — rotating refresh family in the same
 * httpOnly cookie password logins use — and 302-redirects to the SPA's
 * {@code /login/complete} route. The ACCESS TOKEN IS NEVER IN THE URL: the
 * SPA calls POST /api/auth/refresh (cookie credential) to obtain it.
 *
 * <p>Provisioning failures (no email claim, unknown bank, foreign identity
 * re-link) are audited LOGIN_FAILED and answered with an opaque
 * {@code /login?error=oidc} redirect — no detail leaks to the browser.
 *
 * <p>Registered only by {@link OidcClientConfig}, i.e. only when OIDC
 * providers are configured.
 */
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    /** Opaque failure landing; detail stays in the log + audit trail. */
    static final String FAILURE_REDIRECT = "/login?error=oidc";

    private static final Logger log = LoggerFactory.getLogger(OidcLoginSuccessHandler.class);

    // Keep in sync with AuthController (same cookie, same scope): httpOnly +
    // Secure + SameSite=Strict, path-scoped to the refresh endpoint only.
    private static final String REFRESH_COOKIE = "modoc_refresh";
    private static final String REFRESH_PATH = "/api/auth/refresh";
    private static final Duration REFRESH_COOKIE_AGE = Duration.ofDays(7);

    private final OidcLoginService loginService;
    private final AuditRecorder audit;
    private final String postLoginRedirect;

    public OidcLoginSuccessHandler(OidcLoginService loginService, AuditRecorder audit,
                                   String postLoginRedirect) {
        this.loginService = loginService;
        this.audit = audit;
        this.postLoginRedirect = postLoginRedirect;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registrationId = token.getAuthorizedClientRegistrationId();
        if (!(token.getPrincipal() instanceof OidcUser oidcUser)) {
            // Every registration requests the openid scope, so a plain OAuth2
            // principal means a misconfigured provider.
            fail(request, response, registrationId, null,
                    new IllegalStateException("Principal is not an OIDC user — is the "
                            + "'openid' scope enabled at the provider?"));
            return;
        }
        AuthUseCase.AuthSession session;
        try {
            session = loginService.login(registrationId, oidcUser,
                    clientIp(request), request.getHeader(HttpHeaders.USER_AGENT));
        } catch (RuntimeException e) {
            fail(request, response, registrationId, oidcUser.getEmail(), e);
            return;
        }
        cleanUpHandshake(request);
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString());
        // 302 to the SPA; it exchanges the cookie for the access token.
        response.sendRedirect(postLoginRedirect);
    }

    private void fail(HttpServletRequest request, HttpServletResponse response,
                      String registrationId, String email, RuntimeException cause)
            throws IOException {
        log.warn("OIDC login via '{}' failed after IdP authentication: {}",
                registrationId, cause.getMessage());
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("method", "oidc");
        values.put("provider", registrationId);
        if (cause instanceof OidcLoginService.OidcLoginException refusal
                && refusal.reason() != null) {
            // ws21: e.g. bank_suspended — in the audit row only, never the redirect.
            values.put("reason", refusal.reason());
        }
        AuditEventSpec.Builder failed = AuditEventSpec
                .builder("LOGIN_FAILED", AuditOutcome.FAILED)
                .newValues(values);
        if (email != null && !email.isBlank()) {
            failed.actorEmail(email.trim().toLowerCase(java.util.Locale.ROOT));
        }
        audit.record(failed.build());
        cleanUpHandshake(request);
        response.sendRedirect(FAILURE_REDIRECT);
    }

    /** Drops the session the authorization-code handshake created. */
    private static void cleanUpHandshake(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** Same proxy-aware client address rule as AuthController ({@link ClientIps}). */
    private static String clientIp(HttpServletRequest request) {
        return ClientIps.clientIp(request);
    }

    private static ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_PATH)
                .maxAge(REFRESH_COOKIE_AGE)
                .build();
    }
}
