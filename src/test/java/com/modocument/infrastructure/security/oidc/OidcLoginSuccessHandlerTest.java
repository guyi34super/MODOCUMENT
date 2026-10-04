package com.modocument.infrastructure.security.oidc;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.usecase.AuthUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The OIDC landing (ws07): a successful external authentication yields OUR
 * refresh cookie plus a clean 302 into the SPA — the access token is never
 * in the URL — and any provisioning failure collapses to an opaque
 * /login?error=oidc with a LOGIN_FAILED audit row.
 */
class OidcLoginSuccessHandlerTest {

    private OidcLoginService loginService;
    private AuditRecorder audit;
    private OidcLoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        loginService = mock(OidcLoginService.class);
        audit = mock(AuditRecorder.class);
        handler = new OidcLoginSuccessHandler(loginService, audit, "/login/complete");
    }

    @Test
    void successSetsTheRefreshCookieAndRedirectsWithoutAnyTokenInTheUrl() throws Exception {
        UserDto user = new UserDto(1, "ana@acme.example", "Ana", "{}", "READ_WRITE", 7);
        when(loginService.login(any(), any(), any(), any())).thenReturn(
                new AuthUseCase.AuthSession(
                        new AuthResponse("access-token-value", user), "refresh-token-value"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login/complete");
        // The bridge pattern's whole point: no token material in the URL —
        // the SPA exchanges the httpOnly cookie at POST /api/auth/refresh.
        assertThat(response.getRedirectedUrl()).doesNotContain("access-token-value");
        assertThat(response.getRedirectedUrl()).doesNotContain("refresh-token-value");

        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).startsWith("modoc_refresh=refresh-token-value");
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("Secure");
        assertThat(cookie).contains("SameSite=Strict");
        assertThat(cookie).contains("Path=/api/auth/refresh");
    }

    @Test
    void provisioningFailureRedirectsOpaquelyAndAuditsLoginFailed() throws Exception {
        when(loginService.login(any(), any(), any(), any()))
                .thenThrow(new OidcLoginService.OidcLoginException(
                        "OIDC provider 'keycloak' maps to bank code 'GONE', which does not exist"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication());

        assertThat(response.getStatus()).isEqualTo(302);
        // Opaque: no bank code, no email, no exception detail in the redirect.
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=oidc");
        assertThat(response.getHeader("Set-Cookie")).isNull();

        ArgumentCaptor<AuditEventSpec> specs = ArgumentCaptor.forClass(AuditEventSpec.class);
        org.mockito.Mockito.verify(audit).record(specs.capture());
        assertThat(specs.getValue().action()).isEqualTo("LOGIN_FAILED");
        assertThat(specs.getValue().newValues())
                .isEqualTo(Map.of("method", "oidc", "provider", "keycloak"));
    }

    private static OAuth2AuthenticationToken authentication() {
        OidcIdToken idToken = new OidcIdToken("id-token", Instant.now(),
                Instant.now().plusSeconds(300), Map.of(
                        "sub", "sub-1",
                        "email", "ana@acme.example",
                        "iss", "http://localhost:8081/realms/modocument"));
        return new OAuth2AuthenticationToken(
                new DefaultOidcUser(List.of(), idToken), List.of(), "keycloak");
    }
}
