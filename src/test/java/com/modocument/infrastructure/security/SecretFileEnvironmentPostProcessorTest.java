package com.modocument.infrastructure.security;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code <NAME>_FILE} secret indirection (SEC-018): file-only resolution,
 * env-wins-when-alone, the both-set conflict rule, fail-fast on bad files,
 * relaxed binding for SPRING_* names, the registration in spring.factories —
 * and that no secret value ever reaches a log line or exception message.
 */
class SecretFileEnvironmentPostProcessorTest {

    private static final String JWT_SECRET = "file-jwt-secret-0123456789abcdefghijklmnop";

    @TempDir
    Path dir;

    private Log log;
    private SecretFileEnvironmentPostProcessor processor;

    @BeforeEach
    void setUp() {
        log = mock(Log.class);
        DeferredLogFactory logFactory = destination -> log;
        processor = new SecretFileEnvironmentPostProcessor(logFactory);
    }

    private Path secretFile(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private void run(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, new SpringApplication());
    }

    @Test
    void fileOnlyValueIsPublishedTrimmedAndResolvesTheYamlPlaceholder() throws Exception {
        // Secret files usually end with a newline (echo/openssl output).
        Path file = secretFile("jwt", "  " + JWT_SECRET + "\n\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODOCUMENT_JWT_SECRET_FILE", file.toString());

        run(environment);

        assertThat(environment.getProperty("MODOCUMENT_JWT_SECRET")).isEqualTo(JWT_SECRET);
        // Exactly how application.yml consumes it.
        assertThat(environment.resolvePlaceholders("${MODOCUMENT_JWT_SECRET:}"))
                .isEqualTo(JWT_SECRET);
    }

    @Test
    void plainEnvironmentValueAloneIsLeftUntouched() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODOCUMENT_JWT_SECRET", "env-value-0123456789abcdefghijklmnop");
        int sourcesBefore = environment.getPropertySources().size();

        run(environment);

