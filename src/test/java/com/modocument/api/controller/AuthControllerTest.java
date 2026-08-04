package com.modocument.api.controller;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.LoginRequest;
import com.modocument.application.dto.RegisterRequest;
import com.modocument.application.dto.UserDto;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.infrastructure.ratelimit.AuthRateLimiter;
import com.modocument.infrastructure.ratelimit.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AuthUseCase useCase = mock(AuthUseCase.class);
    // Small limit so the throttle test trips quickly; the happy-path tests stay
    // well under it.
    private final AuthRateLimiter rateLimiter = new AuthRateLimiter(3, 3, 60_000, 1000);
    private final AuthController controller = new AuthController(useCase, rateLimiter);

    private static final UserDto USER = new UserDto(1, "demo@modocument.local", "Demo User", "{}");

    private static HttpServletRequest requestFrom(String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }

    @Test
    void registerReturnsCreatedWithTokenAndUser() {
        when(useCase.register("a@b.com", "Password1!", "A"))
                .thenReturn(new AuthResponse("token", USER));

        ResponseEntity<?> result = controller.register(
                new RegisterRequest("a@b.com", "Password1!", "A"), requestFrom("10.0.0.1"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((AuthResponse) result.getBody()).token()).isEqualTo("token");
    }

    @Test
    void loginReturnsOkWithToken() {
        when(useCase.login("demo@modocument.local", "Demo123!"))
                .thenReturn(new AuthResponse("token", USER));

        ResponseEntity<?> result = controller.login(
                new LoginRequest("demo@modocument.local", "Demo123!"), requestFrom("10.0.0.2"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((AuthResponse) result.getBody()).user().email()).isEqualTo("demo@modocument.local");
    }

    @Test
    void repeatedLoginsFromOneAddressAreThrottled() {
        when(useCase.login("victim@modocument.local", "guess"))
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
