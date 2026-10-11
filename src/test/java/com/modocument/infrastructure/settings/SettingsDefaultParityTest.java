package com.modocument.infrastructure.settings;

import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingType;
import com.modocument.domain.model.settings.SettingValues;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity at the source (ws20 Wave 2): the shipped default the settings layer
 * falls back to must be EXACTLY what Spring binds into the consumer from the
 * same property — for the shipped application.yml and for operator
 * overrides written in Spring's own spellings (booleans as yes/on/1,
 * durations as bare milliseconds or ISO-8601). Otherwise an environment
 * that configured a consumer one way would silently get another value the
 * moment the consumer started reading through SettingsReader.
 */
class SettingsDefaultParityTest {

    @Test
    void theShippedConfigurationReadsTheSameAsSpringBindsIt() throws IOException {
        assertParity(environment(Map.of()));
    }

    @Test
    void springSpellingsInOverridesReadTheSameAsSpringBindsThem() throws IOException {
        StandardEnvironment environment = environment(Map.of(
                "modocument.privacy.masking.screen-working", "yes",
                "modocument.parsing.deterministic-enabled", "off",
                "modocument.doccreation.generate-ai-enabled", "0",
                "mapping.llm-fallback-enabled", "ON",
                "modocument.security.session.idle-timeout", "3600000",
                "modocument.ratelimit.window-millis", "7200000",
                "modocument.alerts.rules[13].window", "PT20M",
                "modocument.chat-edit.full-table-max-change-ratio", "0.45"));
        assertParity(environment);
        SettingsCatalogue catalogue = new SettingsCatalogue(SettingDefinitions.ALL, environment);
        assertThat(catalogue.defaultValue(catalogue.require("privacy.masking.screenWorking")))
                .isEqualTo(Boolean.TRUE);
        assertThat(catalogue.defaultValue(catalogue.require("security.session.idleTimeout")))
                .isEqualTo(Duration.ofHours(1));
    }

    private static void assertParity(StandardEnvironment environment) {
        SettingsCatalogue catalogue = new SettingsCatalogue(SettingDefinitions.ALL, environment);
        // The conversion Spring applies to the resolved text for @Value and
        // @ConfigurationProperties alike.
        ApplicationConversionService conversion = new ApplicationConversionService();
        List<String> drift = new ArrayList<>();
        int compared = 0;
        for (SettingDefinition definition : catalogue.all()) {
            String property = catalogue.resolvedPropertyName(definition);
            if (property == null || !environment.containsProperty(property)) {
                continue; // a code default (or an @Value default): no binding to compare
            }
            Object spring = conversion.convert(environment.getProperty(property),
                    javaType(definition.type()));
            Object ours = catalogue.defaultValue(definition);
            compared++;
            if (!same(definition.type(), ours, spring)) {
                drift.add(definition.key() + ": settings read " + ours + ", Spring binds "
                        + spring + " (" + property + ")");
            }
        }
        assertThat(compared).as("properties compared").isGreaterThan(30);
        assertThat(drift).isEmpty();
    }

    private static boolean same(SettingType type, Object ours, Object spring) {
        if (spring == null) {
            return ours == null;
        }
        return switch (type) {
            case INT -> SettingValues.same(type, ours, ((Number) spring).intValue());
            case DECIMAL -> ((BigDecimal) ours).compareTo((BigDecimal) spring) == 0;
            case ENUM -> String.valueOf(ours).equalsIgnoreCase(String.valueOf(spring).trim());
            default -> SettingValues.same(type, ours, spring);
        };
    }

    private static Class<?> javaType(SettingType type) {
        return switch (type) {
            case INT -> Long.class;
            case DECIMAL -> BigDecimal.class;
            case BOOLEAN -> Boolean.class;
            case DURATION -> Duration.class;
            case ENUM -> String.class;
        };
    }

    private static StandardEnvironment environment(Map<String, Object> overrides)
            throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        environment.getPropertySources().addFirst(yaml.get(0));
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("ops", overrides));
        }
        return environment;
    }
}
