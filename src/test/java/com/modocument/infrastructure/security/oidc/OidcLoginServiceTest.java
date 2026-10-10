package com.modocument.infrastructure.security.oidc;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JIT provisioning rules (ws07): subject match wins over email, email match
 * links, a brand-new identity lands in the provider's bank with its default
 * role, the role claim maps, and every misconfiguration fails closed with a
 * clear message that never reaches the browser.
 */
class OidcLoginServiceTest {

    private AppUserJpaRepository users;
    private BankJpaRepository banks;
    private AuthUseCase auth;
    private AuditRecorder audit;
    private OidcProperties properties;
    private OidcLoginService service;

    @BeforeEach
    void setUp() {
        users = mock(AppUserJpaRepository.class);
        banks = mock(BankJpaRepository.class);
        auth = mock(AuthUseCase.class);
        audit = mock(AuditRecorder.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("$2a$10$unusable-random-hash");
        properties = new OidcProperties();
        OidcProperties.Provider keycloak = new OidcProperties.Provider();
        keycloak.setIssuerUri("http://localhost:8081/realms/modocument");
        keycloak.setClientId("modocument-app");
        keycloak.setClientSecret("secret");
        keycloak.setBankCode("ACME");
        keycloak.setDefaultRole(Role.READ_WRITE);
        keycloak.setRoleClaim("modocument_roles");
        properties.getProviders().put("keycloak", keycloak);
        service = new OidcLoginService(users, banks, auth, audit, encoder, properties);
        // Echo saves back so the service sees persisted state.
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void newIdentityIsProvisionedIntoTheProvidersBankWithItsDefaultRole() {
        when(users.findByAuthProviderAndProviderSubject("keycloak", "sub-1"))
                .thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ana@acme.example")).thenReturn(Optional.empty());
        Bank acme = new Bank("ACME", "Acme Bank");
        acme.setBankId(7);
        when(banks.findByCode("ACME")).thenReturn(Optional.of(acme));

        service.login("keycloak", oidcUser(Map.of(
                "sub", "sub-1", "email", "Ana@acme.example", "name", "Ana Analyst")), "1.2.3.4", "agent");

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(auth).openOidcSession(saved.capture(), eq("keycloak"), eq("1.2.3.4"), eq("agent"));
        AppUser user = saved.getValue();
        assertThat(user.getEmail()).isEqualTo("ana@acme.example");
        assertThat(user.getBankId()).isEqualTo(7);
        assertThat(user.getRole()).isEqualTo(Role.READ_WRITE);
        assertThat(user.getAuthProvider()).isEqualTo("keycloak");
        assertThat(user.getProviderSubject()).isEqualTo("sub-1");
        assertThat(user.getDisplayName()).isEqualTo("Ana Analyst");
        assertThat(user.getPasswordHash()).isNotBlank();

        ArgumentCaptor<AuditEventSpec> specs = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(specs.capture());
        assertThat(specs.getValue().action()).isEqualTo("USER_PROVISIONED_OIDC");
        assertThat(specs.getValue().bankId()).isEqualTo(7);
    }

    @Test
    void existingAccountIsLinkedByEmailFallback() {
        AppUser local = localUser(42, "ana@acme.example");
        when(users.findByAuthProviderAndProviderSubject("keycloak", "sub-1"))
                .thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ana@acme.example")).thenReturn(Optional.of(local));

        service.login("keycloak", oidcUser(Map.of(
                "sub", "sub-1", "email", "ana@acme.example")), null, null);

        assertThat(local.getAuthProvider()).isEqualTo("keycloak");
        assertThat(local.getProviderSubject()).isEqualTo("sub-1");
        ArgumentCaptor<AuditEventSpec> specs = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(specs.capture());
        assertThat(specs.getValue().action()).isEqualTo("IDENTITY_LINKED");
        verify(banks, never()).findByCode(anyString());
        verify(auth).openOidcSession(eq(local), eq("keycloak"), any(), any());
    }

    @Test
    void subjectMatchWinsOverEmail() {
        AppUser linked = localUser(42, "old-address@acme.example");
        linked.setAuthProvider("keycloak");
        linked.setProviderSubject("sub-1");
        when(users.findByAuthProviderAndProviderSubject("keycloak", "sub-1"))
                .thenReturn(Optional.of(linked));

        // The IdP renamed the email; the stable subject still finds the account.
        service.login("keycloak", oidcUser(Map.of(
                "sub", "sub-1", "email", "new-address@acme.example")), null, null);

        verify(users, never()).findByEmailIgnoreCase(anyString());
        verify(auth).openOidcSession(eq(linked), eq("keycloak"), any(), any());
        // The account email is the unique key here; the IdP rename does not move it.
        assertThat(linked.getEmail()).isEqualTo("old-address@acme.example");
    }

    @Test
    void roleClaimMapsTheMostPrivilegedValidValueAndBumpsTokenVersion() {
        AppUser linked = localUser(42, "ana@acme.example");
        linked.setAuthProvider("keycloak");
        linked.setProviderSubject("sub-1");
        linked.setRole(Role.READ_ONLY);
        linked.setTokenVersion(3);
        when(users.findByAuthProviderAndProviderSubject("keycloak", "sub-1"))
                .thenReturn(Optional.of(linked));

        service.login("keycloak", oidcUser(Map.of(
                "sub", "sub-1", "email", "ana@acme.example",
                "modocument_roles", List.of("offline_access", "APPROVER", "READ_WRITE"))),
                null, null);

        assertThat(linked.getRole()).isEqualTo(Role.APPROVER);
        assertThat(linked.getTokenVersion()).isEqualTo(4);
        ArgumentCaptor<AuditEventSpec> specs = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(specs.capture());
        assertThat(specs.getValue().action()).isEqualTo("USER_ROLE_MAPPED_OIDC");
    }

    @Test
    void absentRoleClaimKeepsTheExistingRole() {
        AppUser linked = localUser(42, "ana@acme.example");
        linked.setAuthProvider("keycloak");
        linked.setProviderSubject("sub-1");
        linked.setRole(Role.ADMIN);
        when(users.findByAuthProviderAndProviderSubject("keycloak", "sub-1"))
                .thenReturn(Optional.of(linked));

        service.login("keycloak", oidcUser(Map.of(
                "sub", "sub-1", "email", "ana@acme.example")), null, null);

        assertThat(linked.getRole()).isEqualTo(Role.ADMIN);
        assertThat(linked.getTokenVersion()).isZero();
    }

    @Test
    void unknownBankCodeFailsWithAClearMessage() {
        when(users.findByAuthProviderAndProviderSubject(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(banks.findByCode("ACME")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("keycloak", oidcUser(Map.of(
                "sub", "sub-1", "email", "ana@acme.example")), null, null))
                .isInstanceOf(OidcLoginService.OidcLoginException.class)
                .hasMessageContaining("bank code 'ACME'")
                .hasMessageContaining("modocument.oidc.providers.keycloak.bank-code");
    }

    @Test
    void missingEmailClaimIsRefused() {
        assertThatThrownBy(() -> service.login("keycloak",
                oidcUser(Map.of("sub", "sub-1")), null, null))
                .isInstanceOf(OidcLoginService.OidcLoginException.class)
                .hasMessageContaining("no email claim");
    }

    @Test
    void accountAlreadyLinkedToAnotherIdentityIsNeverRelinkedByEmail() {
        AppUser foreign = localUser(42, "ana@acme.example");
        foreign.setAuthProvider("entra");
        foreign.setProviderSubject("other-subject");
        when(users.findByAuthProviderAndProviderSubject("keycloak", "sub-1"))
                .thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ana@acme.example")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.login("keycloak", oidcUser(Map.of(
                "sub", "sub-1", "email", "ana@acme.example")), null, null))
                .isInstanceOf(OidcLoginService.OidcLoginException.class)
                .hasMessageContaining("already linked");
        assertThat(foreign.getAuthProvider()).isEqualTo("entra");
    }

