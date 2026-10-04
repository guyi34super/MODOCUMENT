package com.modocument.infrastructure.security.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the open-source login path's realm definition (ws07, gate B2/B3):
 * docker/keycloak/modocument-realm.json must stay parseable and keep the
 * security posture it ships with — the password policy, the ENFORCED TOTP
 * enrollment (B3), the confidential modocument-app client and the role
 * vocabulary the role-claim mapping consumes. A hand edit that drops any of
 * these fails here by name, not at the next realm import.
 */
class KeycloakRealmConfigTest {

    /** Surefire runs from the module root, where docker/ lives. */
    private static final Path REALM_FILE =
            Path.of("docker", "keycloak", "modocument-realm.json");

    private static JsonNode realm;

    @BeforeAll
    static void parse() throws Exception {
        assertThat(REALM_FILE).exists();
        realm = new ObjectMapper().readTree(Files.readString(REALM_FILE));
    }

    @Test
    void realmIsTheEnabledModocumentRealm() {
        assertThat(realm.path("realm").asText()).isEqualTo("modocument");
        assertThat(realm.path("enabled").asBoolean()).isTrue();
        assertThat(realm.path("registrationAllowed").asBoolean()).isFalse();
    }

    @Test
    void passwordPolicyRequiresTwelvePlusCharsNotUsernameNotEmail() {
        String policy = realm.path("passwordPolicy").asText();
        assertThat(policy).contains("length(12)");
        assertThat(policy).contains("notUsername");
        assertThat(policy).contains("notEmail");
    }

    @Test
    void totpIsSixDigitsThirtySecondsAndEnrollmentIsADefaultRequiredAction() {
        assertThat(realm.path("otpPolicyType").asText()).isEqualTo("totp");
        assertThat(realm.path("otpPolicyDigits").asInt()).isEqualTo(6);
        assertThat(realm.path("otpPolicyPeriod").asInt()).isEqualTo(30);

        JsonNode configureTotp = null;
        for (JsonNode action : realm.path("requiredActions")) {
            if ("CONFIGURE_TOTP".equals(action.path("providerId").asText())) {
                configureTotp = action;
            }
        }
        assertThat(configureTotp)
                .as("the Configure OTP required action must be present")
                .isNotNull();
        assertThat(configureTotp.path("enabled").asBoolean()).isTrue();
        // defaultAction=true is the B3 enforcement: EVERY user must enroll
        // TOTP on first login — MFA is not optional on this path.
        assertThat(configureTotp.path("defaultAction").asBoolean()).isTrue();
    }

    @Test
    void modocumentAppClientIsConfidentialAuthorizationCodeWithTheRightCallbacks() {
        JsonNode client = findClient();
        assertThat(client.path("publicClient").asBoolean())
                .as("confidential client (secret-based)")
                .isFalse();
        assertThat(client.path("standardFlowEnabled").asBoolean())
                .as("authorization-code flow")
                .isTrue();
        assertThat(client.path("implicitFlowEnabled").asBoolean()).isFalse();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean())
                .as("no password grant — the browser must go through the IdP (and its MFA)")
                .isFalse();

        List<String> redirects = new ArrayList<>();
        client.path("redirectUris").forEach(uri -> redirects.add(uri.asText()));
        assertThat(redirects)
                .contains("http://localhost:8080/login/oauth2/code/keycloak",
                        "http://localhost:3000/login/oauth2/code/keycloak");
    }

    @Test
    void realmRolesMatchTheApplicationVocabularyAndAreExposedInTheClaim() {
        List<String> roles = new ArrayList<>();
        realm.path("roles").path("realm").forEach(role -> roles.add(role.path("name").asText()));
        assertThat(roles).containsExactlyInAnyOrder(
                "ADMIN", "APPROVER", "READ_WRITE", "READ_ONLY");

        JsonNode mapper = null;
        for (JsonNode candidate : findClient().path("protocolMappers")) {
            if ("oidc-usermodel-realm-role-mapper"
                    .equals(candidate.path("protocolMapper").asText())) {
                mapper = candidate;
            }
        }
        assertThat(mapper)
                .as("realm-role protocol mapper feeding the app's role-claim mapping")
                .isNotNull();
        assertThat(mapper.path("config").path("claim.name").asText())
                .isEqualTo("modocument_roles");
        assertThat(mapper.path("config").path("id.token.claim").asText()).isEqualTo("true");
        assertThat(mapper.path("config").path("multivalued").asText()).isEqualTo("true");
    }

    @Test
    void theOnlyShippedUserIsDisabled() {
        JsonNode users = realm.path("users");
        assertThat(users.size()).isEqualTo(1);
        assertThat(users.get(0).path("enabled").asBoolean())
                .as("the template user must never be a live account")
                .isFalse();
    }

    private static JsonNode findClient() {
        for (JsonNode client : realm.path("clients")) {
            if ("modocument-app".equals(client.path("clientId").asText())) {
                return client;
            }
        }
        throw new AssertionError("client 'modocument-app' missing from the realm");
    }
}
