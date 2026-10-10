package com.modocument.infrastructure.logging;

import com.modocument.application.privacy.LogSafe;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * PII/credential masking for the structured-logs profile. Boot's
 * {@code StructuredLogEncoder} (ECS/logstash/GELF) builds each JSON log
 * document itself and never runs logback pattern converters, so the
 * {@code %mask}/{@code %maskEx} rules of the human pattern cannot reach it.
 * Boot's supported seam for rewriting that output is a
 * {@link StructuredLoggingJsonMembersCustomizer} registered through
 * {@code logging.structured.json.customizer} (set in the
 * {@code structured-logs} section of application.yml); a logback TurboFilter
 * was rejected because its API can only accept or deny an event, not rewrite
 * its message.
 *
 * <p>The value processor runs over EVERY string member of the document —
 * {@code message}, {@code error.message}, {@code error.stack_trace} and the
 * MDC fields alike — through the same pattern set as the console converters
 * ({@link com.modocument.application.privacy.PiiPatterns}). MDC values
 * (requestId, userId, bankId, role, ip) pass through unchanged because the
 * patterns do not match UUIDs or short numbers.
 */
public class RedactingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogSafe::scrub));
    }
}
