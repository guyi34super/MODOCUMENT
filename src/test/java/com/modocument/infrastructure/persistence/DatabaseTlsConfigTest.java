package com.modocument.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB TLS, application side (C4 / SEC-028): pins how application.yml builds
 * the JDBC URLs. The runtime datasource carries sslmode (default
 * {@code prefer}, production {@code MODOCUMENT_DB_SSLMODE=require} /
 * {@code verify-full}), and Flyway's URL is always the very same string —
 * migrations can never negotiate weaker TLS than the app. (Flyway's URL is
 * explicit for a second reason: derived from the TenantAwareDataSource
 * wrapper, Boot cannot read it back and startup fails.)
 */
class DatabaseTlsConfigTest {

    private static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        // First YAML document = the profile-less defaults.
        environment.getPropertySources().addLast(documents.get(0));
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        return environment;
    }

    @Test
    void defaultUrlPrefersTlsSoLocalTlsLessPostgresKeepsWorking() throws Exception {
        StandardEnvironment environment = environment(Map.of());

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/modocument?sslmode=prefer");
        assertThat(environment.getProperty("spring.flyway.url"))
                .isEqualTo(environment.getProperty("spring.datasource.url"));
    }

    @Test
    void productionSslmodeReachesBothTheAppAndFlyway() throws Exception {
        StandardEnvironment environment = environment(Map.of("MODOCUMENT_DB_SSLMODE", "verify-full"));

        assertThat(environment.getProperty("spring.datasource.url"))
                .endsWith("?sslmode=verify-full");
        assertThat(environment.getProperty("spring.flyway.url"))
                .isEqualTo(environment.getProperty("spring.datasource.url"));
    }

    @Test
    void anOperatorSuppliedUrlIsUsedVerbatimByBoth() throws Exception {
        String url = "jdbc:postgresql://db.internal:5432/modocument?sslmode=require";
        StandardEnvironment environment = environment(Map.of("MODOCUMENT_DB_URL", url));

        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(url);
        assertThat(environment.getProperty("spring.flyway.url")).isEqualTo(url);
    }
}
