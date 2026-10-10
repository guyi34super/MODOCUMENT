package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.dto.ApiClientRequest;
import com.modocument.application.dto.ApiClientSecretView;
import com.modocument.application.dto.ApiClientView;
import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PendingChangeRepository;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.ApiClient;
import com.modocument.domain.entity.ApiClientInitialSecret;
import com.modocument.domain.entity.ApiClientSecret;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.PendingChangeStatus;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientStatus;
import com.modocument.infrastructure.persistence.ApiClientInitialSecretJpaRepository;
import com.modocument.infrastructure.persistence.ApiClientJpaRepository;
import com.modocument.infrastructure.persistence.ApiClientSecretJpaRepository;
import com.modocument.infrastructure.security.client.ApiClientAuthenticator;
import com.modocument.infrastructure.security.client.ClientCredentials;
import com.modocument.infrastructure.security.client.IntegrationClientProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Bank-side management of API clients (ws26, INTEGRATION_ADMIN, contracts
 * §7.3): list, request a new client (maker/checker API_CLIENT_CREATE), read
 * the one-time initial secret (maker only, once, within 24 h), rotate (new
 * secret; the previous one keeps working for the overlap) and revoke (direct
 * — the lower-risk direction — and immediate: status REVOKED plus a
 * TokenVersion bump, so issued tokens die on their next request).
 *
 * <p>Everything is scoped to the caller's bank: another bank's client id is
 * a 404, never a 403.
 */
@Service
public class ApiClientAdminUseCase {

    public static final String ROTATED = "API_CLIENT_ROTATED";
    public static final String REVOKED = "API_CLIENT_REVOKED";
    public static final String INITIAL_SECRET_READ = "API_CLIENT_INITIAL_SECRET_READ";

    private final ApiClientJpaRepository clients;
    private final ApiClientSecretJpaRepository secrets;
    private final ApiClientInitialSecretJpaRepository initialSecrets;
    private final PendingChangeRepository changes;
    private final PendingChangeUseCase pendingChanges;
    private final ApiClientProvisioning provisioning;
    private final ApiClientAuthenticator authenticator;
    private final SecretVault vault;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;
    private final AuditRecorder audit;
    private final IntegrationClientProperties properties;
    private final ObjectMapper objectMapper;

