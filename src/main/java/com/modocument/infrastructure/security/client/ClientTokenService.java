package com.modocument.infrastructure.security.client;

import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.domain.entity.ApiClient;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.settings.IntegrationSettings;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues the OAuth2 client-credentials access token (ws26): an HS256 JWT
 * signed with the SAME key as user access tokens (one JwtDecoder validates
 * both), kept mutually unusable by the {@code typ} claim:
 * <ul>
 *   <li>a client token carries {@code typ=client} and NO {@code roles}
 *       claim; {@code RevocationValidator} sends it down the client branch,
 *       the JWT converter turns it into a {@link ClientAuthentication}
 *       (never a user principal), and SecurityConfig lets it reach the
 *       integration/agent routes only;</li>
 *   <li>a user token has no {@code typ} and is refused on those routes.</li>
 * </ul>
 * Claims: {@code sub} = {@code client_id}, {@code client_id}, {@code bank_id},
 * {@code scope} (space-separated permission names), {@code token_ver},
 * {@code jti}, plus {@code api_client_id}, {@code kind}, {@code auth_method},
 * {@code owner_user_id} and {@code agent_id} (the converter builds the
 * principal from these without another lookup) and,
 * for a certificate-authenticated client, the RFC 8705 binding
 * {@code cnf.x5t#S256} (base64url SHA-256 of the certificate) — such a token is
 * accepted only together with the same certificate.
 *
 * <p>Lifetime: the bank's {@code integration.clientTokenTtl} (5–15 min).
 */
@Service
public class ClientTokenService {

    public static final String TYPE_CLIENT = "client";
    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_CLIENT_ID = "client_id";
    public static final String CLAIM_BANK_ID = JwtService.CLAIM_BANK_ID;
    public static final String CLAIM_TOKEN_VERSION = JwtService.CLAIM_TOKEN_VERSION;
    public static final String CLAIM_SCOPE = "scope";
    public static final String CLAIM_API_CLIENT_ID = "api_client_id";
    public static final String CLAIM_KIND = "kind";
    public static final String CLAIM_AUTH_METHOD = "auth_method";
    public static final String CLAIM_OWNER_USER_ID = "owner_user_id";
    public static final String CLAIM_AGENT_ID = "agent_id";
    public static final String CLAIM_CONFIRMATION = "cnf";
    public static final String CONFIRMATION_X5T_S256 = "x5t#S256";

    private final JwtEncoder encoder;
    private final IntegrationSettings settings;

    public ClientTokenService(JwtEncoder encoder, IntegrationSettings settings) {
        this.encoder = encoder;
        this.settings = settings;
    }

    /** An issued token and what the token response reports. */
    public record Issued(String accessToken, long expiresInSeconds, Set<Permission> scopes) {

        @Override
        public String toString() {
            return "Issued[expiresIn=" + expiresInSeconds + ", scopes=" + scopes + "]";
        }
    }

    public Duration ttlFor(int bankId) {
        return settings.clientTokenTtl(bankId);
    }

    /**
     * @param scopes            the granted scopes (already narrowed to the request)
     * @param certificateSha256 the certificate the client authenticated with
     *                          (binds the token to it), or null
     */
    public Issued issue(ApiClient client, Set<Permission> scopes, String certificateSha256) {
        Instant now = Instant.now();
        Duration ttl = ttlFor(client.getBankId());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(client.getClientId())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(CLAIM_TYPE, TYPE_CLIENT)
                .claim(CLAIM_CLIENT_ID, client.getClientId())
                .claim(CLAIM_BANK_ID, client.getBankId())
                .claim(CLAIM_TOKEN_VERSION, client.getTokenVersion())
                .claim(CLAIM_SCOPE, formatScopes(scopes))
                .claim(CLAIM_API_CLIENT_ID, client.getApiClientId())
                .claim(CLAIM_KIND, client.getKind().name())
                .claim(CLAIM_AUTH_METHOD, client.getAuthMethod().name());
        if (client.getCreatedByUserId() != null) {
            claims.claim(CLAIM_OWNER_USER_ID, client.getCreatedByUserId());
        }
        if (client.getAgentId() != null) {
            claims.claim(CLAIM_AGENT_ID, client.getAgentId());
        }
        if (certificateSha256 != null) {
            claims.claim(CLAIM_CONFIRMATION, Map.of(CONFIRMATION_X5T_S256, x5tS256(certificateSha256)));
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build()))
                .getTokenValue();
        return new Issued(token, ttl.toSeconds(), scopes);
    }

    // ------------------------------------------------------------ reading

    public static boolean isClientToken(Jwt token) {
        return TYPE_CLIENT.equals(token.getClaimAsString(CLAIM_TYPE));
    }

    /** The token's {@code scope} as permissions (unknown names ignored). */
    public static Set<Permission> scopesOf(Jwt token) {
        return parseScopes(token.getClaimAsString(CLAIM_SCOPE));
    }

    /** The bound certificate thumbprint ({@code cnf.x5t#S256}), or null. */
    public static String certificateThumbprintOf(Jwt token) {
        Object cnf = token.getClaims().get(CLAIM_CONFIRMATION);
        if (cnf instanceof Map<?, ?> map && map.get(CONFIRMATION_X5T_S256) instanceof String s) {
            return s;
        }
        return null;
    }

    /**
     * The principal of a token the decoder (incl. RevocationValidator's client
     * branch) already accepted — built from its claims, no lookup.
     */
    public static ClientPrincipal principalOf(Jwt token) {
        Object bank = token.getClaims().get(CLAIM_BANK_ID);
        Object owner = token.getClaims().get(CLAIM_OWNER_USER_ID);
        Object agent = token.getClaims().get(CLAIM_AGENT_ID);
        Object id = token.getClaims().get(CLAIM_API_CLIENT_ID);
        if (!(bank instanceof Number bankId) || !(id instanceof Number apiClientId)) {
            throw new IllegalStateException("Client token without bank_id/api_client_id.");
        }
        return new ClientPrincipal(apiClientId.intValue(), bankId.intValue(),
                token.getClaimAsString(CLAIM_CLIENT_ID),
                parseEnum(ApiClientKind.class, token.getClaimAsString(CLAIM_KIND),
                        ApiClientKind.SYSTEM),
                parseEnum(ApiAuthMethod.class, token.getClaimAsString(CLAIM_AUTH_METHOD),
                        ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS),
                scopesOf(token),
                owner instanceof Number ownerId ? ownerId.intValue() : 0,
                agent instanceof Number agentId ? agentId.intValue() : null);
    }

    /** RFC 8705 {@code x5t#S256}: base64url (no padding) of the SHA-256 given as hex. */
    public static String x5tS256(String sha256Hex) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.util.HexFormat.of().parseHex(sha256Hex));
    }

    public static String formatScopes(Set<Permission> scopes) {
        return scopes.stream().map(Permission::name).sorted().collect(Collectors.joining(" "));
    }

    /** Space- or comma-separated permission names; unknown names are dropped. */
    public static Set<Permission> parseScopes(String value) {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        if (value == null || value.isBlank()) {
            return set;
        }
        for (String part : value.trim().split("[\\s,]+")) {
            try {
                set.add(Permission.valueOf(part.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                // ignored: a scope we do not know grants nothing
            }
        }
        return set;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
