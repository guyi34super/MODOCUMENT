package com.modocument.infrastructure.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.modocument.application.privacy.LogSafe;

/**
 * Logback conversion-word converter that masks PII and credentials in the
 * formatted message of the human-readable console pattern (PRIV-005 log
 * part, SEC-019). Registered in {@code logback-spring.xml} as
 * {@code %mask}, which replaces {@code %m}:
 *
 * <pre>
 *   &lt;conversionRule conversionWord="mask"
 *                   class="com.modocument.infrastructure.logging.RedactingConverter"/&gt;
 * </pre>
 *
 * <p>Exception/stack-trace text is handled by the companion
 * {@link RedactingThrowableConverter} ({@code %maskEx}), and the
 * structured-logs profile (Boot's {@code StructuredLogEncoder}, which never
 * runs pattern converters) by {@link RedactingJsonMembersCustomizer}. All
 * three delegate to the same pattern set,
 * {@link com.modocument.application.privacy.PiiPatterns}.
 */
public class RedactingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogSafe.scrub(super.convert(event));
    }
}
