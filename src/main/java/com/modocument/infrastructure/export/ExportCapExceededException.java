package com.modocument.infrastructure.export;

/**
 * An export above the caller's role cap without a stated reason (ws18, gate
 * D2). Answered 403 with a stable machine code so the SPA can prompt for a
 * reason and retry: {@code {"code":"EXPORT_CAP_EXCEEDED","cap":5000,"rows":10000,...}}.
 * Already audited (EXPORT_BLOCKED) and alerted by the time it is thrown.
 */
public class ExportCapExceededException extends RuntimeException {

    public static final String CODE = "EXPORT_CAP_EXCEEDED";

    private final int cap;
    private final int rows;
    private final int reasonMaxLength;

    public ExportCapExceededException(int cap, int rows, int reasonMaxLength) {
        super("This export has " + rows + " rows, above your role's limit of " + cap
                + ". State a reason for the export to continue; it will be recorded and "
                + "the security team notified.");
        this.cap = cap;
        this.rows = rows;
        this.reasonMaxLength = reasonMaxLength;
    }

    public int cap() {
        return cap;
    }

    public int rows() {
        return rows;
    }

    public int reasonMaxLength() {
        return reasonMaxLength;
    }
}
