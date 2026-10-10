package com.modocument.infrastructure.integration.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.integration.OutboundAuthType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The HTTP side shared by the REST_API and WEBHOOK connectors (ws25):
 * <ul>
 *   <li>the base URL is validated once (https only unless the limits allow
 *       plain http; no user-info, query or fragment) and request paths are
 *       relative to it — a flow can never point a call at another host;</li>
 *   <li>every destination (resource and OAuth2 token endpoint) passes
 *       {@link EgressGuard#checkDestination} right before the call;</li>
 *   <li>redirects are NEVER followed (authenticated calls must not be
 *       bounced to another host); every client has a connect timeout and
 *       every request a read timeout; response bodies are size-capped;</li>
 *   <li>outbound auth per {@link OutboundAuthType}: NONE, BASIC, BEARER,
 *       API_KEY_HEADER, OAUTH2_CLIENT_CREDENTIALS (token cached until shortly
 *       before expiry), MTLS (client certificate + key from the secret).</li>
 * </ul>
 * Secrets never appear in an exception or log line produced here.
 */
@Component
public class OutboundHttp {

    /** Header names a connection may configure for API_KEY_HEADER. */
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length",
            "connection", "transfer-encoding", "cookie", "x-modocument-signature",
            "x-modocument-timestamp", "idempotency-key");
    /** Refresh a cached token this long before it expires. */
    private static final Duration TOKEN_SKEW = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EgressGuard egress;
    private final ConnectorLimits limits;
    private final Clock clock;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    @Autowired
    public OutboundHttp(EgressGuard egress, IntegrationConnectorProperties properties) {
        this(egress, properties.limits(), Clock.systemUTC());
    }

    public OutboundHttp(EgressGuard egress, ConnectorLimits limits, Clock clock) {
        this.egress = egress;
        this.limits = limits;
        this.clock = clock;
    }

    public ConnectorLimits limits() {
        return limits;
    }

    // ---- URLs ----------------------------------------------------------------

    /**
     * The connection's base URL, validated: {@code baseUrl} (REST_API) or
     * {@code url} (WEBHOOK; either key is accepted for both).
     */
    public URI baseUri(ConnectionContext connection) {
        String baseUrl = connection.config("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            return validatedBase(connection.config("url"),
                    connection.type() == com.modocument.domain.model.integration.ConnectionType.WEBHOOK
                            ? "url" : "baseUrl");
        }
        return validatedBase(baseUrl, "baseUrl");
    }

    URI validatedBase(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("The connection setting '" + what + "' is required.");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + what + "' is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && limits.allowPlainHttp())) {
            throw new IllegalArgumentException("'" + what + "' must be an https:// URL.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("'" + what + "' has no host.");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("'" + what + "' must not carry credentials, a "
                    + "query or a fragment.");
        }
        return uri;
    }

    /** {@code base} + a validated relative path + an encoded query. */
    public static URI resolve(URI base, String relativePath, Map<String, String> query) {
        String path = Specs.relativePath(relativePath, "path", true);
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        StringBuilder url = new StringBuilder();
        url.append(base.getScheme()).append("://").append(base.getRawAuthority());
        if (basePath.isEmpty()) {
            basePath = "/";
        }
        url.append(basePath);
        if (!path.isEmpty()) {
            if (!basePath.endsWith("/")) {
                url.append('/');
            }
            url.append(path);
        }
        if (query != null && !query.isEmpty()) {
            char separator = '?';
            for (Map.Entry<String, String> entry : query.entrySet()) {
                url.append(separator).append(encode(entry.getKey())).append('=')
                        .append(encode(entry.getValue() == null ? "" : entry.getValue()));
                separator = '&';
            }
        }
        return URI.create(url.toString());
    }

    /** True when {@code candidate} has the same scheme, host and port as {@code base}. */
    public static boolean sameOrigin(URI base, URI candidate) {
        return candidate.getScheme() != null && candidate.getHost() != null
                && base.getScheme().equalsIgnoreCase(candidate.getScheme())
                && base.getHost().equalsIgnoreCase(candidate.getHost())
                && port(base) == port(candidate)
                && candidate.getRawUserInfo() == null;
    }

    static int port(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---- auth ----------------------------------------------------------------

    public static OutboundAuthType authType(ConnectionContext connection) {
        String raw = connection.config("authType");
        if (raw == null || raw.isBlank()) {
            return OutboundAuthType.NONE;
        }
        try {
            return OutboundAuthType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported auth type '" + Specs.safe(raw) + "'.");
        }
    }

    /** Refuses a connection whose auth settings or secret keys are incomplete. */
    public void validateAuth(ConnectionContext connection, OutboundAuthType type) {
        SecretMaterial secret = connection.secret();
        boolean serverSide = connection.mode() == com.modocument.domain.model.integration.ConnectionMode.DIRECT;
        switch (type) {
            case NONE, HMAC_SIGNATURE -> { }
            case BASIC -> {
                if (connection.config("username") == null && secret.get("username") == null) {
                    throw new IllegalArgumentException("BASIC auth needs a 'username'.");
                }
                requireSecret(serverSide, secret, "password");
            }
            case BEARER -> requireSecret(serverSide, secret, "token");
            case API_KEY_HEADER -> {
                String header = connection.config("apiKeyHeader");
                if (header != null && (!HEADER_NAME.matcher(header).matches()
                        || FORBIDDEN_HEADERS.contains(header.toLowerCase(Locale.ROOT)))) {
                    throw new IllegalArgumentException("'apiKeyHeader' is not an allowed header name.");
                }
                requireSecret(serverSide, secret, "apiKey");
            }
            case OAUTH2_CLIENT_CREDENTIALS -> {
                validatedBase(connection.config("tokenUrl"), "tokenUrl");
                if (connection.config("clientId") == null) {
                    throw new IllegalArgumentException("OAuth2 needs a 'clientId'.");
                }
                requireSecret(serverSide, secret, "clientSecret");
            }
            case MTLS -> {
                requireSecret(serverSide, secret, "clientCertPem");
                requireSecret(serverSide, secret, "clientKeyPem");
            }
        }
    }

    private static void requireSecret(boolean serverSide, SecretMaterial secret, String key) {
        if (serverSide && (secret.get(key) == null || secret.get(key).isBlank())) {
            throw new IllegalArgumentException("The connection secret has no '" + key + "'.");
        }
    }

    /** A client for one call: no redirects, a connect timeout, the MTLS context when needed. */
    public HttpClient client(ConnectionContext connection, OutboundAuthType type) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(limits.connectTimeout());
        if (type == OutboundAuthType.MTLS || connection.config("caCertPem") != null) {
            SSLContext context = TlsMaterial.sslContext(
                    type == OutboundAuthType.MTLS ? connection.secret().get("clientCertPem") : null,
                    type == OutboundAuthType.MTLS ? connection.secret().get("clientKeyPem") : null,
                    connection.config("caCertPem"));
            builder.sslContext(context);
        }
        return builder.build();
    }

    /** A request builder with the read timeout and the connection's auth applied. */
    public HttpRequest.Builder request(ConnectionContext connection, OutboundAuthType type,
                                       HttpClient client, URI uri) throws Exception {
        checkEgress(connection, uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(limits.readTimeout())
                .header("User-Agent", "MoDocument-Integration/1");
        SecretMaterial secret = connection.secret();
        switch (type) {
            case BASIC -> {
                String user = connection.config("username") != null
                        ? connection.config("username") : secret.get("username");
                String pair = user + ":" + nullToEmpty(secret.get("password"));
                builder.header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
            }
            case BEARER -> builder.header("Authorization", "Bearer " + secret.get("token"));
            case API_KEY_HEADER -> builder.header(
                    connection.config("apiKeyHeader") == null ? "X-Api-Key"
                            : connection.config("apiKeyHeader"),
                    nullToEmpty(secret.get("apiKey")));
            case OAUTH2_CLIENT_CREDENTIALS -> builder.header("Authorization",
                    "Bearer " + accessToken(connection, client));
            case NONE, MTLS, HMAC_SIGNATURE -> { }
        }
        return builder;
    }

    public void checkEgress(ConnectionContext connection, URI uri) {
        egress.checkDestination(connection.bankId(), uri.getHost(), port(uri));
    }

    /** Sends and reads the body up to the response cap. */
    public HttpResponse<byte[]> send(HttpClient client, HttpRequest request) throws Exception {
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream in = response.body()) {
            body = readCapped(in, limits.maxResponseBytes());
        }
        return new CappedResponse(response, body);
    }

    static byte[] readCapped(InputStream in, long cap) throws IOException {
        byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, cap + 1));
        if (bytes.length > cap) {
            throw new IllegalStateException("The response is larger than the " + cap + "-byte cap.");
        }
        return bytes;
    }

    /** Evicts the cached OAuth2 token of a connection (after a 401 from the resource). */
    public void evictToken(ConnectionContext connection) {
        tokens.keySet().removeIf(key -> key.startsWith(connection.bankId() + "|"
                + connection.connectionId() + "|"));
    }

    // ---- OAuth2 client credentials -------------------------------------------

    String accessToken(ConnectionContext connection, HttpClient client) throws Exception {
        String tokenUrl = connection.config("tokenUrl");
        String clientId = connection.config("clientId");
        String clientSecret = nullToEmpty(connection.secret().get("clientSecret"));
        String scope = connection.config("scope");
        String key = connection.bankId() + "|" + connection.connectionId() + "|" + tokenUrl + "|"
                + clientId + "|" + Hashing.sha256(clientSecret) + "|" + scope;
        Instant now = clock.instant();
        CachedToken cached = tokens.get(key);
        if (cached != null && now.isBefore(cached.expiresAt().minus(TOKEN_SKEW))) {
            return cached.token();
        }
        URI uri = validatedBase(tokenUrl, "tokenUrl");
        checkEgress(connection, uri);
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (scope != null && !scope.isBlank()) {
            form.append("&scope=").append(encode(scope));
        }
        String basic = Base64.getEncoder().encodeToString((encode(clientId) + ":"
                + encode(clientSecret)).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(limits.readTimeout())
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        HttpResponse<byte[]> response = send(client, request);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("The OAuth2 token endpoint answered HTTP "
                    + response.statusCode() + ".");
        }
        JsonNode body = MAPPER.readTree(response.body());
        JsonNode token = body.get("access_token");
        if (token == null || !token.isTextual() || token.asText().isBlank()) {
            throw new IllegalStateException("The OAuth2 token endpoint returned no access_token.");
        }
        long expiresIn = body.hasNonNull("expires_in") ? body.get("expires_in").asLong(300) : 300;
        expiresIn = Math.max(1, Math.min(expiresIn, 24 * 3600));
        tokens.put(key, new CachedToken(token.asText(), now.plusSeconds(expiresIn)));
        return token.asText();
    }

    /** Tokens currently cached (tests). */
    int cachedTokens() {
        return tokens.size();
    }

    private record CachedToken(String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "CachedToken[expiresAt=" + expiresAt + "]";
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** A response whose body was read up to the cap. */
    private record CappedResponse(HttpResponse<InputStream> delegate, byte[] body)
            implements HttpResponse<byte[]> {

        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public HttpRequest request() {
            return delegate.request();
        }

        @Override
        public java.util.Optional<HttpResponse<byte[]>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return delegate.headers();
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return delegate.sslSession();
        }

        @Override
        public URI uri() {
            return delegate.uri();
        }

        @Override
        public HttpClient.Version version() {
            return delegate.version();
        }
    }
}