    public ApiClientAdminUseCase(ApiClientJpaRepository clients,
                                 ApiClientSecretJpaRepository secrets,
                                 ApiClientInitialSecretJpaRepository initialSecrets,
                                 PendingChangeRepository changes,
                                 PendingChangeUseCase pendingChanges,
                                 ApiClientProvisioning provisioning,
                                 ApiClientAuthenticator authenticator,
                                 SecretVault vault,
                                 AppUserRepository users,
                                 CurrentUserProvider currentUser,
                                 AuditRecorder audit,
                                 IntegrationClientProperties properties,
                                 ObjectMapper objectMapper) {
        this.clients = clients;
        this.secrets = secrets;
        this.initialSecrets = initialSecrets;
        this.changes = changes;
        this.pendingChanges = pendingChanges;
        this.provisioning = provisioning;
        this.authenticator = authenticator;
        this.vault = vault;
        this.users = users;
        this.currentUser = currentUser;
        this.audit = audit;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * The bank's clients (SYSTEM and AGENT, newest first), preceded by the
     * create requests still waiting for a checker (status PENDING).
     */
    @Transactional(readOnly = true)
    public List<ApiClientView> list() {
        int bankId = currentUser.requireBankId();
        int callerId = currentUser.requireUserId();
        Instant now = Instant.now();
        Set<Integer> deliverable = new HashSet<>(
                initialSecrets.findDeliverable(bankId, callerId, now));
        Map<Integer, String> emails = new HashMap<>();
        List<ApiClientView> views = new ArrayList<>();
        for (PendingChange change : changes.listByBankAndStatus(bankId,
                PendingChangeStatus.REQUESTED)) {
            if (change.getChangeType() == ChangeType.API_CLIENT_CREATE) {
                views.add(pendingView(change, emails));
            }
        }
        for (ApiClient client : clients.findByBank(bankId)) {
            views.add(view(client, now, deliverable.contains(client.getApiClientId()), emails));
        }
        return views;
    }

    /** Maker side: queues API_CLIENT_CREATE (validated now, applied by a checker). */
    @Transactional
    public PendingChangeDto requestCreate(ApiClientRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", request.name());
        payload.put("authMethod", request.authMethod());
        payload.set("scopes", objectMapper.valueToTree(
                request.scopes() == null ? List.of() : request.scopes()));
        payload.set("allowedCidrs", objectMapper.valueToTree(
                request.allowedCidrs() == null ? List.of() : request.allowedCidrs()));
        payload.put("expiresAt", request.expiresAt());
        payload.put("certificatePem", request.certificatePem());
        return pendingChanges.submit(ChangeType.API_CLIENT_CREATE, payload, request.comment());
    }

    /**
     * The approved client's secret, for its MAKER, once: decrypted, returned
     * and deleted in one transaction. Anyone else, a second read, or a read
     * after 24 h is a 404.
     */
    @Transactional
    public ApiClientSecretView readInitialSecret(int apiClientId) {
        int bankId = currentUser.requireBankId();
        int callerId = currentUser.requireUserId();
        Instant now = Instant.now();
        initialSecrets.deleteExpired(bankId, now);
        ApiClient client = requireClient(apiClientId, bankId);
        ApiClientInitialSecret row = initialSecrets.findByApiClientIdAndBankId(apiClientId, bankId)
                .filter(found -> found.getRecipientUserId() == callerId)
                .filter(found -> found.getExpiresAt().isAfter(now))
                .orElseThrow(() -> new NoSuchElementException(
                        "No secret is waiting for you for API client " + apiClientId + "."));
        SecretMaterial material = vault.open(bankId,
                new SecretVault.Sealed(row.getSecretCiphertext(), row.getSecretKeyVersion()));
        String secret = material.get("clientSecret");
        initialSecrets.delete(row);
        if (secret == null) {
            throw new IllegalStateException("The sealed secret of API client " + apiClientId
                    + " is unreadable; rotate the client to issue a new one.");
        }
        audit.record(AuditEventSpec.builder(INITIAL_SECRET_READ, AuditOutcome.SUCCESS)
                .entity("ApiClient", String.valueOf(apiClientId))
                .newValues(Map.of("clientId", client.getClientId()))
                .build());
        return new ApiClientSecretView(client.getClientId(), secret);
    }

    /**
     * A new secret, shown once; every older secret still valid keeps working
     * until now + overlap (24 h). Certificate clients rotate by registering
     * a new client with the new certificate.
     */
    @Transactional
    public ApiClientSecretView rotate(int apiClientId) {
        int bankId = currentUser.requireBankId();
        ApiClient client = requireClient(apiClientId, bankId);
        if (client.getStatus() != ApiClientStatus.ACTIVE) {
            throw new IllegalStateException("API client " + apiClientId + " is revoked.");
        }
        if (client.getAuthMethod() == ApiAuthMethod.MTLS) {
            throw new IllegalStateException("A certificate client has no secret to rotate; "
                    + "register a new client with the new certificate, then revoke this one.");
        }
        Instant now = Instant.now();
        Instant overlapEnd = now.plus(properties.getClients().getSecretOverlap());
        for (ApiClientSecret existing : secrets.findForClient(apiClientId, bankId)) {
            if (existing.getExpiresAt() == null || existing.getExpiresAt().isAfter(overlapEnd)) {
                existing.setExpiresAt(overlapEnd);
                secrets.save(existing);
            }
        }
        String secret = ClientCredentials.newSecret();
        provisioning.storeSecret(client, secret, now);
        // A not-yet-read initial secret is superseded (it expires in the overlap).
        initialSecrets.findByApiClientIdAndBankId(apiClientId, bankId)
                .ifPresent(initialSecrets::delete);
        authenticator.forget(apiClientId);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("clientId", client.getClientId());
        values.put("previousSecretsValidUntil", overlapEnd.toString());
        audit.record(AuditEventSpec.builder(ROTATED, AuditOutcome.SUCCESS)
                .entity("ApiClient", String.valueOf(apiClientId))
                .newValues(values)
                .build());
        String deliverable = client.getAuthMethod() == ApiAuthMethod.API_KEY
                ? ClientCredentials.apiKey(client.getKeyPrefix(), secret) : secret;
        return new ApiClientSecretView(client.getClientId(), deliverable);
    }

    /**
     * Immediate and direct: REVOKED + TokenVersion bump (every issued token
     * fails its next request), the unread initial secret deleted, cached
     * API-key verifications dropped. Revoking twice is a no-op.
     */
    @Transactional
    public ApiClientView revoke(int apiClientId) {
        int bankId = currentUser.requireBankId();
        int callerId = currentUser.requireUserId();
        ApiClient client = requireClient(apiClientId, bankId);
        Instant now = Instant.now();
        if (client.getStatus() != ApiClientStatus.REVOKED) {
            client.setStatus(ApiClientStatus.REVOKED);
            client.setTokenVersion(client.getTokenVersion() + 1);
            client.setRevokedAt(now);
            client.setRevokedByUserId(callerId);
            client = clients.save(client);
            initialSecrets.findByApiClientIdAndBankId(apiClientId, bankId)
                    .ifPresent(initialSecrets::delete);
            audit.record(AuditEventSpec.builder(REVOKED, AuditOutcome.SUCCESS)
                    .entity("ApiClient", String.valueOf(apiClientId))
                    .newValues(Map.of("clientId", client.getClientId(),
                            "kind", client.getKind().name()))
                    .build());
        }
        authenticator.forget(apiClientId);
        return view(client, now, false, new HashMap<>());
    }

    // ------------------------------------------------------------ plumbing

    /** Another bank's client answers exactly like a missing id. */
    private ApiClient requireClient(int apiClientId, int bankId) {
        return clients.findByApiClientIdAndBankId(apiClientId, bankId)
                .orElseThrow(() -> new NoSuchElementException(
                        "API client " + apiClientId + " not found."));
    }

    private ApiClientView view(ApiClient client, Instant now, boolean secretWaiting,
                               Map<Integer, String> emails) {
        String status = client.getStatus() == ApiClientStatus.REVOKED ? "REVOKED"
                : client.isExpired(now) ? "EXPIRED" : "ACTIVE";
        Integer createdBy = client.getCreatedByUserId();
        return new ApiClientView(client.getApiClientId(), client.getName(),
                client.getKind().name(), client.getAuthMethod().name(), client.getClientId(),
                client.getKeyPrefix(), client.getCertFingerprintSha256(), client.getCertSubject(),
                client.scopeSet().stream().map(Enum::name).sorted().toList(),
                client.allowedCidrList(), client.getExpiresAt(), status, client.getLastUsedAt(),
                createdBy, createdBy == null ? null : emailOf(createdBy, emails), null,
                secretWaiting);
    }

    private ApiClientView pendingView(PendingChange change, Map<Integer, String> emails) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        List<String> scopes = new ArrayList<>();
        payload.path("scopes").forEach(node -> scopes.add(node.asText()));
        List<String> cidrs = new ArrayList<>();
        payload.path("allowedCidrs").forEach(node -> cidrs.add(node.asText()));
        Instant expiresAt = payload.hasNonNull("expiresAt")
                ? Instant.parse(payload.get("expiresAt").asText()) : null;
        return new ApiClientView(null, payload.path("name").asText(null), "SYSTEM",
                payload.path("authMethod").asText(null), null, null,
                payload.path("certFingerprint").asText(null),
                payload.path("certSubject").asText(null), scopes, cidrs, expiresAt, "PENDING",
                null, change.getMakerUserId(), emailOf(change.getMakerUserId(), emails),
                change.getPendingChangeId(), false);
    }

    private String emailOf(int userId, Map<Integer, String> cache) {
        return cache.computeIfAbsent(userId,
                id -> users.getById(id).map(AppUser::getEmail).orElse(null));
    }
}
