package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.ApiClient;
import com.modocument.domain.entity.ApiClientInitialSecret;
import com.modocument.domain.entity.ApiClientSecret;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;
import com.modocument.domain.model.integration.ApiClientStatus;
import com.modocument.infrastructure.persistence.ApiClientInitialSecretJpaRepository;
import com.modocument.infrastructure.persistence.ApiClientJpaRepository;
import com.modocument.infrastructure.persistence.ApiClientSecretJpaRepository;
import com.modocument.infrastructure.security.client.ApiClientAuthenticator;
import com.modocument.infrastructure.security.client.Cidrs;
import com.modocument.infrastructure.security.client.ClientCredentials;
import com.modocument.infrastructure.security.client.IntegrationClientProperties;
import com.modocument.infrastructure.security.client.MtlsClientCertificates;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creating API clients (ws26) — the one place a client and its credentials
 * come into existence, shared by the {@code API_CLIENT_CREATE} applier
 * (SYSTEM clients, after a second admin approved) and
 * {@code ApiClientIssuer} (AGENT clients, at enrolment).
 *
 * <p>Credentials are generated HERE, at apply time, never at submit: the
 * maker's request carries no secret. The plaintext secret is hashed with the
 * project's Argon2id {@link PasswordEncoder}; for a maker-created client it
 * is additionally sealed with the {@link SecretVault} (per-bank key) into
 * ApiClientInitialSecret, which the maker can read ONCE within 24 h.
 */
@Component
public class ApiClientProvisioning {

    /** What a SYSTEM client may be granted (AGENT clients get AGENT_CONNECT only). */
    public static final Set<Permission> SYSTEM_SCOPES = Collections.unmodifiableSet(EnumSet.of(
            Permission.INTEGRATION_INGEST, Permission.INTEGRATION_RESULTS,
            Permission.INTEGRATION_DOCS, Permission.INTEGRATION_EVENTS));

    public static final Set<Permission> AGENT_SCOPES =
            Collections.unmodifiableSet(EnumSet.of(Permission.AGENT_CONNECT));

    public static final String CREATED = "API_CLIENT_CREATED";
    public static final String REVOKED = "API_CLIENT_REVOKED";

    private static final int MAX_CIDRS = 20;

    private final ApiClientJpaRepository clients;
    private final ApiClientSecretJpaRepository secrets;
    private final ApiClientInitialSecretJpaRepository initialSecrets;
    private final PasswordEncoder passwordEncoder;
    private final SecretVault vault;
    private final ApiClientAuthenticator authenticator;
    private final AuditRecorder audit;
    private final CurrentUserProvider currentUser;
    private final IntegrationClientProperties properties;
    private final ObjectMapper objectMapper;

