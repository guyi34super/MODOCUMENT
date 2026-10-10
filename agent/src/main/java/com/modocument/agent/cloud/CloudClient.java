package com.modocument.agent.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.agent.state.CredentialStore.Credentials;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/**
 * HTTPS client for MoDocument's agent protocol (contracts §7.4) and the
 * OAuth2 client-credentials token endpoint. Outbound only, no redirects
 * followed, every call with a timeout. The access token is cached until a
 * minute before it expires and refreshed once on a 401.
 *
 * <p>Safety rails: the base URL must be https (unless explicitly allowed for
 * local tests), and the token URL MoDocument hands out at enrolment must be
 * on the SAME origin — the client secret is never sent anywhere else.
 */
public class CloudClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final URI base;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private volatile Credentials credentials;
    private String accessToken;
    private long accessTokenExpiresAtMillis;

    public CloudClient(String baseUrl, boolean allowInsecureHttp, ObjectMapper mapper) {
        this.base = requireBase(baseUrl, allowInsecureHttp);
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The validated base URL, without a trailing slash. */
    public URI base() {
        return base;
    }

    public void useCredentials(Credentials credentials) {
        this.credentials = credentials;
        synchronized (this) {
            accessToken = null;
        }
    }

    /**
     * Same-origin check of the token URL from the enrolment answer; falls
     * back to the contract path on the configured base.
     */
    public String tokenUrlFor(String offered) {
        String fallback = base + "/api/integration/oauth/token";
        if (offered == null || offered.isBlank()) {
            return fallback;
        }
        try {
            URI uri = URI.create(offered);
            if (Objects.equals(uri.getScheme(), base.getScheme())
                    && Objects.equals(uri.getHost(), base.getHost())
                    && uri.getPort() == base.getPort()) {
                return uri.toString();
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        return fallback;
    }

    public Protocol.EnrollResponse enroll(Protocol.EnrollRequest request) {
        HttpRequest call = json(HttpRequest.newBuilder(path("/api/agent/v1/enroll")), request)
                .timeout(CALL_TIMEOUT)
                .build();
        return read(send(call), Protocol.EnrollResponse.class);
    }

    public Protocol.TasksResponse tasks(int waitSeconds) {
        int wait = Math.max(0, Math.min(30, waitSeconds));
        return authorized(token -> HttpRequest.newBuilder(path("/api/agent/v1/tasks?wait=" + wait))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(wait + 30L))
                .GET()
                .build(), Protocol.TasksResponse.class);
    }

    public void records(long runId, Protocol.RecordsRequest request) {
        authorized(token -> json(HttpRequest.newBuilder(path("/api/agent/v1/runs/" + runId + "/records")),
                request).header("Authorization", "Bearer " + token).timeout(CALL_TIMEOUT).build(), null);
    }

    public Protocol.OutboundBatch payload(long runId) {
        return authorized(token -> HttpRequest.newBuilder(path("/api/agent/v1/runs/" + runId + "/payload"))
                .header("Authorization", "Bearer " + token)
                .timeout(CALL_TIMEOUT)
                .GET()
                .build(), Protocol.OutboundBatch.class);
    }

    public void result(long runId, Protocol.ResultRequest request) {
        authorized(token -> json(HttpRequest.newBuilder(path("/api/agent/v1/runs/" + runId + "/result")),
                request).header("Authorization", "Bearer " + token).timeout(CALL_TIMEOUT).build(), null);
    }

    public Protocol.HeartbeatResponse heartbeat(Protocol.HeartbeatRequest request) {
        return authorized(token -> json(HttpRequest.newBuilder(path("/api/agent/v1/heartbeat")), request)
                .header("Authorization", "Bearer " + token)
                .timeout(CALL_TIMEOUT)
                .build(), Protocol.HeartbeatResponse.class);
    }

    // ---------------------------------------------------------------- internals

    private <T> T authorized(java.util.function.Function<String, HttpRequest> request, Class<T> type) {
        HttpResponse<byte[]> response = sendRaw(request.apply(accessToken(false)));
        if (response.statusCode() == 401) {
            response = sendRaw(request.apply(accessToken(true)));
        }
        check(response);
        return type == null ? null : read(response, type);
    }

    private synchronized String accessToken(boolean refresh) {
        long now = System.currentTimeMillis();
        if (!refresh && accessToken != null && now < accessTokenExpiresAtMillis) {
            return accessToken;
        }
        Credentials current = credentials;
        if (current == null) {
            throw new CloudException(401, "The agent is not enrolled.");
        }
        String basic = Base64.getEncoder().encodeToString(
                (urlEncode(current.clientId()) + ":" + urlEncode(current.clientSecret()))
                        .getBytes(StandardCharsets.UTF_8));
        HttpRequest call = HttpRequest.newBuilder(URI.create(tokenUrlFor(current.tokenUrl())))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(CALL_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        Protocol.TokenResponse token = read(send(call), Protocol.TokenResponse.class);
        if (token.access_token() == null || token.access_token().isBlank()) {
            throw new CloudException(502, "The token endpoint returned no access token.");
        }
        long ttlMillis = Math.max(30_000L, (token.expires_in() == null ? 300 : token.expires_in()) * 1000L);
        accessToken = token.access_token();
        accessTokenExpiresAtMillis = now + ttlMillis - Math.min(60_000L, ttlMillis / 2);
        return accessToken;
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        HttpResponse<byte[]> response = sendRaw(request);
        check(response);
        return response;
    }

    private HttpResponse<byte[]> sendRaw(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new CloudException("MoDocument is unreachable (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudException("Interrupted while calling MoDocument", e);
        }
    }

    private void check(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        throw new CloudException(status, "MoDocument answered " + status + errorCode(response.body()));
    }

    /** Only the server's stable error code, never the full body. */
    private String errorCode(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node != null && node.hasNonNull("code")) {
                String code = node.get("code").asText();
                if (code.matches("[A-Z0-9_]{1,64}")) {
                    return " (" + code + ")";
                }
            }
        } catch (IOException | RuntimeException e) {
            // no JSON body
        }
        return "";
    }

    private <T> T read(HttpResponse<byte[]> response, Class<T> type) {
        try {
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new CloudException(502, "MoDocument sent an unreadable " + type.getSimpleName());
        }
    }

    private HttpRequest.Builder json(HttpRequest.Builder builder, Object body) {
        try {
            return builder.header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize " + body.getClass().getSimpleName(), e);
        }
    }

    private URI path(String pathAndQuery) {
        return URI.create(base + pathAndQuery);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static URI requireBase(String baseUrl, boolean allowInsecureHttp) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("MODOCUMENT_URL is not set.");
        }
        String trimmed = baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MODOCUMENT_URL is not a URL.");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        if (uri.getHost() == null || !(https || (http && allowInsecureHttp))
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null) {
            throw new IllegalStateException("MODOCUMENT_URL must be an https:// URL without "
                    + "credentials, query or fragment.");
        }
        return uri;
    }
}
