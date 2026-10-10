package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.MoDocumentApplication;
import com.modocument.infrastructure.security.totp.Base32;
import com.modocument.infrastructure.security.totp.Totp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full two-factor sign-in on real PostgreSQL with the runtime pool
 * connecting as {@code modocument_app} (RLS enforced), the way production
 * runs: password → forced enrolment → activate → verify → access token →
 * refresh. Every login write (the LOGIN_SUCCESS audit row, the refresh
 * family, the TOTP counter) must pass the RLS WITH CHECK even though the
 * verify call is anonymous until it succeeds — the H2 suite cannot see
 * that, and a 502 here once locked every MFA user (every admin) out.
 *
 * <p>Phase 2 (OBS-02): the bootstrap admin's password comes from deployment
 * configuration, so the first session is good for the forced password
 * change only; the change itself (PASSWORD_CHANGED, TokenVersion bump,
 * refresh revocation) must also pass RLS, and the next sign-in — TOTP
 * already active — opens a normal session.
 */
class MfaLoginPgIT extends PgItSupport {

    private static final Pattern URL_SHAPE =
            Pattern.compile("^(jdbc:postgresql://[^/]+/)([^?]*)(\\?.*)?$");
    private static final String EMAIL = "mfa-admin@example.com";
    private static final String PASSWORD = "Mfa-Login-Passphrase-2026!";
    private static final String NEW_PASSWORD = "Mfa-Login-Rotated-Passphrase-77!";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    private ConfigurableApplicationContext context;
    private String databaseName;
    private String base;

    @AfterEach
    void tearDown() throws Exception {
        if (context != null) {
            context.close();
        }
        if (databaseName != null) {
            try (Connection owner = ownerDataSource().getConnection();
                 Statement statement = owner.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            }
        }
    }

    @Test
    void adminSignsInWithMfaWhileTheAppRunsUnderRls() throws Exception {
        boot();

        JsonNode login = post("/api/auth/login", null,
                Map.of("email", EMAIL, "password", PASSWORD), 200);
        assertThat(login.path("mfaRequired").asBoolean()).isTrue();
        assertThat(login.path("enrollmentRequired").asBoolean()).isTrue();
        String challenge = login.path("challenge").asText();

        JsonNode enrollment = post("/api/auth/mfa/enroll", challenge, Map.of(), 200);
        byte[] secret = Base32.decode(enrollment.path("secret").asText());
        String code = Totp.codeAt(secret, Totp.counterAt(Instant.now().getEpochSecond()));
        post("/api/auth/mfa/activate", challenge, Map.of("code", code), 204);

        HttpResponse<String> verified = send("/api/auth/mfa/verify", null,
                Map.of("challenge", challenge, "code", code));
        assertThat(verified.statusCode())
                .as("MFA verify under RLS: %s", verified.body())
                .isEqualTo(200);
        String accessToken = json.readTree(verified.body()).path("token").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(json.readTree(verified.body()).at("/user/mustChangePassword").asBoolean())
                .as("bootstrap passwords are temporary (OBS-02)")
                .isTrue();
        String refreshCookie = verified.headers().firstValue("Set-Cookie").orElseThrow()
                .split(";", 2)[0];

        HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains(EMAIL);

        HttpResponse<String> refreshed = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/refresh"))
                        .header("Cookie", refreshCookie)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(refreshed.statusCode())
                .as("refresh under RLS: %s", refreshed.body())
                .isEqualTo(200);

        // The LOGIN_SUCCESS row really landed, stamped with the admin's bank.
        try (Connection owner = java.sql.DriverManager.getConnection(
                     withDatabase(pg().jdbcUrl(), databaseName), pg().ownerUser(),
                     pg().ownerPassword());
             Statement statement = owner.createStatement();
             var rs = statement.executeQuery(
                     "SELECT count(*) FROM AuditEvent WHERE Action = 'LOGIN_SUCCESS' "
                             + "AND BankId IS NOT NULL")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }

        // OBS-02: until the bootstrap password is changed, nothing else opens.
        HttpResponse<String> gated = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/api/admin/users"))
                .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(gated.statusCode()).isEqualTo(403);
        assertThat(gated.body()).contains("PASSWORD_CHANGE_REQUIRED");

        HttpResponse<String> changed = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/api/auth/password"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(changed.statusCode())
                .as("password change under RLS: %s", changed.body())
                .isEqualTo(204);

        // Signed in again: TOTP is active now, so no enrolment — the next
        // step's code (the first one is spent: replay protection).
        JsonNode second = post("/api/auth/login", null,
                Map.of("email", EMAIL, "password", NEW_PASSWORD), 200);
        assertThat(second.path("mfaRequired").asBoolean()).isTrue();
        assertThat(second.path("enrollmentRequired").asBoolean()).isFalse();
        String nextCode = Totp.codeAt(secret, Totp.counterAt(Instant.now().getEpochSecond()) + 1);
        JsonNode session = post("/api/auth/mfa/verify", null,
                Map.of("challenge", second.path("challenge").asText(), "code", nextCode), 200);
        assertThat(session.at("/user/mustChangePassword").asBoolean()).isFalse();
        HttpResponse<String> users = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/api/admin/users"))
                .header("Authorization", "Bearer " + session.path("token").asText())
                .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(users.statusCode()).isEqualTo(200);
    }

    private void boot() throws Exception {
        String perRunUrl = pg().jdbcUrl();
        databaseName = "modocument_mfa_"
                + Long.toString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        String url = withDatabase(perRunUrl, databaseName);

        Map<String, Object> deployment = new HashMap<>();
        deployment.put("MODOCUMENT_DB_URL", url);
        deployment.put("MODOCUMENT_DB_USERNAME", APP_ROLE);
        deployment.put("MODOCUMENT_DB_PASSWORD", APP_ROLE_PASSWORD);
        deployment.put("MODOCUMENT_FLYWAY_USERNAME", pg().ownerUser());
        deployment.put("MODOCUMENT_FLYWAY_PASSWORD", pg().ownerPassword());
        deployment.put("MODOCUMENT_JWT_SECRET", "mfa-login-it-signing-key-0123456789abcdefghij");
        deployment.put("MODOCUMENT_MFA_KEY", "mfa-login-it-mfa-encryption-key-0123456789abcd");
        deployment.put("MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL", EMAIL);
        deployment.put("MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD", PASSWORD);
        deployment.put("MODOCUMENT_STORAGE_ROOT", dir.resolve("storage").toString());

        context = new SpringApplicationBuilder(MoDocumentApplication.class)
                .properties(deployment)
                .run("--server.port=0",
                        "--extraction.worker.enabled=false",
                        "--modocument.retention.enabled=false",
                        "--modocument.sod.enabled=false");
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    private JsonNode post(String path, String bearer, Map<String, ?> body, int expectedStatus)
            throws Exception {
        HttpResponse<String> response = send(path, bearer, body);
        assertThat(response.statusCode())
                .as("%s → %s", path, response.body())
                .isEqualTo(expectedStatus);
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }

    private HttpResponse<String> send(String path, String bearer, Map<String, ?> body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String withDatabase(String url, String database) {
        Matcher matcher = URL_SHAPE.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported PostgreSQL JDBC url shape: " + url);
        }
        return matcher.group(1) + database + (matcher.group(3) == null ? "" : matcher.group(3));
    }
}
