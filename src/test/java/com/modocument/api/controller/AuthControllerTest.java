package com.modocument.api.controller;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.LoginRequest;
import com.modocument.application.dto.RegisterRequest;
import com.modocument.application.dto.UserDto;
import com.modocument.application.usecase.AuthUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AuthUseCase useCase = mock(AuthUseCase.class);
    private final AuthController controller = new AuthController(useCase);

    private static final UserDto USER = new UserDto(1, "demo@modocument.local", "Demo User", "{}");

    @Test
    void registerReturnsCreatedWithTokenAndUser() {
        when(useCase.register("a@b.com", "Password1!", "A"))
                .thenReturn(new AuthResponse("token", USER));

        ResponseEntity<?> result = controller.register(new RegisterRequest("a@b.com", "Password1!", "A"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((AuthResponse) result.getBody()).token()).isEqualTo("token");
    }

    @Test
    void loginReturnsOkWithToken() {
        when(useCase.login("demo@modocument.local", "Demo123!"))
                .thenReturn(new AuthResponse("token", USER));

        ResponseEntity<?> result = controller.login(new LoginRequest("demo@modocument.local", "Demo123!"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((AuthResponse) result.getBody()).user().email()).isEqualTo("demo@modocument.local");
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
