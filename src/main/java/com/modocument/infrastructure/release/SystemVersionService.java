package com.modocument.infrastructure.release;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Single source of "what is running here" (ws24) for
 * {@code GET /api/system/version} and every later reader (the admin
 * dashboard's version tile, the Wave 2 deployment recorder and release
 * banner): inject this and call {@link #current()} rather than reading
 * BuildProperties/GitProperties/Flyway yourself.
 *
 * <ul>
 *   <li>version, buildTime — {@code META-INF/build-info.properties}
 *       (spring-boot-maven-plugin build-info; the Docker build pins the time
 *       to its BUILD_TIME arg);</li>
 *   <li>commit — {@code git.properties} (git-commit-id-maven-plugin, or the
 *       Dockerfile's fallback from its GIT_COMMIT arg);</li>
 *   <li>schemaVersion — Flyway's latest applied migration, re-read at most
 *       once a minute (another task of a rolling deploy may migrate the
 *       shared database); null when no Flyway bean exists (H2 profiles) or
 *       the history cannot be read;</li>
 *   <li>environment — {@code modocument.deploy.environment}
 *       (MODOCUMENT_DEPLOY_ENVIRONMENT, set per env by Terraform), default
 *       {@code local}.</li>
 * </ul>
 */
@Component
public class SystemVersionService {

    private static final Logger log = LoggerFactory.getLogger(SystemVersionService.class);

    private static final long SCHEMA_TTL_NANOS = 60_000_000_000L;
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private final String version;
    private final String commit;
    private final String buildTime;
    private final String environment;
    private final ObjectProvider<Flyway> flyway;

    private volatile SchemaReading schema;

    public SystemVersionService(ObjectProvider<BuildProperties> buildProperties,
                                ObjectProvider<GitProperties> gitProperties,
                                ObjectProvider<Flyway> flyway,
                                @Value("${modocument.deploy.environment:local}") String environment) {
        BuildProperties build = buildProperties.getIfAvailable();
        GitProperties git = gitProperties.getIfAvailable();
        this.version = build == null ? null : build.getVersion();
        this.buildTime = build == null || build.getTime() == null ? null : build.getTime().toString();
        this.commit = git == null ? null : git.getShortCommitId();
        this.environment = environmentName(environment);
        this.flyway = flyway;
    }

    /** The running build, schema and environment, as of now. */
    public VersionInfo current() {
        return new VersionInfo(version, commit, buildTime, schemaVersion(), deployedAt(), environment);
    }

    /** Release version from pom.xml, or null when the build carries no build-info. */
    public String version() {
        return version;
    }

    /** Deployment name, never null. */
    public String environment() {
        return environment;
    }

    private String schemaVersion() {
        Flyway migrations = flyway.getIfAvailable();
        if (migrations == null) {
            return null;
        }
        SchemaReading cached = schema;
        long now = System.nanoTime();
        if (cached != null && now - cached.readAtNanos() < SCHEMA_TTL_NANOS) {
            return cached.version();
        }
        String current = readSchemaVersion(migrations);
        schema = new SchemaReading(current, now);
        return current;
    }

    private static String readSchemaVersion(Flyway migrations) {
        try {
            MigrationInfo current = migrations.info().current();
            return current == null || current.getVersion() == null
                    ? null : current.getVersion().getVersion();
        } catch (RuntimeException e) {
            log.warn("Schema version unavailable ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * First start of this version in this environment. Null until the Wave 2
     * DeployedVersion table (V240) and its startup recorder exist.
     */
    private String deployedAt() {
        return null;
    }

    private static String environmentName(String configured) {
        String name = configured == null ? "" : configured.strip();
        if (name.isEmpty()) {
            return "local";
        }
        if (!ENVIRONMENT_NAME.matcher(name).matches()) {
            log.warn("modocument.deploy.environment is not a plain name (letters, digits, . _ -, "
                    + "max 32); reporting 'unknown'");
            return "unknown";
        }
        return name;
    }

    private record SchemaReading(String version, long readAtNanos) {
    }
}
