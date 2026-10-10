package com.modocument.infrastructure.security.oidc;

import com.modocument.domain.model.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OIDC front-door configuration (ws07, gate B2/B3). External identity
 * providers only AUTHENTICATE; MoDocument then issues its own HS256 access
 * token + refresh cookie (bridge pattern), so the resource-server side of
 * SecurityConfig never changes.
 *
 * <p>Everything here is OPTIONAL: with {@code modocument.oidc.providers}
 * empty the app boots exactly as before — no ClientRegistrationRepository
 * bean, no oauth2Login filters, no new behavior (see
 * {@link OidcConfiguredCondition}). The two blessed provider keys are
 * {@code entra} (Microsoft banks) and {@code keycloak} (everyone else); any
 * other key works too as long as its issuer serves standard OIDC discovery.
 *
 * <p>See {@code application-oidc.yml.example} for the full configuration
 * shape and {@code docs/security/identity-providers.md} for the setup guide.
 */
@ConfigurationProperties(prefix = "modocument.oidc")
public class OidcProperties {

    /** Keyed by registrationId (e.g. "entra", "keycloak"). */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /**
     * Where the browser lands after a successful OIDC login. The SPA route
     * there exchanges the refresh cookie for an access token — the token
     * itself NEVER travels in the URL.
     */
    private String postLoginRedirect = "/login/complete";

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }

    public String getPostLoginRedirect() {
        return postLoginRedirect;
    }

    public void setPostLoginRedirect(String postLoginRedirect) {
        this.postLoginRedirect = postLoginRedirect;
    }

    public Provider provider(String registrationId) {
        Provider provider = providers.get(registrationId);
        if (provider == null) {
            throw new IllegalStateException(
                    "No OIDC provider named '" + registrationId + "' is configured.");
        }
        return provider;
    }

    /** One external identity provider. */
    public static class Provider {

        /**
         * The OIDC issuer, e.g.
         * {@code https://login.microsoftonline.com/{tenant}/v2.0} (Entra) or
         * {@code http://localhost:8081/realms/modocument} (Keycloak).
         */
        private String issuerUri;

        private String clientId;

        private String clientSecret;

        /**
         * Code of the Bank (Bank.Code) that users arriving through this
         * provider are JIT-provisioned into. Must exist — validated at
         * startup (fail fast) and again at login time.
         */
        private String bankCode;

        /** Role given to a JIT-created user when no role claim maps. */
        private Role defaultRole = Role.READ_ONLY;

        /**
         * Optional: name of an ID-token/userinfo claim carrying the
         * MoDocument role (ADMIN/APPROVER/READ_WRITE/READ_ONLY), e.g.
         * {@code modocument_roles} from the Keycloak realm's protocol
         * mapper. Absent claim or unknown values keep the existing/default
         * role.
         */
        private String roleClaim;

        /** Button label in the SPA; defaults derived from the registrationId. */
        private String displayName;

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getBankCode() {
            return bankCode;
        }

        public void setBankCode(String bankCode) {
            this.bankCode = bankCode;
        }

        public Role getDefaultRole() {
            return defaultRole;
        }

        public void setDefaultRole(Role defaultRole) {
            if (defaultRole != null && !defaultRole.isBankRole()) {
                throw new IllegalArgumentException(
                        "An OIDC provider's default-role must be a bank role, not " + defaultRole);
            }
            this.defaultRole = defaultRole;
        }

        public String getRoleClaim() {
            return roleClaim;
        }

        public void setRoleClaim(String roleClaim) {
            this.roleClaim = roleClaim;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }
}
