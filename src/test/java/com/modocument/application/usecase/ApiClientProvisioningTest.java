package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.integration.ApiClientIssuer;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.ApiClient;
import com.modocument.domain.entity.ApiClientInitialSecret;
import com.modocument.domain.entity.ApiClientSecret;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;
import com.modocument.domain.model.integration.ApiClientStatus;
import com.modocument.infrastructure.persistence.ApiClientInitialSecretJpaRepository;
import com.modocument.infrastructure.persistence.ApiClientJpaRepository;
import com.modocument.infrastructure.persistence.ApiClientSecretJpaRepository;
import com.modocument.infrastructure.security.client.ApiClientAuthenticator;
import com.modocument.infrastructure.security.client.IntegrationClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API client creation (ws26) against mocks of the ports: the secret is
 * generated at APPLY, stored only as an Argon2id hash and SEALED with the
 * SecretVault for the maker (SE1's vault is mocked — its placeholder refuses
 * on this branch); SUBMIT validates without creating anything.
 */
class ApiClientProvisioningTest {

    private static final int BANK = 7;
    private static final int MAKER = 70;

    private final ObjectMapper json = new ObjectMapper();
    private final ApiClientJpaRepository clients = mock(ApiClientJpaRepository.class);
    private final ApiClientSecretJpaRepository secrets = mock(ApiClientSecretJpaRepository.class);
    private final ApiClientInitialSecretJpaRepository initialSecrets =
            mock(ApiClientInitialSecretJpaRepository.class);
    private final SecretVault vault = mock(SecretVault.class);
    private final ApiClientAuthenticator authenticator = mock(ApiClientAuthenticator.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final PasswordEncoder encoder = new DelegatingPasswordEncoder("argon2",
            Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    private ApiClientProvisioning provisioning;

    @BeforeEach
    void setUp() {
        AtomicInteger ids = new AtomicInteger(100);
        when(clients.save(any())).thenAnswer(inv -> {
            ApiClient client = inv.getArgument(0);
            if (client.getApiClientId() == 0) {
                client.setApiClientId(ids.incrementAndGet());
            }
            return client;
        });
        when(secrets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(initialSecrets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(authenticator.methodAllowed(any(), anyInt())).thenReturn(true);
        when(currentUser.requireBankId()).thenReturn(BANK);
        when(vault.seal(anyInt(), any())).thenReturn(new SecretVault.Sealed("sealed!", 3));
        provisioning = new ApiClientProvisioning(clients, secrets, initialSecrets, encoder, vault,
                authenticator, audit, currentUser, new IntegrationClientProperties(), json);
    }

    @Test
    void submitNormalizesAndCreatesNothing() throws Exception {
        JsonNode prepared = provisioning.prepareCreate(json.readTree("""
                {"name":" Core ","authMethod":"api_key","scopes":["INTEGRATION_RESULTS",
                 "INTEGRATION_INGEST"],"allowedCidrs":["203.0.113.0/24","198.51.100.7"],
                 "expiresAt":"2027-06-30T23:59:59Z"}"""));
        assertThat(prepared.path("name").asText()).isEqualTo("Core");
        assertThat(prepared.path("authMethod").asText()).isEqualTo("API_KEY");
        assertThat(prepared.path("scopes").toString())
                .isEqualTo("[\"INTEGRATION_INGEST\",\"INTEGRATION_RESULTS\"]");
        assertThat(prepared.path("allowedCidrs").toString())
                .isEqualTo("[\"203.0.113.0/24\",\"198.51.100.7/32\"]");
        assertThat(prepared.has("certificatePem")).isFalse();
        verify(clients, never()).save(any());
        verify(vault, never()).seal(anyInt(), any());
    }

    @Test
    void aDisabledMethodIsRefusedAtSubmit() {
        when(authenticator.methodAllowed(ApiAuthMethod.API_KEY, BANK)).thenReturn(false);
        assertThatThrownBy(() -> provisioning.prepareCreate(json.readTree(
                "{\"name\":\"x\",\"authMethod\":\"API_KEY\",\"scopes\":[\"INTEGRATION_INGEST\"]}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("disabled");
    }

    @Test
    void applyHashesTheSecretAndSealsItForTheMakerOnly() {
        PendingChange change = new PendingChange();
        change.setPendingChangeId(5);
        change.setBankId(BANK);
        change.setMakerUserId(MAKER);
        change.setChangeType(ChangeType.API_CLIENT_CREATE);
        change.setPayloadJson("{\"name\":\"Core\",\"authMethod\":\"OAUTH2_CLIENT_CREDENTIALS\","
                + "\"scopes\":[\"INTEGRATION_INGEST\"],\"allowedCidrs\":[],"
                + "\"expiresAt\":\"2027-06-30T23:59:59Z\"}");

        ApiClient created = provisioning.applyCreate(change);

        assertThat(created.getBankId()).isEqualTo(BANK);
        assertThat(created.getKind()).isEqualTo(ApiClientKind.SYSTEM);
        assertThat(created.getCreatedByUserId()).isEqualTo(MAKER);
        assertThat(created.getClientId()).startsWith("mdc_");
        assertThat(change.getTargetEntityId()).isEqualTo(String.valueOf(created.getApiClientId()));

        ArgumentCaptor<SecretMaterial> sealed = ArgumentCaptor.forClass(SecretMaterial.class);
        verify(vault).seal(eq(BANK), sealed.capture());
        String plaintext = sealed.getValue().get("clientSecret");
        assertThat(plaintext).hasSize(43);

        ArgumentCaptor<ApiClientSecret> hash = ArgumentCaptor.forClass(ApiClientSecret.class);
        verify(secrets).save(hash.capture());
        assertThat(hash.getValue().getSecretHash()).startsWith("{argon2}")
                .doesNotContain(plaintext);
        assertThat(encoder.matches(plaintext, hash.getValue().getSecretHash())).isTrue();
        assertThat(hash.getValue().getExpiresAt()).isNull();

        ArgumentCaptor<ApiClientInitialSecret> delivery =
                ArgumentCaptor.forClass(ApiClientInitialSecret.class);
        verify(initialSecrets).save(delivery.capture());
        assertThat(delivery.getValue().getRecipientUserId()).isEqualTo(MAKER);
        assertThat(delivery.getValue().getSecretCiphertext()).isEqualTo("sealed!");
        assertThat(delivery.getValue().getSecretKeyVersion()).isEqualTo(3);

        ArgumentCaptor<AuditEventSpec> event = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().action()).isEqualTo("API_CLIENT_CREATED");
        assertThat(String.valueOf(event.getValue().newValues())).doesNotContain(plaintext);
    }

    @Test
    void agentClientsGetAgentConnectOnlyAndTheirSecretOnce() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AgentApiClientIssuer issuer = new AgentApiClientIssuer(provisioning, clients, currentUser,
                tx);

        ApiClientIssuer.Issued issued = issuer.issueAgentClient(BANK, 9, "dc-agent",
                ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS, null);

        assertThat(issued.secret()).hasSize(43);
        assertThat(issued.clientId()).startsWith("mdc_");
        ArgumentCaptor<ApiClient> saved = ArgumentCaptor.forClass(ApiClient.class);
        verify(clients).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(ApiClientKind.AGENT);
        assertThat(saved.getValue().scopeSet()).containsExactly(Permission.AGENT_CONNECT);
        assertThat(saved.getValue().getAgentId()).isEqualTo(9);
        assertThat(saved.getValue().getCreatedByUserId()).isNull();
        verify(vault, never()).seal(anyInt(), any());
        assertThatThrownBy(() -> issuer.issueAgentClient(BANK, 9, "x", ApiAuthMethod.API_KEY,
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokingAnAgentClientBumpsItsTokenVersionAndRefusesSystemClients() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(currentUser.requireUserId()).thenReturn(MAKER);
        AgentApiClientIssuer issuer = new AgentApiClientIssuer(provisioning, clients, currentUser,
                tx);
        ApiClient agentClient = new ApiClient();
        agentClient.setApiClientId(41);
        agentClient.setBankId(BANK);
        agentClient.setClientId("mdc_agent");
        agentClient.setKind(ApiClientKind.AGENT);
        agentClient.setStatus(ApiClientStatus.ACTIVE);
        agentClient.setTokenVersion(2);
        ApiClient systemClient = new ApiClient();
        systemClient.setApiClientId(42);
        systemClient.setBankId(BANK);
        systemClient.setKind(ApiClientKind.SYSTEM);
        systemClient.setStatus(ApiClientStatus.ACTIVE);
        when(clients.findByApiClientIdAndBankId(41, BANK)).thenReturn(Optional.of(agentClient));
        when(clients.findByApiClientIdAndBankId(42, BANK)).thenReturn(Optional.of(systemClient));

        issuer.revokeAgentClient(BANK, 41);
        issuer.revokeAgentClient(BANK, 41); // idempotent

        assertThat(agentClient.getStatus()).isEqualTo(ApiClientStatus.REVOKED);
        assertThat(agentClient.getTokenVersion()).isEqualTo(3);
        assertThat(agentClient.getRevokedByUserId()).isEqualTo(MAKER);
        ArgumentCaptor<AuditEventSpec> event = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().action()).isEqualTo("API_CLIENT_REVOKED");
        assertThat(String.valueOf(event.getValue().newValues())).contains("AGENT_REVOKED");
        verify(authenticator, org.mockito.Mockito.times(2)).forget(41);

        assertThatThrownBy(() -> issuer.revokeAgentClient(BANK, 42))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(systemClient.getStatus()).isEqualTo(ApiClientStatus.ACTIVE);
    }
}
