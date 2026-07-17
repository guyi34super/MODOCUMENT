package com.modocument.application.usecase;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUseCaseTest {

    private final AppUserRepository repo = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthUseCase useCase = new AuthUseCase(repo, encoder, jwtService);

    private AppUser demoUser() {
        AppUser user = new AppUser(1, "demo@modocument.local", encoder.encode("Demo123!"), "Demo User");
        user.setSettingsJson("{}");
        return user;
    }

    @Test
    void registerCreatesUserAndReturnsToken() {
        when(repo.existsByEmail("new@example.com")).thenReturn(false);
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        AuthResponse response = useCase.register("new@example.com", "Password1!", "New User");

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.user().email()).isEqualTo("new@example.com");
        assertThat(response.user().displayName()).isEqualTo("New User");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(repo.existsByEmail("demo@modocument.local")).thenReturn(true);

        assertThatThrownBy(() -> useCase.register("demo@modocument.local", "Password1!", "X"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registerRejectsShortPassword() {
        assertThatThrownBy(() -> useCase.register("a@b.com", "short", "X"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginReturnsTokenForValidCredentials() {
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(demoUser()));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        AuthResponse response = useCase.login("demo@modocument.local", "Demo123!");

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.user().userId()).isEqualTo(1);
    }

    @Test
    void loginRejectsWrongPassword() {
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(demoUser()));

        assertThatThrownBy(() -> useCase.login("demo@modocument.local", "wrong-password"))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class);
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(repo.getByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.login("nobody@example.com", "Demo123!"))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class);
    }

    @Test
    void updateProfileChangesDisplayNameAndSettings() {
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDto updated = useCase.updateProfile(1, "Renamed", "{\"theme\":\"dark\"}");

        assertThat(updated.displayName()).isEqualTo("Renamed");
        assertThat(updated.settings()).isEqualTo("{\"theme\":\"dark\"}");
    }

    @Test
    void changePasswordRequiresCorrectCurrentPassword() {
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));

        assertThatThrownBy(() -> useCase.changePassword(1, "wrong", "NewPassword1!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changePasswordStoresNewHash() {
        AppUser user = demoUser();
        when(repo.getById(1)).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.changePassword(1, "Demo123!", "NewPassword1!");

        verify(repo).save(user);
        assertThat(encoder.matches("NewPassword1!", user.getPasswordHash())).isTrue();
    }
}
