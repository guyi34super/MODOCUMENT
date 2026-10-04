package com.modocument.infrastructure.security.oidc;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TenantContext;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

/**
 * Turns an externally-authenticated OIDC identity into a MoDocument session
 * (ws07 bridge pattern): JIT-provisions/links the {@link AppUser}, then
 * reuses {@link AuthUseCase}'s issuance path so OIDC logins get exactly the
 * same access token + rotating refresh family as password logins.
 *
 * <p>Matching order: (provider, subject) first — the IdP's stable identity —
 * then email fallback, which LINKS the found account (audited
 * IDENTITY_LINKED). A brand-new identity is created in the provider's
 * configured bank with its configured default role (audited
 * USER_PROVISIONED_OIDC). When the provider declares a role claim, a valid
 * value maps onto the account's role on every login, so role changes made at
 * the IdP propagate.
 *
 * <p>Registered only by {@link OidcClientConfig}, i.e. only when OIDC
 * providers are configured.
 */
public class OidcLoginService {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserJpaRepository users;
    private final BankJpaRepository banks;
    private final AuthUseCase auth;
    private final AuditRecorder audit;
    private final PasswordEncoder passwordEncoder;
    private final OidcProperties properties;

    public OidcLoginService(AppUserJpaRepository users, BankJpaRepository banks,
                            AuthUseCase auth, AuditRecorder audit,
                            PasswordEncoder passwordEncoder, OidcProperties properties) {
        this.users = users;
        this.banks = banks;
        this.auth = auth;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * The one entry point, called by {@link OidcLoginSuccessHandler} strictly
     * after Spring Security validated the provider's ID token. Throws
     * {@link OidcLoginException} when the identity cannot be mapped to an
     * account; the handler audits LOGIN_FAILED and redirects without detail.
     */
    public AuthUseCase.AuthSession login(String registrationId, OidcUser oidcUser,
                                         String ip, String userAgent) {
        OidcProperties.Provider provider = properties.provider(registrationId);
        String subject = oidcUser.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new OidcLoginException("ID token from '" + registrationId + "' has no subject.");
        }
        String email = resolveEmail(registrationId, oidcUser);
        AppUser user;
        try {
            // SYSTEM tenant: provisioning reads the RLS-protected Bank table
            // before any tenant context exists (same stance as
            // BootstrapAdminRunner; registered in TenantContext's call-site
            // registry). AppUsers itself is exempt from RLS by design.
            user = TenantContext.runAsSystem(() ->
                    provision(registrationId, provider, subject, email, oidcUser));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return auth.openOidcSession(user, registrationId, ip, userAgent);
    }

    private AppUser provision(String registrationId, OidcProperties.Provider provider,
                              String subject, String email, OidcUser oidcUser) {
        AppUser user = users.findByAuthProviderAndProviderSubject(registrationId, subject)
                .orElse(null);
        if (user == null) {
            user = users.findByEmailIgnoreCase(email).orElse(null);
            if (user != null) {
                linkByEmail(user, registrationId, subject);
            } else {
                user = createUser(registrationId, provider, subject, email, oidcUser);
            }
        }
        applyRoleClaim(user, registrationId, provider, oidcUser);
        updateDisplayName(user, oidcUser);
        return user;
    }

    /** Email fallback: links the existing account to this IdP identity. */
    private void linkByEmail(AppUser user, String registrationId, String subject) {
        if (user.getProviderSubject() != null
                && !(registrationId.equals(user.getAuthProvider())
                        && subject.equals(user.getProviderSubject()))) {
            // The email matches an account already bound to a DIFFERENT IdP
            // identity — never silently re-link (account-takeover shape).
            throw new OidcLoginException("Account '" + user.getEmail()
                    + "' is already linked to another identity ("
                    + user.getAuthProvider() + "); refusing to re-link via '"
                    + registrationId + "'.");
        }
        String previousProvider = user.getAuthProvider();
        user.setAuthProvider(registrationId);
        user.setProviderSubject(subject);
        user.setUpdatedDate(LocalDateTime.now());
        users.save(user);
        audit.record(AuditEventSpec.builder("IDENTITY_LINKED", AuditOutcome.SUCCESS)
                .actorUserId(user.getAppUserId())
                .actorEmail(user.getEmail())
                .bankId(user.getBankId())
                .entity("AppUser", String.valueOf(user.getAppUserId()))
                .oldValues(Map.of("authProvider", previousProvider == null ? "local" : previousProvider))
                .newValues(Map.of("authProvider", registrationId, "providerSubject", subject))
                .build());
        log.info("OIDC identity {}:{} linked to existing account {} by email match.",
                registrationId, subject, user.getAppUserId());
    }

    private AppUser createUser(String registrationId, OidcProperties.Provider provider,
                               String subject, String email, OidcUser oidcUser) {
        Bank bank = banks.findByCode(provider.getBankCode())
                .orElseThrow(() -> new OidcLoginException("OIDC provider '" + registrationId
                        + "' maps to bank code '" + provider.getBankCode()
                        + "', which does not exist — create the bank or fix "
                        + "modocument.oidc.providers." + registrationId + ".bank-code."));
        Role role = provider.getDefaultRole() == null ? Role.READ_ONLY : provider.getDefaultRole();
        AppUser user = new AppUser();
        user.setEmail(email);
        // OIDC accounts have no local password: an unusable random hash keeps
        // the NOT NULL column honest while no credential can ever match it.
        user.setPasswordHash(passwordEncoder.encode(randomUnusablePassword()));
        String displayName = oidcUser.getFullName();
        user.setDisplayName(displayName == null || displayName.isBlank() ? email : displayName.trim());
        user.setBankId(bank.getBankId());
        user.setRole(role);
        user.setAuthProvider(registrationId);
        user.setProviderSubject(subject);
        user.setSettingsJson("{}");
        user.setCreatedDate(LocalDateTime.now());
        user.setUpdatedDate(LocalDateTime.now());
        user = users.save(user);
        audit.record(AuditEventSpec.builder("USER_PROVISIONED_OIDC", AuditOutcome.SUCCESS)
                .actorUserId(user.getAppUserId())
                .actorEmail(user.getEmail())
                .bankId(bank.getBankId())
                .entity("AppUser", String.valueOf(user.getAppUserId()))
                .newValues(Map.of(
                        "email", user.getEmail(),
                        "role", role.name(),
                        "bankCode", bank.getCode(),
                        "provider", registrationId))
                .build());
        log.info("OIDC user {} JIT-provisioned via '{}' into bank '{}' as {}.",
                user.getAppUserId(), registrationId, bank.getCode(), role);
        return user;
    }

    /**
     * Maps the provider's role claim (when configured and present) onto the
     * account. A changed role bumps TokenVersion so outstanding access tokens
     * carrying the old role die at the RevocationValidator.
     */
    private void applyRoleClaim(AppUser user, String registrationId,
                                OidcProperties.Provider provider, OidcUser oidcUser) {
        if (provider.getRoleClaim() == null || provider.getRoleClaim().isBlank()) {
            return;
        }
        Role mapped = highestRole(oidcUser.getClaims().get(provider.getRoleClaim().trim()));
        if (mapped == null || mapped == user.getRole()) {
            return;
        }
        Role previous = user.getRole();
        user.setRole(mapped);
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedDate(LocalDateTime.now());
        users.save(user);
        audit.record(AuditEventSpec.builder("USER_ROLE_MAPPED_OIDC", AuditOutcome.SUCCESS)
                .actorUserId(user.getAppUserId())
                .actorEmail(user.getEmail())
                .bankId(user.getBankId())
                .entity("AppUser", String.valueOf(user.getAppUserId()))
                .oldValues(Map.of("role", previous == null ? "" : previous.name()))
                .newValues(Map.of("role", mapped.name(), "provider", registrationId))
                .build());
    }

    private void updateDisplayName(AppUser user, OidcUser oidcUser) {
        String name = oidcUser.getFullName();
        if (name != null && !name.isBlank() && !name.trim().equals(user.getDisplayName())) {
            user.setDisplayName(name.trim());
            user.setUpdatedDate(LocalDateTime.now());
            users.save(user);
        }
    }

    /**
     * The role claim may be a plain string or a list (Keycloak's realm-role
     * mapper is multivalued); unknown values are ignored and the MOST
     * privileged valid one wins.
     */
    static Role highestRole(Object claimValue) {
        if (claimValue == null) {
            return null;
        }
        Collection<?> values = claimValue instanceof Collection<?> collection
                ? collection
                : java.util.List.of(claimValue);
        Role best = null;
        for (Object value : values) {
            Role candidate = parseRole(String.valueOf(value));
            if (candidate != null && (best == null || candidate.ordinal() < best.ordinal())) {
                // Role's declaration order IS the privilege order:
                // ADMIN, APPROVER, READ_WRITE, READ_ONLY.
                best = candidate;
            }
        }
        return best;
    }

    private static Role parseRole(String value) {
        try {
            return Role.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Email identifies the account on first login; without one, no account. */
    private static String resolveEmail(String registrationId, OidcUser oidcUser) {
        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            // Entra tokens for some account types carry the address only in
            // preferred_username; accept it when it looks like an email.
            Object preferred = oidcUser.getClaims().get("preferred_username");
            if (preferred instanceof String candidate && candidate.contains("@")) {
                email = candidate;
            }
        }
        if (email == null || email.isBlank()) {
            throw new OidcLoginException("ID token from '" + registrationId
                    + "' carries no email claim; cannot provision an account.");
        }
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String randomUnusablePassword() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /** An OIDC identity that cannot be mapped to an account (no detail leaks to the browser). */
    public static class OidcLoginException extends RuntimeException {
        public OidcLoginException(String message) {
            super(message);
        }
    }
}
