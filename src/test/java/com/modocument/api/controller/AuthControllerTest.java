package com.modocument.api.controller;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.LoginRequest;
import com.modocument.application.dto.UserDto;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.infrastructure.ratelimit.AuthRateLimiter;
import com.modocument.infrastructure.ratelimit.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AuthUseCase useCase = mock(AuthUseCase.class);
    // Small limit so the throttle test trips quickly; the happy-path tests stay
    // well under it.
    private final AuthRateLimiter rateLimiter = new AuthRateLimiter(3, 3, 60_000, 1000);
    private final AuthController controller = new AuthController(useCase, rateLimiter);

    private static final UserDto USER =
            new UserDto(1, "demo@modocument.local", "Demo User", "{}", "READ_ONLY", 1);

    private static HttpServletRequest requestFrom(String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }

    @Test
    void loginReturnsOkWithTokenAndRefreshCookie() {
        when(useCase.login(eq("demo@modocument.local"), eq("Demo123!"), any(), any()))
                .thenReturn(new AuthUseCase.AuthSession(
                        new AuthResponse("token", USER), "refresh-value"));

        ResponseEntity<?> result = controller.login(
                new LoginRequest("demo@modocument.local", "Demo123!"), requestFrom("10.0.0.2"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse body = (AuthResponse) result.getBody();
        assertThat(body.token()).isEqualTo("token");
        assertThat(body.user().email()).isEqualTo("demo@modocument.local");
        assertThat(body.user().role()).isEqualTo("READ_ONLY");
        assertThat(body.user().bankId()).isEqualTo(1);

        String cookie = result.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie)
                .contains("modoc_refresh=refresh-value")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/auth/refresh");
        // The raw refresh value must never appear in the JSON body.
        assertThat(body.toString()).doesNotContain("refresh-value");
    }

    @Test
    void repeatedLoginsFromOneAddressAreThrottled() {
        when(useCase.login(eq("victim@modocument.local"), eq("guess"), any(), any()))
                .thenThrow(new AuthUseCase.BadCredentialsException("Invalid email or password."));

        // Three attempts are allowed (per-IP max = 3); the fourth trips 429
        // before any credential check runs.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> controller.login(
                    new LoginRequest("victim@modocument.local", "guess"), requestFrom("10.0.0.3")))
                    .isInstanceOf(AuthUseCase.BadCredentialsException.class);
        }
        assertThatThrownBy(() -> controller.login(
                new LoginRequest("victim@modocument.local", "guess"), requestFrom("10.0.0.3")))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void refreshRotatesTheCookie() {
        when(useCase.refresh(eq("old-value"), any(), any()))
                .thenReturn(new AuthUseCase.AuthSession(
                        new AuthResponse("new-access", USER), "new-value"));

        ResponseEntity<?> result = controller.refresh("old-value", requestFrom("10.0.0.4"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((AuthResponse) result.getBody()).token()).isEqualTo("new-access");
        assertThat(result.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("modoc_refresh=new-value");
    }

    @Test
    void badCredentialsMapToUnauthorized() {
        ResponseEntity<?> result = controller.unauthorized(
                new AuthUseCase.BadCredentialsException("Invalid email or password."));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(((Map<?, ?>) result.getBody()).get("error")).isEqualTo("Invalid email or password.");
    }

    @Test
    void duplicateEmailMapsToConflict() {
        ResponseEntity<?> result = controller.conflict(
                new IllegalStateException("An account with this email already exists."));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invalidInputMapsToBadRequest() {
        ResponseEntity<?> result = controller.badRequest(
                new IllegalArgumentException("Password must be at least 8 characters."));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
