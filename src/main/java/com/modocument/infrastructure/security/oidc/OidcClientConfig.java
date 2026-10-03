package com.modocument.infrastructure.security.oidc;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TenantContext;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import java.util.List;

/**
 * The whole OIDC front door (ws07), registered ONLY when at least one
 * {@code modocument.oidc.providers.*} entry exists ({@link
 * OidcConfiguredCondition}). Without configuration none of these beans is
 * created, no ClientRegistrationRepository exists, SecurityConfig leaves
 * oauth2Login off, and the app behaves exactly as before — the main test
 * suite runs with OIDC entirely absent.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(OidcConfiguredCondition.class)
public class OidcClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OidcClientConfig.class);

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OidcProperties properties) {
        List<ClientRegistration> registrations = OidcRegistrationFactory.build(properties);
        log.info("OIDC login enabled for provider(s): {}",
                registrations.stream().map(ClientRegistration::getRegistrationId).toList());
        return new InMemoryClientRegistrationRepository(registrations);
    }

    @Bean
    public OidcLoginService oidcLoginService(AppUserJpaRepository users, BankJpaRepository banks,
                                             AuthUseCase auth, AuditRecorder audit,
                                             PasswordEncoder passwordEncoder,
                                             OidcProperties properties) {
        return new OidcLoginService(users, banks, auth, audit, passwordEncoder, properties);
    }

    @Bean
    public OidcLoginSuccessHandler oidcLoginSuccessHandler(OidcLoginService loginService,
                                                           AuditRecorder audit,
                                                           OidcProperties properties) {
        return new OidcLoginSuccessHandler(loginService, audit, properties.getPostLoginRedirect());
    }

    @Bean
    public OidcLoginFailureHandler oidcLoginFailureHandler(AuditRecorder audit) {
        return new OidcLoginFailureHandler(audit);
    }

    /**
     * Fail fast on a misconfigured bank mapping: every provider's bank-code
     * must name an existing Bank, or JIT provisioning would fail opaquely at
     * first login. Under the dev/test profiles (permit-dev-paths) this only
     * warns — the H2 schema boots empty and banks are created through the
     * app — mirroring BootstrapAdminRunner's stance.
     */
    @Bean
    public ApplicationRunner oidcBankCodeValidator(
            OidcProperties properties, BankJpaRepository banks,
            @Value("${modocument.security.permit-dev-paths:false}") boolean permitDevPaths) {
        return args -> TenantContext.runAsSystem(() ->
                properties.getProviders().forEach((registrationId, provider) -> {
                    if (banks.findByCode(provider.getBankCode()).isPresent()) {
                        return;
                    }
                    String message = "OIDC provider '" + registrationId + "' maps to bank code '"
                            + provider.getBankCode() + "', which does not exist. Create the bank "
                            + "or fix modocument.oidc.providers." + registrationId + ".bank-code.";
                    if (permitDevPaths) {
                        log.warn("{} (dev/test profile: continuing; JIT provisioning via this "
                                + "provider will fail until the bank exists)", message);
                    } else {
                        throw new IllegalStateException(message);
                    }
                }));
    }
}