    /**
     * ws09 leaver switch: the IdP may still vouch for the identity, but a
     * disabled MoDocument account never gets a session — neither via the
     * stable subject match nor via the email fallback (which must not even
     * link the IdP identity to the disabled account).
     */
    @Test
    void disabledAccountRefusesOidcLogin() {
        AppUser linked = localUser(42, "ana@acme.example");
        linked.setAuthProvider("keycloak");
        linked.setProviderSubject("sub-1");
        linked.setDisabled(true);
        when(users.findByAuthProviderAndProviderSubject("keycloak", "sub-1"))
                .thenReturn(Optional.of(linked));

        assertThatThrownBy(() -> service.login("keycloak", oidcUser(Map.of(
                "sub", "sub-1", "email", "ana@acme.example")), null, null))
                .isInstanceOf(OidcLoginService.OidcLoginException.class)
                .hasMessageContaining("disabled");
        verify(auth, never()).openOidcSession(any(), anyString(), any(), any());
    }

    @Test
    void disabledAccountIsNotLinkedByTheEmailFallback() {
        AppUser local = localUser(42, "ana@acme.example");
        local.setDisabled(true);
        when(users.findByAuthProviderAndProviderSubject("keycloak", "sub-1"))
                .thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ana@acme.example")).thenReturn(Optional.of(local));

        assertThatThrownBy(() -> service.login("keycloak", oidcUser(Map.of(
                "sub", "sub-1", "email", "ana@acme.example")), null, null))
                .isInstanceOf(OidcLoginService.OidcLoginException.class)
                .hasMessageContaining("disabled");
        // The refusal happened BEFORE any linking side effect.
        assertThat(local.getAuthProvider()).isNull();
        verify(users, never()).save(any(AppUser.class));
    }

    @Test
    void unknownBankCodeFailsTheStartupValidatorOutsideDevProfiles() {
        when(banks.findByCode("ACME")).thenReturn(Optional.empty());
        OidcClientConfig config = new OidcClientConfig();
        assertThatThrownBy(() ->
                config.oidcBankCodeValidator(properties, banks, false).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bank code 'ACME'");
    }

    @Test
    void startupValidatorOnlyWarnsUnderDevProfilesAndPassesOnExistingBanks() throws Exception {
        when(banks.findByCode("ACME")).thenReturn(Optional.empty());
        OidcClientConfig config = new OidcClientConfig();
        // permit-dev-paths=true: H2 dev/test schemas boot empty — warn only.
        config.oidcBankCodeValidator(properties, banks, true).run(null);

        Bank acme = new Bank("ACME", "Acme Bank");
        when(banks.findByCode("ACME")).thenReturn(Optional.of(acme));
        config.oidcBankCodeValidator(properties, banks, false).run(null);
    }

    private static AppUser localUser(int id, String email) {
        AppUser user = new AppUser();
        user.setAppUserId(id);
        user.setEmail(email);
        user.setPasswordHash("$2a$10$existing");
        user.setBankId(7);
        user.setRole(Role.READ_ONLY);
        return user;
    }

    private static OidcUser oidcUser(Map<String, Object> claims) {
        Map<String, Object> all = new HashMap<>(claims);
        all.putIfAbsent("iss", "http://localhost:8081/realms/modocument");
        OidcIdToken idToken = new OidcIdToken("id-token-value",
                Instant.now(), Instant.now().plusSeconds(300), all);
        return new DefaultOidcUser(List.of(), idToken);
    }
}
