package com.modocument.application.privacy;

/**
 * Write-site scrubbing for exception text that gets PERSISTED (as opposed to
 * logged — log output is already covered by the logback redaction converters,
 * see {@code infrastructure/logging}). Database columns such as
 * {@code IngestionJob.ErrorMessage}, {@code ExtractionTask.LastError} and
 * {@code ExtractionAuditEvent.Detail} store raw driver/parser messages, which
 * can quote the very VALUES that failed — run them through here first.
 *
 * <p>PRIV-005 (log part) / SEC-019: same pattern set as the log converters
 * ({@link PiiPatterns}), so what is safe for a log line is exactly what is
 * safe for a persisted error column.
 */
public final class LogSafe {

    private LogSafe() {
    }

    /** Masks PII and credential shapes; null-safe. See {@link PiiPatterns#scrub}. */
    public static String scrub(String text) {
        return PiiPatterns.scrub(text);
    }
}
