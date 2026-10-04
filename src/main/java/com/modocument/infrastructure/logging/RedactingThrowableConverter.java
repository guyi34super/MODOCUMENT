package com.modocument.infrastructure.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import com.modocument.application.privacy.LogSafe;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * Replaces Boot's default {@code %wEx} in the console pattern (registered as
 * {@code %maskEx}): the rendered stack trace is scrubbed before it is
 * written. Exception MESSAGES travel inside the stack-trace text — a
 * PostgreSQL constraint violation spells out the offending VALUES, a parser
 * failure quotes the cell content — so masking {@code %m} alone
 * ({@link RedactingConverter}) would still leak PII through every logged
 * throwable. Extends the extended-whitespace converter so Boot's trace
 * enrichment and blank-line framing are preserved; only the text changes.
 */
public class RedactingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return LogSafe.scrub(super.throwableProxyToString(tp));
    }
}