    public ApiClientProvisioning(ApiClientJpaRepository clients,
                                 ApiClientSecretJpaRepository secrets,
                                 ApiClientInitialSecretJpaRepository initialSecrets,
                                 PasswordEncoder passwordEncoder,
                                 SecretVault vault,
                                 ApiClientAuthenticator authenticator,
                                 AuditRecorder audit,
                                 CurrentUserProvider currentUser,
                                 IntegrationClientProperties properties,
                                 ObjectMapper objectMapper) {
        this.clients = clients;
        this.secrets = secrets;
        this.initialSecrets = initialSecrets;
        this.passwordEncoder = passwordEncoder;
        this.vault = vault;
        this.authenticator = authenticator;
        this.audit = audit;
        this.currentUser = currentUser;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** A new client and its plaintext secret (null for MTLS) — shown once, never stored. */
    public record Created(ApiClient client, String deliverableSecret) {

        @Override
        public String toString() {
            return "Created[apiClientId=" + client.getApiClientId() + "]";
        }
    }

    // ============================================================ the request

    /**
     * SUBMIT side of API_CLIENT_CREATE (as the maker): validates the request
     * and returns the payload to persist —
     * {@code {name, authMethod, scopes, allowedCidrs, expiresAt, certFingerprint?, certSubject?}}.
     * The certificate itself is not kept; its fingerprint and subject are.
     */
    public JsonNode prepareCreate(JsonNode request) {
        int bankId = currentUser.requireBankId();
        String name = requireName(request.path("name").asText(null));
        ApiAuthMethod method = parseMethod(request.path("authMethod").asText(null));
        requireMethodAllowed(method, bankId);
        Set<Permission> scopes = parseScopes(request.get("scopes"));
        List<String> cidrs = parseCidrs(request.get("allowedCidrs"));
        Instant expiresAt = parseExpiry(request.path("expiresAt").asText(null));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", name);
        payload.put("authMethod", method.name());
        ArrayNode scopeArray = payload.putArray("scopes");
        scopes.stream().map(Permission::name).sorted().forEach(scopeArray::add);
        ArrayNode cidrArray = payload.putArray("allowedCidrs");
        cidrs.forEach(cidrArray::add);
        payload.put("expiresAt", expiresAt.toString());

        String pem = request.path("certificatePem").asText(null);
        if (method == ApiAuthMethod.MTLS) {
            X509Certificate certificate = parseCertificate(pem);
            String fingerprint = fingerprint(certificate);
            requireCertificateFree(fingerprint);
            payload.put("certFingerprint", fingerprint);
            payload.put("certSubject", truncate(
                    certificate.getSubjectX500Principal().getName(), ApiClient.CERT_SUBJECT_MAX));
        } else if (pem != null && !pem.isBlank()) {
            throw new IllegalArgumentException(
                    "A certificate is only accepted for the MTLS authentication method.");
        }
        return payload;
    }

    /**
     * APPLY side (as the checker, inside the approval transaction): re-checks
     * what may have changed since submit (method still allowed, certificate
     * still unregistered), creates the client owned by the MAKER, seals its
     * secret for the maker's one-time read, audits API_CLIENT_CREATED and
     * points the change at the new client.
     */
    public ApiClient applyCreate(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        int bankId = change.getBankId();
        ApiAuthMethod method = parseMethod(payload.path("authMethod").asText(null));
        requireMethodAllowed(method, bankId);
        Set<Permission> scopes = parseScopes(payload.get("scopes"));
        List<String> cidrs = parseCidrs(payload.get("allowedCidrs"));
        Instant expiresAt = Instant.parse(payload.path("expiresAt").asText());
        String fingerprint = null;
        String subject = null;
        if (method == ApiAuthMethod.MTLS) {
            fingerprint = payload.path("certFingerprint").asText(null);
            subject = payload.path("certSubject").asText(null);
            if (fingerprint == null || fingerprint.isBlank()) {
                throw new IllegalArgumentException("The MTLS request carries no certificate.");
            }
            requireCertificateFree(fingerprint);
        }
        Created created = createClient(bankId, ApiClientKind.SYSTEM,
                requireName(payload.path("name").asText(null)), method, scopes, cidrs, expiresAt,
                fingerprint, subject, change.getMakerUserId(), null,
                change.getPendingChangeId());
        if (created.deliverableSecret() != null) {
            sealForMaker(created.client(), change.getMakerUserId(), created.deliverableSecret());
        }
        change.setTargetEntityId(String.valueOf(created.client().getApiClientId()));
        return created.client();
    }

    // ============================================================ revocation

    /**
     * Revokes a client, immediately: REVOKED + TokenVersion bump (every
     * issued token fails its next request), the unread initial secret
     * deleted, cached API-key verifications dropped, audited
     * API_CLIENT_REVOKED. Revoking twice is a no-op. Runs in the caller's
     * transaction and tenant; the caller has already scoped {@code client}
     * to its bank.
     *
     * @param revokedByUserId who revoked it (null when no user is known)
     * @param via             why, when not the client's own revoke (e.g. AGENT_REVOKED)
     * @return the (saved) client
     */
    public ApiClient revoke(ApiClient client, Integer revokedByUserId, String via) {
        if (client.getStatus() != ApiClientStatus.REVOKED) {
            client.setStatus(ApiClientStatus.REVOKED);
            client.setTokenVersion(client.getTokenVersion() + 1);
            client.setRevokedAt(Instant.now());
            client.setRevokedByUserId(revokedByUserId);
            client = clients.save(client);
            initialSecrets.findByApiClientIdAndBankId(client.getApiClientId(), client.getBankId())
                    .ifPresent(initialSecrets::delete);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("clientId", client.getClientId());
            values.put("kind", client.getKind().name());
            if (via != null) {
                values.put("via", via);
            }
            audit.record(AuditEventSpec.builder(REVOKED, AuditOutcome.SUCCESS)
                    .bankId(client.getBankId())
                    .entity("ApiClient", String.valueOf(client.getApiClientId()))
                    .newValues(values)
                    .build());
        }
        authenticator.forget(client.getApiClientId());
        return client;
    }

    // ============================================================ creation

    /**
     * Inserts the client and (except MTLS) its first secret, and audits
     * API_CLIENT_CREATED. Runs in the caller's transaction and tenant.
     */
    public Created createClient(int bankId, ApiClientKind kind, String name, ApiAuthMethod method,
                                Set<Permission> scopes, List<String> cidrs, Instant expiresAt,
                                String certFingerprint, String certSubject,
                                Integer createdByUserId, Integer agentId,
                                Integer pendingChangeId) {
        Instant now = Instant.now();
        ApiClient client = new ApiClient();
        client.setBankId(bankId);
        client.setName(name);
        client.setKind(kind);
        client.setAuthMethod(method);
        client.setClientId(uniqueClientId());
        client.setKeyPrefix(method == ApiAuthMethod.API_KEY ? uniqueKeyPrefix() : null);
        client.setCertFingerprintSha256(method == ApiAuthMethod.MTLS ? certFingerprint : null);
        client.setCertSubject(method == ApiAuthMethod.MTLS ? certSubject : null);
        client.setScopeSet(scopes);
        client.setAllowedCidrList(cidrs);
        client.setExpiresAt(expiresAt);
        client.setStatus(ApiClientStatus.ACTIVE);
        client.setTokenVersion(0);
        client.setCreatedByUserId(createdByUserId);
        client.setCreatedAt(now);
        client.setAgentId(agentId);
        ApiClient saved = clients.save(client);

        String deliverable = null;
        if (method != ApiAuthMethod.MTLS) {
            String secret = ClientCredentials.newSecret();
            storeSecret(saved, secret, now);
            deliverable = method == ApiAuthMethod.API_KEY
                    ? ClientCredentials.apiKey(saved.getKeyPrefix(), secret) : secret;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("apiClientId", saved.getApiClientId());
        values.put("clientId", saved.getClientId());
        values.put("name", saved.getName());
        values.put("kind", kind.name());
        values.put("authMethod", method.name());
        values.put("scopes", saved.getScopes());
        values.put("allowedCidrs", saved.getAllowedCidrs());
        values.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        if (agentId != null) {
            values.put("agentId", agentId);
        }
        if (pendingChangeId != null) {
            values.put("pendingChangeId", pendingChangeId);
        }
        audit.record(AuditEventSpec.builder(CREATED, AuditOutcome.SUCCESS)
                .entity("ApiClient", String.valueOf(saved.getApiClientId()))
                .bankId(bankId)
                .newValues(values)
                .build());
        return new Created(saved, deliverable);
    }

    /** Hashes and stores a new current secret of {@code client}. */
    public ApiClientSecret storeSecret(ApiClient client, String secret, Instant now) {
        ApiClientSecret row = new ApiClientSecret();
        row.setBankId(client.getBankId());
        row.setApiClientId(client.getApiClientId());
        row.setSecretHash(passwordEncoder.encode(secret));
        row.setCreatedAt(now);
        row.setExpiresAt(null);
        return secrets.save(row);
    }

    private void sealForMaker(ApiClient client, int makerUserId, String secret) {
        SecretVault.Sealed sealed = vault.seal(client.getBankId(),
                new SecretMaterial(Map.of("clientSecret", secret)));
        Instant now = Instant.now();
        ApiClientInitialSecret row = new ApiClientInitialSecret();
        row.setApiClientId(client.getApiClientId());
        row.setBankId(client.getBankId());
        row.setRecipientUserId(makerUserId);
        row.setSecretCiphertext(sealed.ciphertext());
        row.setSecretKeyVersion(sealed.keyVersion());
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(properties.getClients().getInitialSecretTtl()));
        initialSecrets.save(row);
    }

    // ============================================================ validation

    public void requireMethodAllowed(ApiAuthMethod method, int bankId) {
        if (!authenticator.methodAllowed(method, bankId)) {
            throw new IllegalArgumentException("The " + method.name()
                    + " authentication method is disabled for this bank by the platform.");
        }
    }

    /** A certificate maps to one client across ALL banks (the pre-tenant key). */
    public void requireCertificateFree(String fingerprint) {
        if (authenticator.certificateRegistered(fingerprint)) {
            throw new IllegalStateException("This certificate is already registered to an API "
                    + "client.");
        }
    }

    public static X509Certificate parseCertificate(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException(
                    "The MTLS authentication method needs the client certificate (PEM).");
        }
        try {
            X509Certificate certificate = MtlsClientCertificates.parsePem(pem);
            certificate.checkValidity();
            return certificate;
        } catch (CertificateException e) {
            throw new IllegalArgumentException("The client certificate is not a valid, current "
                    + "X.509 certificate in PEM format.");
        }
    }

