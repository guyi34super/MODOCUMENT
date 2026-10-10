package com.modocument.api.controller;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.infrastructure.ratelimit.FixedWindowRateLimiter;
import com.modocument.infrastructure.security.ClientIps;
import com.modocument.infrastructure.security.RequestIdFilter;
import com.modocument.infrastructure.security.client.ApiClientAuthenticator;
import com.modocument.infrastructure.security.client.ClientTokenService;
import com.modocument.infrastructure.security.client.IntegrationClientProperties;
import com.modocument.infrastructure.security.client.MtlsClientCertificates;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The OAuth2 token endpoint for bank systems (ws26, RFC 6749 §4.4 client
 * credentials). PUBLIC route — the client authenticates inside:
 * <ul>
 *   <li>{@code client_secret_basic}: {@code Authorization: Basic
 *       base64(urlenc(client_id):urlenc(client_secret))};</li>
 *   <li>{@code client_secret_post}: form fields {@code client_id} +
 *       {@code client_secret};</li>
 *   <li>mutual TLS (RFC 8705, {@code tls_client_auth}): the client
 *       certificate the trusted proxy forwarded, for an MTLS client
 *       ({@code client_id} optional; the token is bound to the certificate).</li>
 * </ul>
 * Form body {@code grant_type=client_credentials[&scope=A B]}; the answer is
 * {@code {access_token, token_type:"Bearer", expires_in, scope}} with
 * {@code Cache-Control: no-store}. Errors follow RFC 6749 §5.2
 * ({@code error}, {@code error_description}) plus the API's {@code code} /
 * {@code message}: 400 invalid_request / unsupported_grant_type /
 * invalid_scope, 401 invalid_client, 429 when the per-IP limit is reached.
 */
@RestController
@Tag(name = "Integration OAuth", description = "Client-credentials token endpoint for bank systems")
public class IntegrationTokenController {

    public static final String PATH = "/api/integration/oauth/token";
    public static final String TOKEN_ISSUED = "API_CLIENT_TOKEN_ISSUED";

    private final ApiClientAuthenticator authenticator;
    private final MtlsClientCertificates certificates;
    private final ClientTokenService tokens;
    private final AuditRecorder audit;
    private final FixedWindowRateLimiter perIp;

    public IntegrationTokenController(ApiClientAuthenticator authenticator,
                                      MtlsClientCertificates certificates,
                                      ClientTokenService tokens,
                                      AuditRecorder audit,
                                      IntegrationClientProperties properties) {
        this.authenticator = authenticator;
        this.certificates = certificates;
        this.tokens = tokens;
        this.audit = audit;
        this.perIp = new FixedWindowRateLimiter(Math.max(1, properties.getApi().getTokenPerIpMax()),
                Math.max(1, properties.getClients().getVerifyWindow().toMillis()), 10_000);
    }

    @PostMapping(value = PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(HttpServletRequest request,
                                                     @RequestParam MultiValueMap<String, String> form) {
        String ip = ClientIps.clientIp(request);
        long now = System.currentTimeMillis();
        if (!perIp.tryAcquire("token:" + ip, now)) {
            return status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER,
                            Long.toString(perIp.retryAfterSeconds("token:" + ip, now)))
                    .body(body("slow_down", "Too many token requests. Try again later."));
        }
        String grantType = single(form, "grant_type");
        if (grantType == null) {
            return status(HttpStatus.BAD_REQUEST)
                    .body(body("invalid_request", "grant_type is required."));
        }
        if (!"client_credentials".equals(grantType)) {
            return status(HttpStatus.BAD_REQUEST)
                    .body(body("unsupported_grant_type",
                            "Only grant_type=client_credentials is supported."));
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean basic = authorization != null
                && authorization.regionMatches(true, 0, "Basic ", 0, 6);
        String formId = single(form, "client_id");
        String formSecret = single(form, "client_secret");
        ApiClientAuthenticator.Result result;
        String boundCertificate = null;
        if (basic) {
            if (formSecret != null) {
                return invalidRequest("Use one client authentication method, not two.");
            }
            String[] credentials = decodeBasic(authorization.substring(6).trim());
            if (credentials == null) {
                return invalidClient(true);
            }
            result = authenticator.authenticateSecret(credentials[0], credentials[1], request);
        } else if (formSecret != null) {
            result = authenticator.authenticateSecret(formId, formSecret, request);
        } else {
            Optional<MtlsClientCertificates.PresentedCertificate> certificate =
                    certificates.presented(request);
            if (certificate.isEmpty()) {
                return invalidClient(false);
            }
            result = authenticator.authenticateCertificate(certificate.get(), formId, request);
            boundCertificate = certificate.get().fingerprintSha256();
        }
        if (result.rateLimited()) {
            return status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "60")
                    .body(body("slow_down", "Too many authentication attempts. Try again later."));
        }
        if (!result.succeeded()) {
            return invalidClient(basic);
        }

        Set<Permission> granted = result.principal().scopes();
        String requested = single(form, "scope");
        Set<Permission> scopes = granted;
        if (requested != null && !requested.isBlank()) {
            scopes = ClientTokenService.parseScopes(requested);
            int words = requested.trim().split("[\\s]+").length;
            if (scopes.size() != words || !granted.containsAll(scopes)) {
                return status(HttpStatus.BAD_REQUEST)
                        .body(body("invalid_scope",
                                "The requested scope exceeds the client's scopes."));
            }
        }
        ClientTokenService.Issued issued = tokens.issue(result.client(), scopes,
                result.client().getAuthMethod() == ApiAuthMethod.MTLS ? boundCertificate : null);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("clientId", result.client().getClientId());
        values.put("scope", ClientTokenService.formatScopes(scopes));
        values.put("expiresIn", issued.expiresInSeconds());
        audit.record(AuditEventSpec.builder(TOKEN_ISSUED, AuditOutcome.SUCCESS)
                .entity("ApiClient", String.valueOf(result.client().getApiClientId()))
                .actorRole("API_CLIENT")
                .actorEmail("api-client:" + result.client().getClientId())
                .bankId(result.client().getBankId())
                .newValues(values)
                .build());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", issued.accessToken());
        response.put("token_type", "Bearer");
        response.put("expires_in", issued.expiresInSeconds());
        response.put("scope", ClientTokenService.formatScopes(scopes));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    private ResponseEntity<Map<String, Object>> invalidRequest(String description) {
        return status(HttpStatus.BAD_REQUEST)
                .body(body("invalid_request", description));
    }

    private ResponseEntity<Map<String, Object>> invalidClient(boolean basic) {
        ResponseEntity.BodyBuilder builder = status(HttpStatus.UNAUTHORIZED);
        if (basic) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"modocument-integration\"");
        }
        return builder.body(body("invalid_client", "Client authentication failed."));
    }

    private static ResponseEntity.BodyBuilder status(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore());
    }

    private static Map<String, Object> body(String error, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        body.put("code", error.toUpperCase(java.util.Locale.ROOT));
        body.put("message", description);
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }

    /** One value of a form field; a repeated field is invalid (null). */
    private static String single(MultiValueMap<String, String> form, String name) {
        List<String> values = form.get(name);
        if (values == null || values.isEmpty() || values.size() > 1) {
            return null;
        }
        String value = values.get(0);
        return value == null || value.isEmpty() ? null : value;
    }

    /** RFC 6749 §2.3.1: both parts are form-urlencoded before base64. */
    private static String[] decodeBasic(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            return new String[] {
                    URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
                    URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)};
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
