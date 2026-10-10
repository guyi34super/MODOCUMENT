package com.modocument.infrastructure.security.client;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.domain.entity.ApiClient;
import com.modocument.domain.entity.ApiClientSecret;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;
import com.modocument.domain.model.integration.ApiClientStatus;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.persistence.ApiClientJpaRepository;
import com.modocument.infrastructure.persistence.ApiClientSecretJpaRepository;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.ratelimit.FixedWindowRateLimiter;
import com.modocument.infrastructure.security.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * THE authentication of machine callers (ws26) — every method ends here:
 * the OAuth2 token endpoint (client secret or certificate), the
 * {@code Authorization: ApiKey <prefix>.<secret>} header, a client
 * certificate forwarded by the trusted proxy, and every request carrying an
 * issued client token ({@code RevocationValidator}'s client branch).
 *
 * <p><b>Pre-tenant lookup.</b> Authentication happens before any tenant is
 * known and ApiClient/ApiClientSecret are RLS-protected, so the lookup runs
 * through {@link #lookupAsSystem} — the ONE {@code TenantContext.runAsSystem}
 * call site of machine identity (registered in TenantContext's Javadoc): a
 * read-only REQUIRES_NEW transaction that fetches one client by ClientId,
 * KeyPrefix, certificate fingerprint or id, plus that client's secret
 * hashes, and whether the client's creator is still an enabled account of
 * its bank (one primary-key probe of AppUsers). Nothing else is read as
 * SYSTEM.
 *
 * <p><b>Every method enforces</b> (in {@link #check}): status ACTIVE, not
 * expired, the client's bank not suspended, its creator still an enabled
 * account of that bank (the leaver check: a SYSTEM client dies with the
 * user who made it — disabled or deleted; AGENT clients have no creator and
 * are exempt), the auth method still allowed
 * for the bank (platform settings {@code integration.auth.allow*}), and the
 * caller's IP inside {@code AllowedCidrs} (the forwarded-aware address of
 * {@link ClientIps}). Client tokens additionally carry {@code token_ver}:
 * a revocation bumps TokenVersion, so a token stops working on its very next
 * request.
 *
 * <p><b>Cost control.</b> Argon2id verification is deliberately expensive.
 * A verified API key is remembered by the SHA-256 of the presented key for
 * {@code api-key-cache-ttl} (the client row is still re-read and re-checked
 * on every request — only the hash comparison is skipped), and Argon2
 * verifications are rate-limited per caller IP.
 *
 * <p>Every refusal is audited {@code API_CLIENT_AUTH_FAILED} (FAILED, in its
 * own transaction) with the reason, the method and — when the client was
 * identified — its bank; the response never says which check failed.
 */
@Component
public class ApiClientAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiClientAuthenticator.class);

    public static final String AUTH_FAILED = "API_CLIENT_AUTH_FAILED";

    /** Upper bound of the API-key cache (swept of expired entries when exceeded). */
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final ApiClientJpaRepository clients;
    private final ApiClientSecretJpaRepository secrets;
    private final AppUserJpaRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SettingsReader settings;
    private final BankDirectory bankDirectory;
    private final AuditRecorder audit;
    private final IntegrationClientProperties properties;
    private final TransactionTemplate readOnlyNewTx;
    private final TransactionTemplate writeNewTx;
    private final FixedWindowRateLimiter verifyLimiter;
    private final Map<String, CachedKey> apiKeyCache = new ConcurrentHashMap<>();

    public ApiClientAuthenticator(ApiClientJpaRepository clients,
                                  ApiClientSecretJpaRepository secrets,
                                  AppUserJpaRepository users,
                                  PasswordEncoder passwordEncoder,
                                  SettingsReader settings,
                                  BankDirectory bankDirectory,
                                  AuditRecorder audit,
                                  IntegrationClientProperties properties,
                                  PlatformTransactionManager transactionManager) {
        this.clients = clients;
        this.secrets = secrets;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
        this.bankDirectory = bankDirectory;
        this.audit = audit;
        this.properties = properties;
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readOnly.setReadOnly(true);
        this.readOnlyNewTx = readOnly;
        TransactionTemplate write = new TransactionTemplate(transactionManager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeNewTx = write;
        IntegrationClientProperties.Clients config = properties.getClients();
        this.verifyLimiter = new FixedWindowRateLimiter(Math.max(1, config.getVerifyPerIpMax()),
                Math.max(1, config.getVerifyWindow().toMillis()), MAX_CACHE_ENTRIES);
    }

    // ================================================================ results

    /** Why an authentication failed (audited; never told to the caller). */
    public enum Failure {
        UNKNOWN_CLIENT, BAD_SECRET, WRONG_METHOD, REVOKED, EXPIRED, BANK_SUSPENDED,
        /** The SYSTEM client's creator is disabled, deleted or in another bank (leaver check). */
        CREATOR_INACTIVE,
        METHOD_DISABLED, IP_NOT_ALLOWED, TOKEN_REVOKED, TOKEN_MISMATCH, CERTIFICATE_MISMATCH,
        MALFORMED, RATE_LIMITED
    }

    /** Outcome of one authentication: a principal, or the refusal reason. */
    public record Result(ClientPrincipal principal, ApiClient client, Failure failure) {

        static Result ok(ClientPrincipal principal, ApiClient client) {
            return new Result(principal, client, null);
        }

        static Result failed(Failure failure) {
            return new Result(null, null, failure);
        }

        public boolean succeeded() {
            return principal != null;
        }

        /** 429 for the verification throttle, 401 for everything else. */
        public boolean rateLimited() {
            return failure == Failure.RATE_LIMITED;
        }
    }

    /**
     * The client row, its secret hashes and whether its creator is still an
     * enabled account of its bank, read together as SYSTEM.
     */
    private record Loaded(ApiClient client, List<ApiClientSecret> secrets,
                          boolean creatorActive) {
    }

    // ============================================================ the methods

    /**
     * OAuth2 client credentials with a secret (client_secret_basic /
     * client_secret_post): an OAUTH2_CLIENT_CREDENTIALS client.
     */
    public Result authenticateSecret(String clientId, String secret, HttpServletRequest request) {
        ApiAuthMethod method = ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS;
        if (blank(clientId) || blank(secret) || clientId.length() > 64 || secret.length() > 256) {
            return fail(request, method, null, Failure.MALFORMED);
        }
        if (!verifyPermit(request)) {
            return fail(request, method, null, Failure.RATE_LIMITED);
        }
        Loaded loaded = lookupAsSystem(() -> load(clients.findByClientId(clientId)));
        if (loaded == null) {
            return fail(request, method, null, Failure.UNKNOWN_CLIENT);
        }
        ApiClient client = loaded.client();
        if (client.getAuthMethod() != method) {
            return fail(request, method, client, Failure.WRONG_METHOD);
        }
        Instant now = Instant.now();
        if (matchingSecret(loaded.secrets(), secret, now) == null) {
            return fail(request, method, client, Failure.BAD_SECRET);
        }
        return check(request, method, loaded, now, null);
    }

    /**
     * {@code Authorization: ApiKey <prefix>.<secret>} — an API_KEY client.
     * A key verified within the cache TTL skips the Argon2 comparison; the
     * client is re-read and re-checked regardless.
     */
    public Result authenticateApiKey(String presentedKey, HttpServletRequest request) {
        ApiAuthMethod method = ApiAuthMethod.API_KEY;
        int dot = presentedKey == null ? -1 : presentedKey.indexOf('.');
        if (dot <= 0 || dot == presentedKey.length() - 1 || presentedKey.length() > 300) {
            return fail(request, method, null, Failure.MALFORMED);
        }
        String prefix = presentedKey.substring(0, dot);
        String secret = presentedKey.substring(dot + 1);
        Loaded loaded = lookupAsSystem(() -> load(clients.findByKeyPrefix(prefix)));
        if (loaded == null) {
            return fail(request, method, null, Failure.UNKNOWN_CLIENT);
        }
        ApiClient client = loaded.client();
        if (client.getAuthMethod() != method) {
            return fail(request, method, client, Failure.WRONG_METHOD);
        }
        Instant now = Instant.now();
        String cacheKey = sha256Hex(presentedKey);
        CachedKey cached = apiKeyCache.get(cacheKey);
        boolean verified = cached != null && cached.isLive(System.nanoTime())
                && cached.apiClientId() == client.getApiClientId()
                && loaded.secrets().stream().anyMatch(s -> s.getApiClientSecretId()
                == cached.secretId() && s.isValidAt(now));
        if (!verified) {
            if (!verifyPermit(request)) {
                return fail(request, method, client, Failure.RATE_LIMITED);
            }
            ApiClientSecret match = matchingSecret(loaded.secrets(), secret, now);
            if (match == null) {
                apiKeyCache.remove(cacheKey);
                return fail(request, method, client, Failure.BAD_SECRET);
            }
            remember(cacheKey, client.getApiClientId(), match.getApiClientSecretId());
        }
        return check(request, method, loaded, now, null);
    }

    /**
     * A client certificate the trusted proxy forwarded — an MTLS client,
     * looked up by the certificate's SHA-256 fingerprint. {@code clientIdHint}
     * (the token endpoint's optional client_id) must then name that client.
     */
    public Result authenticateCertificate(MtlsClientCertificates.PresentedCertificate certificate,
                                          String clientIdHint, HttpServletRequest request) {
        ApiAuthMethod method = ApiAuthMethod.MTLS;
        Loaded loaded = lookupAsSystem(() -> load(
                clients.findByCertFingerprintSha256(certificate.fingerprintSha256())));
        if (loaded == null) {
            return fail(request, method, null, Failure.UNKNOWN_CLIENT);
        }
        ApiClient client = loaded.client();
        if (client.getAuthMethod() != method) {
            return fail(request, method, client, Failure.WRONG_METHOD);
        }
        if (!blank(clientIdHint) && !clientIdHint.equals(client.getClientId())) {
            return fail(request, method, client, Failure.CERTIFICATE_MISMATCH);
        }
        return check(request, method, loaded, Instant.now(), null);
    }

    /**
     * The client branch of {@code RevocationValidator}: a validated
     * (signature, expiry) client token is still good only while its client
     * is — same checks as the other methods, plus TokenVersion, the token's
     * bank and scopes against the client's, and the certificate binding of
     * an MTLS-issued token ({@code cnf.x5t#S256}).
     */
    public Result validateToken(Jwt token, HttpServletRequest request,
                                MtlsClientCertificates certificates) {
        ApiAuthMethod method = ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS;
        String clientId = token.getClaimAsString(ClientTokenService.CLAIM_CLIENT_ID);
        if (blank(clientId)) {
            return fail(request, method, null, Failure.MALFORMED);
        }
        Loaded loaded = lookupAsSystem(() -> load(clients.findByClientId(clientId)));
        if (loaded == null) {
            return fail(request, method, null, Failure.UNKNOWN_CLIENT);
        }
        ApiClient client = loaded.client();
        Object bank = token.getClaims().get(ClientTokenService.CLAIM_BANK_ID);
        Object version = token.getClaims().get(ClientTokenService.CLAIM_TOKEN_VERSION);
        if (!(bank instanceof Number bankId) || bankId.intValue() != client.getBankId()) {
            return fail(request, client.getAuthMethod(), client, Failure.TOKEN_MISMATCH);
        }
        if (client.getAuthMethod() == ApiAuthMethod.API_KEY) {
            // API-key clients never receive tokens.
            return fail(request, client.getAuthMethod(), client, Failure.WRONG_METHOD);
        }
        int tokenVersion = version instanceof Number number ? number.intValue() : -1;
        if (tokenVersion < client.getTokenVersion()) {
            return fail(request, client.getAuthMethod(), client, Failure.TOKEN_REVOKED);
        }
        Set<Permission> tokenScopes = ClientTokenService.scopesOf(token);
        if (!client.scopeSet().containsAll(tokenScopes)) {
            return fail(request, client.getAuthMethod(), client, Failure.TOKEN_MISMATCH);
        }
        String boundThumbprint = ClientTokenService.certificateThumbprintOf(token);
        if (boundThumbprint != null) {
            Optional<MtlsClientCertificates.PresentedCertificate> presented =
                    request == null ? Optional.empty() : certificates.presented(request);
            if (presented.isEmpty()
                    || !ClientTokenService.x5tS256(presented.get().fingerprintSha256())
                    .equals(boundThumbprint)) {
                return fail(request, client.getAuthMethod(), client,
                        Failure.CERTIFICATE_MISMATCH);
            }
        }
        return check(request, client.getAuthMethod(), loaded, Instant.now(), tokenScopes);
    }

    // =============================================================== checks

    /**
     * The checks every method shares. {@code narrowedScopes} (a token's
     * {@code scope}) narrows the principal's scopes; null = the client's own.
     */
    private Result check(HttpServletRequest request, ApiAuthMethod method, Loaded loaded,
                         Instant now, Set<Permission> narrowedScopes) {
        ApiClient client = loaded.client();
        if (client.getStatus() != ApiClientStatus.ACTIVE) {
            return fail(request, method, client, Failure.REVOKED);
        }
        if (client.isExpired(now)) {
            return fail(request, method, client, Failure.EXPIRED);
        }
        if (bankDirectory.isSuspended(client.getBankId())) {
            return fail(request, method, client, Failure.BANK_SUSPENDED);
        }
        if (!loaded.creatorActive()) {
            return fail(request, method, client, Failure.CREATOR_INACTIVE);
        }
        if (!methodAllowed(client.getAuthMethod(), client.getBankId())) {
            return fail(request, method, client, Failure.METHOD_DISABLED);
        }
        if (!ipAllowed(client, request == null ? "" : ClientIps.clientIp(request))) {
            return fail(request, method, client, Failure.IP_NOT_ALLOWED);
        }
        Set<Permission> scopes = narrowedScopes == null ? client.scopeSet() : narrowedScopes;
        return Result.ok(principalOf(client, scopes), client);
    }

    /** Whether the bank may use {@code method} (platform settings integration.auth.allow*). */
    public boolean methodAllowed(ApiAuthMethod method, int bankId) {
        String key = switch (method) {
            case OAUTH2_CLIENT_CREDENTIALS -> SettingKeys.INTEGRATION_ALLOW_OAUTH2;
            case API_KEY -> SettingKeys.INTEGRATION_ALLOW_API_KEYS;
            case MTLS -> SettingKeys.INTEGRATION_ALLOW_MTLS;
        };
        try {
            return settings.getBool(key, bankId);
        } catch (RuntimeException unreadable) {
            log.warn("Could not read {} for bank {}; refusing the method", key, bankId);
            return false;
        }
    }

    /** AllowedCidrs: empty = any address; an unparseable entry matches nothing. */
    static boolean ipAllowed(ApiClient client, String ip) {
        List<String> cidrs = client.allowedCidrList();
        if (cidrs.isEmpty()) {
            return true;
        }
        if (ip == null || ip.isBlank()) {
            return false;
        }
        for (String cidr : cidrs) {
            try {
                if (new IpAddressMatcher(cidr).matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException unparseable) {
                // fail closed for this entry
            }
        }
        return false;
    }

    public static ClientPrincipal principalOf(ApiClient client, Set<Permission> scopes) {
        return new ClientPrincipal(client.getApiClientId(), client.getBankId(),
                client.getClientId(), client.getKind(), client.getAuthMethod(), scopes,
                client.getCreatedByUserId() == null ? 0 : client.getCreatedByUserId(),
                client.getAgentId());
    }

    /**
     * Stamps LastUsedAt (at most once per {@code last-used-resolution}). Runs
     * under the client's own tenant (bound by IdentityContextFilter by the
     * time the request reaches the integration routes).
     */
    public void touchLastUsed(ClientPrincipal principal) {
        Instant now = Instant.now();
        Instant threshold = now.minus(properties.getClients().getLastUsedResolution());
        try {
            writeNewTx.executeWithoutResult(status -> clients.touchLastUsed(
                    principal.apiClientId(), principal.bankId(), now, threshold));
        } catch (RuntimeException e) {
            log.warn("Could not stamp LastUsedAt of API client {}: {}", principal.apiClientId(),
                    e.getMessage());
        }
    }

    /**
     * Whether ANY bank already registered this certificate (the fingerprint
     * is globally unique: it is the pre-tenant lookup key). Same SYSTEM
     * lookup as authentication; answers a boolean only.
     */
    public boolean certificateRegistered(String fingerprintSha256) {
        return lookupAsSystem(() -> load(
                clients.findByCertFingerprintSha256(fingerprintSha256))) != null;
    }

    /** Drops every cached API-key verification of a client (revocation, rotation). */
    public void forget(int apiClientId) {
        apiKeyCache.values().removeIf(entry -> entry.apiClientId() == apiClientId);
    }

    // ============================================================= plumbing

    /**
     * THE pre-tenant lookup: SYSTEM, read-only, its own transaction, by one
     * credential key only (registered TenantContext.runAsSystem call site).
     */
    private Loaded lookupAsSystem(Supplier<Loaded> query) {
        try {
            return TenantContext.runAsSystem(() -> readOnlyNewTx.execute(status -> query.get()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not look up the API client.", e);
        }
    }

    /**
     * Called inside {@link #lookupAsSystem}: the client plus its secret
     * hashes and the leaver check of its creator.
     */
    private Loaded load(Optional<ApiClient> client) {
        if (client.isEmpty()) {
            return null;
        }
        ApiClient found = client.get();
        List<ApiClientSecret> hashes = found.getAuthMethod() == ApiAuthMethod.MTLS
                ? List.of() : secrets.findForClient(found.getApiClientId(), found.getBankId());
        return new Loaded(found, hashes, creatorActive(found));
    }

    /**
     * The leaver check: an AGENT client has no creator (it belongs to its
     * agent); any other client is good only while the user who created it is
     * an enabled account of the client's bank. A client without a recorded
     * creator fails closed.
     */
    private boolean creatorActive(ApiClient client) {
        if (client.getKind() == ApiClientKind.AGENT) {
            return true;
        }
        Integer creator = client.getCreatedByUserId();
        return creator != null
                && users.existsByAppUserIdAndBankIdAndDisabledFalse(creator, client.getBankId());
    }

    private ApiClientSecret matchingSecret(List<ApiClientSecret> candidates, String secret,
                                           Instant now) {
        for (ApiClientSecret candidate : candidates) {
            if (candidate.isValidAt(now) && passwordEncoder.matches(secret,
                    candidate.getSecretHash())) {
                return candidate;
            }
        }
        return null;
    }

    private boolean verifyPermit(HttpServletRequest request) {
        String ip = request == null ? "" : ClientIps.clientIp(request);
        return verifyLimiter.tryAcquire("verify:" + ip, System.currentTimeMillis());
    }

    private void remember(String cacheKey, int apiClientId, int secretId) {
        long now = System.nanoTime();
        if (apiKeyCache.size() >= MAX_CACHE_ENTRIES) {
            apiKeyCache.values().removeIf(entry -> !entry.isLive(now));
            if (apiKeyCache.size() >= MAX_CACHE_ENTRIES) {
                apiKeyCache.clear();
            }
        }
        apiKeyCache.put(cacheKey, new CachedKey(apiClientId, secretId,
                now + properties.getClients().getApiKeyCacheTtl().toNanos()));
    }

    private Result fail(HttpServletRequest request, ApiAuthMethod method, ApiClient client,
                        Failure failure) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("method", method.name());
        values.put("reason", failure.name());
        if (request != null) {
            values.put("path", request.getRequestURI());
        }
        AuditEventSpec.Builder spec = AuditEventSpec.builder(AUTH_FAILED, AuditOutcome.FAILED)
                .actorRole("API_CLIENT")
                .newValues(values);
        if (client != null) {
            spec.entity("ApiClient", String.valueOf(client.getApiClientId()))
                    .actorEmail("api-client:" + client.getClientId())
                    .bankId(client.getBankId());
        } else {
            spec.entity("ApiClient", null);
        }
        try {
            audit.record(spec.build());
        } catch (RuntimeException e) {
            log.warn("Could not audit an API client authentication failure: {}", e.getMessage());
        }
        return Result.failed(failure);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record CachedKey(int apiClientId, int secretId, long expiresAtNanos) {

        boolean isLive(long nowNanos) {
            return nowNanos - expiresAtNanos < 0;
        }
    }
}