        assertThat(environment.getProperty("MODOCUMENT_JWT_SECRET"))
                .isEqualTo("env-value-0123456789abcdefghijklmnop");
        assertThat(environment.getPropertySources().size()).isEqualTo(sourcesBefore);
    }

    @Test
    void bothSetWithDifferentValuesFailsStartupWithoutRevealingEither() throws Exception {
        Path file = secretFile("db", "from-file-password");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODOCUMENT_DB_PASSWORD", "from-env-password")
                .withProperty("MODOCUMENT_DB_PASSWORD_FILE", file.toString());

        assertThatThrownBy(() -> run(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_DB_PASSWORD and MODOCUMENT_DB_PASSWORD_FILE")
                .hasMessageNotContaining("from-file-password")
                .hasMessageNotContaining("from-env-password");
    }

    @Test
    void bothSetWithTheSameValueIsTolerated() throws Exception {
        Path file = secretFile("mfa", "same-mfa-key-0123456789abcdefghijklmnop\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODOCUMENT_MFA_KEY", "same-mfa-key-0123456789abcdefghijklmnop")
                .withProperty("MODOCUMENT_MFA_KEY_FILE", file.toString());

        run(environment);

        assertThat(environment.getProperty("MODOCUMENT_MFA_KEY"))
                .isEqualTo("same-mfa-key-0123456789abcdefghijklmnop");
    }

    @Test
    void blankPlainValueCountsAsUnsetAndTheFileWins() throws Exception {
        // docker-compose passes `NAME: ${NAME:-}` through as an empty string.
        Path file = secretFile("admin", "Bootstrap-Passphrase-2026!");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD", "")
                .withProperty("MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD_FILE", file.toString());

        run(environment);

        assertThat(environment.getProperty("MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD"))
                .isEqualTo("Bootstrap-Passphrase-2026!");
    }

    @Test
    void publishedValuesSitAtEnvironmentVariablePrecedence() throws Exception {
        Path file = secretFile("flyway", "owner-password");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "test", Map.of("MODOCUMENT_FLYWAY_PASSWORD_FILE", file.toString())));

        processor.postProcessEnvironment(environment, new SpringApplication());

        var names = environment.getPropertySources().stream().map(PropertySource::getName).toList();
        assertThat(names.indexOf(SecretFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isEqualTo(names.indexOf(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME) - 1)
                .isGreaterThan(names.indexOf(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME));
        assertThat(environment.getProperty("MODOCUMENT_FLYWAY_PASSWORD")).isEqualTo("owner-password");
    }

    @Test
    void springStyleNamesBindThroughRelaxedEnvironmentMapping() throws Exception {
        Path file = secretFile("ds", "datasource-password");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SPRING_DATASOURCE_PASSWORD_FILE", file.toString());

        run(environment);
        ConfigurationPropertySources.attach(environment);

        assertThat(environment.getPropertySources()
                .get(SecretFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isInstanceOf(SystemEnvironmentPropertySource.class);
        assertThat(Binder.get(environment).bind("spring.datasource.password", String.class).get())
                .isEqualTo("datasource-password");
    }

    @Test
    void missingFileFailsFastNamingThePathOnly() {
        Path missing = dir.resolve("does-not-exist");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODOCUMENT_JWT_SECRET_FILE", missing.toString());

        assertThatThrownBy(() -> run(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_JWT_SECRET_FILE")
                .hasMessageContaining(missing.toString());
    }

    @Test
    void emptyFileFailsFast() throws Exception {
        Path file = secretFile("empty", " \n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODOCUMENT_MFA_KEY_FILE", file.toString());

        assertThatThrownBy(() -> run(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is empty");
    }

    @Test
    void oversizedFileFailsFast() throws Exception {
        Path file = dir.resolve("huge");
        Files.write(file, new byte[SecretFileEnvironmentPostProcessor.MAX_SECRET_BYTES + 1]);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODOCUMENT_JWT_SECRET_FILE", file.toString());

        assertThatThrownBy(() -> run(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    void variablesOutsideTheAllowlistAreIgnored() throws Exception {
        Path file = secretFile("other", "whatever");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SOME_UNRELATED_FILE", file.toString());

        run(environment);

        assertThat(environment.getProperty("SOME_UNRELATED")).isNull();
    }

    @Test
    void theLogLineNamesTheVariableAndPathButNeverTheValue() throws Exception {
        Path file = secretFile("jwt", JWT_SECRET);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODOCUMENT_JWT_SECRET_FILE", file.toString());

        run(environment);

        ArgumentCaptor<Object> messages = ArgumentCaptor.forClass(Object.class);
        verify(log, atLeast(1)).info(messages.capture());
        assertThat(messages.getAllValues()).allSatisfy(message -> assertThat(message.toString())
                .contains("MODOCUMENT_JWT_SECRET_FILE")
                .doesNotContain(JWT_SECRET));
        verify(log, never()).debug(any());
        verify(log, never()).warn(any());
    }

    /**
     * End to end through Spring Boot itself: the processor is registered in
     * META-INF/spring.factories, so a plain SpringApplication picks it up and
     * the JWT secret resolves from the file exactly as application.yml reads
     * it.
     */
    @Test
    void isRegisteredAndAppliedByARealSpringApplication() throws Exception {
        // A real MODOCUMENT_JWT_SECRET in the runner's environment would
        // (correctly) trip the both-set rule; CI never exports one.
        assumeTrue(System.getenv("MODOCUMENT_JWT_SECRET") == null);
        Path file = secretFile("jwt-e2e", JWT_SECRET + "\n");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of("MODOCUMENT_JWT_SECRET_FILE", file.toString()))
                .run()) {
            assertThat(context.getEnvironment().getProperty("modocument.security.jwt.secret"))
                    .isEqualTo(JWT_SECRET);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
