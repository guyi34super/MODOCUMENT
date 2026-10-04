package com.modocument.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.privacy.PiiPatterns;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structured-logs path (gate C3): Boot's {@code StructuredLogEncoder}
 * builds the ECS JSON documents itself, bypassing the {@code %mask}/
 * {@code %maskEx} pattern converters, so redaction there rides on
 * {@link RedactingJsonMembersCustomizer} registered through
 * {@code logging.structured.json.customizer}.
 *
 * <p>This test exercises the REAL encoder exactly as production wires it —
 * the Spring {@link Environment} placed in the logback context object map is
 * where the encoder reads the customizer property from (see
 * {@code StructuredLogEncoder.createFormatter}) — rather than booting a
 * second full application context just to flip the console encoder. A
 * companion assertion pins the application.yml wiring: the
 * {@code structured-logs} profile document must carry the customizer
 * property, or production would silently log unscrubbed JSON.
 */
class StructuredLogRedactionTest {

    private static final String CANARY_NIC = "A1234567890123";
    private static final String CANARY_ACCOUNT = "12345678901234";
    private static final String CANARY_EMAIL = "canary@example.test";
    private static final String CANARY_PASSWORD = "CanaryPass123!x";
    private static final String CANARY_JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjYW5hcnkifQ.c2lnbmF0dXJl";

    private static final String REQUEST_ID = "0199c2f4-7d3a-7b5c-8a3e-4f2b1c9d0e7a";

    @Test
    void ecsJsonOutputCarriesNoCanaries() throws Exception {
        LoggerContext context = new LoggerContext();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.structured.json.customizer",
                        RedactingJsonMembersCustomizer.class.getName());
        context.putObject(Environment.class.getName(), environment);

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();

        LoggingEvent event = new LoggingEvent("x",
                context.getLogger("com.modocument.api.controller.ApiExceptionHandler"),
                Level.ERROR,
                "job failed for NIC " + CANARY_NIC + " account " + CANARY_ACCOUNT
                        + " holder " + CANARY_EMAIL + " password=" + CANARY_PASSWORD
                        + " jwt " + CANARY_JWT,
                new IllegalStateException("constraint violated by NIC " + CANARY_NIC),
                null);
        event.setMDCPropertyMap(Map.of(
                "requestId", REQUEST_ID, "userId", "4242", "bankId", "1"));

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        for (String canary : List.of(CANARY_NIC, CANARY_ACCOUNT, CANARY_EMAIL,
                CANARY_PASSWORD, CANARY_JWT)) {
            assertThat(json).doesNotContain(canary);
        }
        assertThat(json).contains(PiiPatterns.MASK);

        // Still a valid, useful ECS document: message scrubbed (not dropped),
        // error.* scrubbed, MDC identity fields intact and unredacted.
        JsonNode doc = new ObjectMapper().readTree(json);
        assertThat(doc.path("message").asText())
                .contains("job failed for NIC").contains(PiiPatterns.MASK);
        assertThat(doc.path("error").path("message").asText()).contains(PiiPatterns.MASK);
        assertThat(doc.path("error").path("stack_trace").asText())
                .contains("IllegalStateException").doesNotContain(CANARY_NIC);
        assertThat(doc.path("requestId").asText()).isEqualTo(REQUEST_ID);
        assertThat(doc.path("userId").asText()).isEqualTo("4242");
        assertThat(doc.path("bankId").asText()).isEqualTo("1");
    }

    @Test
    void structuredLogsProfileRegistersTheCustomizer() throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        PropertySource<?> structuredProfile = documents.stream()
                .filter(source -> "structured-logs".equals(
                        source.getProperty("spring.config.activate.on-profile")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "application.yml lost its structured-logs profile document"));

        assertThat(structuredProfile.getProperty("logging.structured.json.customizer"))
                .as("the structured path has no %mask converters — losing this "
                        + "property means unscrubbed JSON logs in production")
                .isEqualTo(RedactingJsonMembersCustomizer.class.getName());
    }
}
