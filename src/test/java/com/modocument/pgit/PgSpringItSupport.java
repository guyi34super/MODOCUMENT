package com.modocument.pgit;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Boots the REAL application context against the migrated PostgreSQL
 * database — on the {@code modocument_app} datasource, i.e. with row level
 * security ENFORCED — with Flyway off (PgItSupport already migrated as the
 * owner) and {@code ddl-auto=validate}, so every JPA mapping is checked
 * against the real Flyway schema instead of an H2 replica. Subclasses share
 * one cached context.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PgSpringItSupport extends PgItSupport {

    /** Same dev-only secret the H2 test profile uses (permit-dev-paths on). */
    static final String TEST_JWT_SECRET =
            "modocument-local-dev-secret-change-in-production-0123456789";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        Pg database = pg();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Migrated by PgItSupport as the owner; the app role must not (and
        // could not) run DDL.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"));
        registry.add("modocument.security.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("modocument.security.permit-dev-paths", () -> "true");
        registry.add("modocument.security.ssrf.enabled", () -> "false");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        // The real SQL store, persisted in the RLS-protected tables; the
        // worker/sweeper stay off so tests are deterministic.
        registry.add("extraction.store", () -> "sql");
        registry.add("extraction.worker.enabled", () -> "false");
        registry.add("extraction.kafka.enabled", () -> "false");
        registry.add("modocument.storage.root", () -> STORAGE_ROOT);
    }

    private static final String STORAGE_ROOT = createStorageRoot();

    private static String createStorageRoot() {
        try {
            Path root = Files.createTempDirectory("modocument-pgit-storage");
            root.toFile().deleteOnExit();
            return root.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
