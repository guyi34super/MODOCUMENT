package com.modocument.pgit;

import com.modocument.MoDocumentApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * First production boot, end to end on real PostgreSQL (ws16): the app is
 * configured ONLY through its documented deployment variables — exactly what
 * docker-compose passes — on the default (production) profile, against an
 * EMPTY database, so Spring's own Flyway migrates the full chain as the
 * schema owner while the runtime pool connects as {@code modocument_app}.
 *
 * <p>Covers what the other ITs bypass (they migrate through PgItSupport with
 * Spring's Flyway disabled): the Flyway auto-configuration with separate
 * owner credentials behind the TenantAwareDataSource wrapper (startup used to
 * die with "Unable to find suitable method for url"), the DB URL carrying
 * {@code sslmode} for both connections (C4/SEC-028 app side), the
 * {@code <NAME>_FILE} secret indirection (SEC-018) and the production
 * security headers on a real Tomcat (E1).
 */
class ProductionBootPgIT extends PgItSupport {

    private static final Pattern URL_SHAPE =
            Pattern.compile("^(jdbc:postgresql://[^/]+/)([^?]*)(\\?.*)?$");
    private static final String TLS_PARAM = "sslmode=prefer";

    @TempDir
    Path dir;

    private ConfigurableApplicationContext context;
    private String databaseName;

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
    void firstBootMigratesAsOwnerRunsAsAppRoleAndServesProductionHeaders() throws Exception {
        // pg() also guarantees the cluster-wide modocument_app role exists
        // with the password the suite knows.
        String perRunUrl = pg().jdbcUrl();
        databaseName = "modocument_boot_"
                + Long.toString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        String url = withDatabaseAndTls(perRunUrl, databaseName);

        Path jwtSecret = Files.writeString(dir.resolve("jwt_secret"),
                "prod-boot-it-signing-key-0123456789abcdefghijklmnop\n");
        Path mfaKey = Files.writeString(dir.resolve("mfa_key"),
                "prod-boot-it-mfa-encryption-key-0123456789abcdefgh\n");

        // The deployment contract, as docker-compose sets it (secrets as files).
        Map<String, Object> deployment = new HashMap<>();
        deployment.put("MODOCUMENT_DB_URL", url);
        deployment.put("MODOCUMENT_DB_USERNAME", APP_ROLE);
        deployment.put("MODOCUMENT_DB_PASSWORD", APP_ROLE_PASSWORD);
        deployment.put("MODOCUMENT_FLYWAY_USERNAME", pg().ownerUser());
        deployment.put("MODOCUMENT_FLYWAY_PASSWORD", pg().ownerPassword());
        deployment.put("MODOCUMENT_JWT_SECRET_FILE", jwtSecret.toString());
        deployment.put("MODOCUMENT_MFA_KEY_FILE", mfaKey.toString());
        deployment.put("MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL", "boot-admin@example.com");
        deployment.put("MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD", "Prod-Boot-Passphrase-2026!");
        deployment.put("MODOCUMENT_STORAGE_ROOT", dir.resolve("storage").toString());

        context = new SpringApplicationBuilder(MoDocumentApplication.class)
                .properties(deployment)
                // Test isolation only: random port, no background workers.
                .run("--server.port=0",
                        "--extraction.worker.enabled=false",
                        "--modocument.retention.enabled=false",
                        "--modocument.sod.enabled=false");

        // Flyway (owner) and the runtime pool share one URL, sslmode included.
        assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isEqualTo(url);
        assertThat(context.getEnvironment().getProperty("spring.flyway.url")).isEqualTo(url);
        assertThat(context.getBean(Flyway.class).info().current()).isNotNull();

        try (Connection connection = context.getBean(DataSource.class).getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT current_user")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(APP_ROLE);
        }

        String port = context.getEnvironment().getProperty("local.server.port");
        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.headers().allValues("Content-Security-Policy"))
                .containsExactly("default-src 'none'; frame-ancestors 'none'");
        assertThat(health.headers().allValues("X-Frame-Options")).containsExactly("DENY");
        assertThat(health.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
    }

    private static String withDatabaseAndTls(String url, String database) {
        Matcher matcher = URL_SHAPE.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported PostgreSQL JDBC url shape: " + url);
        }
        String query = matcher.group(3);
        return matcher.group(1) + database
                + (query == null ? "?" + TLS_PARAM : query + "&" + TLS_PARAM);
    }
}
