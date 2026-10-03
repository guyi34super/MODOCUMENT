package com.modocument.pgit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base class of the real-PostgreSQL integration suite (the {@code *PgIT}
 * classes, run by failsafe under {@code -Ppostgres-it}). Resolves ONE
 * database for the whole JVM, in this order:
 * <ol>
 *   <li>{@code MODOCUMENT_IT_JDBC_URL} (+ {@code MODOCUMENT_IT_SUPERUSER} /
 *       {@code MODOCUMENT_IT_SUPERUSER_PASSWORD}) — an external PostgreSQL,
 *       e.g. a scratch {@code initdb}/{@code pg_ctl} instance where Docker
 *       is unavailable;</li>
 *   <li>otherwise a Testcontainers {@code postgres:17} (CI runners).</li>
 * </ol>
 *
 * <p>Setup, once per JVM: CREATE a fresh database, run the full Flyway
 * migration chain against it as the superuser/owner (which also fires the
 * afterMigrate RLS callback and the V12 creation of {@code modocument_app}),
 * then normalize the app role's password so tests can log in with it. Tests
 * get TWO datasources: {@link #ownerDataSource()} (migration owner —
 * BYPASSES row level security) and {@link #appDataSource()} (the runtime
 * role — SUBJECT to it).
 */
@Tag("postgres")
public abstract class PgItSupport {

    public static final String APP_ROLE = "modocument_app";
    public static final String APP_ROLE_PASSWORD = "modocument_app";

    private static final Pattern URL_SHAPE =
            Pattern.compile("^(jdbc:postgresql://[^/]+/)([^?]*)(\\?.*)?$");

    private static Pg pg;

    /** The resolved, migrated per-run database (lazily initialized). */
    protected static synchronized Pg pg() {
        if (pg == null) {
            pg = Pg.create();
        }
        return pg;
    }

    protected static DataSource ownerDataSource() {
        Pg database = pg();
        return dataSource(database.jdbcUrl, database.ownerUser, database.ownerPassword);
    }

    protected static DataSource appDataSource() {
        return dataSource(pg().jdbcUrl, APP_ROLE, APP_ROLE_PASSWORD);
    }

    private static DataSource dataSource(String url, String user, String password) {
        return new SimpleDriverDataSource(new Driver(), url, user, password);
    }

    /** Resolved server + the fresh database migrated for this JVM. */
    protected record Pg(String jdbcUrl, String ownerUser, String ownerPassword) {

        @SuppressWarnings("resource") // lives for the JVM; Ryuk reaps the container
        static Pg create() {
            String adminUrl;
            String ownerUser;
            String ownerPassword;
            String externalUrl = System.getenv("MODOCUMENT_IT_JDBC_URL");
            if (externalUrl != null && !externalUrl.isBlank()) {
                adminUrl = externalUrl;
                ownerUser = env("MODOCUMENT_IT_SUPERUSER", "postgres");
                ownerPassword = env("MODOCUMENT_IT_SUPERUSER_PASSWORD", "");
            } else {
                PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:17");
                container.start();
                adminUrl = container.getJdbcUrl();
                ownerUser = container.getUsername();
                ownerPassword = container.getPassword();
            }

            String databaseName = "modocument_it_"
                    + Long.toString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
            String jdbcUrl = withDatabase(adminUrl, databaseName);

            try (Connection admin = dataSource(adminUrl, ownerUser, ownerPassword)
                    .getConnection();
                 Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + databaseName);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Could not create the integration database at " + adminUrl, e);
            }

            // The full production chain, as the owner: V1..V13 (role +
            // policies) ..V20, then the afterMigrate RLS callback.
            Flyway.configure()
                    .dataSource(jdbcUrl, ownerUser, ownerPassword)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            // V12 only creates the role when absent (placeholder password);
            // on a shared external cluster it may pre-exist with an unknown
            // one, so pin the password the suite connects with.
            try (Connection owner = dataSource(jdbcUrl, ownerUser, ownerPassword)
                    .getConnection();
                 Statement statement = owner.createStatement()) {
                statement.execute("ALTER ROLE " + APP_ROLE + " WITH LOGIN NOBYPASSRLS PASSWORD '"
                        + APP_ROLE_PASSWORD + "'");
            } catch (Exception e) {
                throw new IllegalStateException("Could not normalize the " + APP_ROLE
                        + " password on " + jdbcUrl, e);
            }
            return new Pg(jdbcUrl, ownerUser, ownerPassword);
        }

        private static String withDatabase(String url, String databaseName) {
            Matcher matcher = URL_SHAPE.matcher(url);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        "Unsupported PostgreSQL JDBC url shape: " + url);
            }
            return matcher.group(1) + databaseName
                    + (matcher.group(3) == null ? "" : matcher.group(3));
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