    public static String fingerprint(X509Certificate certificate) {
        try {
            return MtlsClientCertificates.fingerprint(certificate);
        } catch (CertificateException e) {
            throw new IllegalArgumentException("The client certificate cannot be encoded.");
        }
    }

    static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A client name is required.");
        }
        String name = raw.trim();
        if (name.length() > ApiClient.NAME_MAX) {
            throw new IllegalArgumentException("The client name must be at most "
                    + ApiClient.NAME_MAX + " characters.");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The client name contains control characters.");
        }
        return name;
    }

    public static ApiAuthMethod parseMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("authMethod is required: "
                    + "OAUTH2_CLIENT_CREDENTIALS, API_KEY or MTLS.");
        }
        try {
            return ApiAuthMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown authMethod '" + raw
                    + "'; use OAUTH2_CLIENT_CREDENTIALS, API_KEY or MTLS.");
        }
    }

    static Set<Permission> parseScopes(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("At least one scope is required: "
                    + "INTEGRATION_INGEST, INTEGRATION_RESULTS, INTEGRATION_DOCS, "
                    + "INTEGRATION_EVENTS.");
        }
        Set<Permission> scopes = EnumSet.noneOf(Permission.class);
        for (JsonNode item : node) {
            String raw = item.asText("").trim().toUpperCase(Locale.ROOT);
            Permission permission;
            try {
                permission = Permission.valueOf(raw);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown scope '" + item.asText() + "'.");
            }
            if (!SYSTEM_SCOPES.contains(permission)) {
                throw new IllegalArgumentException("Scope " + permission.name()
                        + " cannot be granted to a bank system client.");
            }
            scopes.add(permission);
        }
        return scopes;
    }

    static List<String> parseCidrs(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("allowedCidrs must be a list.");
        }
        if (node.size() > MAX_CIDRS) {
            throw new IllegalArgumentException("At most " + MAX_CIDRS + " allowed CIDRs.");
        }
        Set<String> cidrs = new LinkedHashSet<>();
        for (JsonNode item : node) {
            cidrs.add(Cidrs.normalize(item.asText(null)));
        }
        return new ArrayList<>(cidrs);
    }

    Instant parseExpiry(String raw) {
        Instant now = Instant.now();
        if (raw == null || raw.isBlank()) {
            return now.plus(properties.getClients().getDefaultExpiry());
        }
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt must be an ISO-8601 UTC timestamp, "
                    + "e.g. 2027-06-30T00:00:00Z.");
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future.");
        }
        if (expiresAt.isAfter(now.plus(properties.getClients().getMaxExpiry()))) {
            throw new IllegalArgumentException("expiresAt is too far in the future (at most "
                    + properties.getClients().getMaxExpiry().toDays() + " days).");
        }
        return expiresAt;
    }

    private String uniqueClientId() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = ClientCredentials.newClientId();
            if (!clients.existsByClientId(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique client id.");
    }

    private String uniqueKeyPrefix() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = ClientCredentials.newKeyPrefix();
            if (!clients.existsByKeyPrefix(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique key prefix.");
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
