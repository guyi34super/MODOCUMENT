package com.modocument.infrastructure.security;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File-based secret indirection (SEC-018): for each sensitive variable
 * {@code NAME} below, {@code NAME_FILE} may point at a file whose content
 * (whitespace-trimmed) becomes {@code NAME}'s value. That is the Docker /
 * Kubernetes secrets contract ({@code /run/secrets/<name>}, mode 0400): the
 * secret VALUE then never sits in the process environment, {@code docker
 * inspect}, the compose file or shell history — only its path does.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code NAME} set (non-blank) and no {@code NAME_FILE}: unchanged —
 *       plain environment variables stay the default path.</li>
 *   <li>{@code NAME_FILE} set and {@code NAME} unset or blank: the file's
 *       content is published as {@code NAME} at environment-variable
 *       precedence, so the existing {@code ${NAME:}} placeholders in
 *       application.yml (and Spring's relaxed binding for
 *       {@code SPRING_*} names) resolve it exactly as if it had been
 *       exported. Blank counts as unset because compose passes
 *       {@code NAME: ${NAME:-}} through as an empty string.</li>
 *   <li>Both set: tolerated only when identical; different values fail
 *       startup — an operator must never be unsure which credential is
 *       live.</li>
 *   <li>Unreadable, empty or oversized file: startup fails.</li>
 * </ul>
 *
 * <p>Neither the log line nor any exception ever contains a secret value —
 * only variable names and the file path.
 *
 * <p>Registered in {@code META-INF/spring.factories} (Spring Boot 3.5 loads
 * {@link EnvironmentPostProcessor}s from there); runs after config data is
 * loaded so the both-set check sees every source.
 */
public class SecretFileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** The variables that accept a {@code _FILE} indirection. */
    static final List<String> SECRET_VARIABLES = List.of(
            "MODOCUMENT_JWT_SECRET",
            "MODOCUMENT_MFA_KEY",
            // Runtime datasource (modocument_app role) and migration owner.
            "MODOCUMENT_DB_PASSWORD",
            "MODOCUMENT_FLYWAY_PASSWORD",
            // Standard Spring overrides documented in the Dockerfile.
            "SPRING_DATASOURCE_PASSWORD",
            "SPRING_FLYWAY_PASSWORD",
            "MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD",
            "MODOCUMENT_AI_API_KEY",
            "MODOCUMENT_OIDC_ENTRA_CLIENT_SECRET",
            "MODOCUMENT_OIDC_KEYCLOAK_CLIENT_SECRET",
            // Carries the Mongo credentials on the scale profile.
            "EXTRACTION_MONGO_URI",
            // ws18 HMAC signing keys of the alert and SIEM webhooks.
            "MODOCUMENT_ALERTS_WEBHOOK_SECRET",
            "MODOCUMENT_SIEM_WEBHOOK_SECRET");

    static final String FILE_SUFFIX = "_FILE";

    /**
     * Named like a system-environment source so Spring Boot applies its
     * environment-variable mapping (SPRING_DATASOURCE_PASSWORD →
     * spring.datasource.password) to the published values.
     */
    static final String PROPERTY_SOURCE_NAME =
            "modocumentSecretFiles-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

    /** Secrets are short; anything bigger is a mis-pointed path (or /dev/zero). */
    static final int MAX_SECRET_BYTES = 64 * 1024;

    private final Log log;

    public SecretFileEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SecretFileEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor (HIGHEST_PRECEDENCE + 10).
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String name : SECRET_VARIABLES) {
            String fileVariable = name + FILE_SUFFIX;
            String file = rawValue(environment, fileVariable);
            if (file == null || file.isBlank()) {
                continue;
            }
            Path path = Path.of(file.strip());
            String fromFile = readSecret(fileVariable, path);
            String direct = rawValue(environment, name);
            if (direct != null && !direct.isBlank()) {
                if (!direct.equals(fromFile)) {
                    throw new IllegalStateException(
                            name + " and " + fileVariable + " are both set and their values "
                                    + "differ. Set exactly one of them (prefer " + fileVariable
                                    + " with a mounted secret file).");
                }
                // Same value both ways: nothing to publish.
                continue;
            }
            resolved.put(name, fromFile);
            log.info("Resolved " + name + " from " + fileVariable + " (" + path + ")");
        }
        if (resolved.isEmpty()) {
            return;
        }
        SystemEnvironmentPropertySource source =
                new SystemEnvironmentPropertySource(PROPERTY_SOURCE_NAME, resolved);
        MutablePropertySources sources = environment.getPropertySources();
        // Environment-variable precedence: just above the real environment
        // (so a blank NAME="" passed through by compose is overridden), still
        // below system properties and command-line arguments.
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addBefore(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addFirst(source);
        }
    }

    /**
     * The highest-precedence RAW value of {@code name} (no placeholder
     * resolution — a secret is compared byte for byte, never interpreted).
     */
    private static String rawValue(ConfigurableEnvironment environment, String name) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source.getProperty(name);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    private static String readSecret(String fileVariable, Path path) {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path)) {
            // Bounded read: a path pointing at a device or a huge file must
            // fail fast instead of hanging or exhausting the heap.
            bytes = in.readNBytes(MAX_SECRET_BYTES + 1);
        } catch (IOException e) {
            throw new IllegalStateException(
                    fileVariable + " points at " + path + ", which cannot be read ("
                            + e.getClass().getSimpleName() + ").", e);
        }
        if (bytes.length > MAX_SECRET_BYTES) {
            throw new IllegalStateException(
                    fileVariable + " points at " + path + ", which is larger than "
                            + MAX_SECRET_BYTES + " bytes — not a secret file.");
        }
        String value = new String(bytes, StandardCharsets.UTF_8).strip();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    fileVariable + " points at " + path + ", which is empty.");
        }
        return value;
    }
}
