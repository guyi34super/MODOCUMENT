package com.modocument.infrastructure.export;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-role export row caps with a stated reason above the cap (ws18, gate
 * D2, PRIV-002) — and the append-only EXPORT LOG, which lives in the audit
 * trail. Called by IngestionExportController for BOTH export paths (file
 * download and database push) after the rows are counted and BEFORE
 * anything is exported:
 * <ul>
 *   <li>rows <= cap: allowed; once the export succeeded,
 *       {@link #recordExport} writes EXPORT_RUN (who via the audit actor,
 *       format/target, rowCount);</li>
 *   <li>rows > cap, no reason: REFUSED — EXPORT_BLOCKED (DENIED, its own
 *       transaction, so it survives the refusal) + an IMMEDIATE alert
 *       (rule export-blocked), then {@link ExportCapExceededException}
 *       (403 EXPORT_CAP_EXCEEDED);</li>
 *   <li>rows > cap WITH a reason: allowed — EXPORT_OVER_CAP_WITH_REASON
 *       (SUCCESS, the reason verbatim in NewValues) + an informational
 *       alert (rule export-over-cap), then the normal EXPORT_RUN.</li>
 * </ul>
 * The alerts need no code here: the immediate rules match the audit
 * actions (the audit trail is the single event source).
 *
 * <p><b>Per-bank caps (ws20):</b> the READ_WRITE and ADMIN caps are the
 * settings {@code export.cap.readWrite} / {@code export.cap.admin} of the
 * caller's bank ({@code TenantContext}), read per export; with nothing
 * stored they are {@code modocument.export.caps.*}. Every other role keeps
 * its yml cap (none configured = 0, fail closed).
 */
@Service
public class ExportGuard {

    public static final String EXPORT_RUN = "EXPORT_RUN";
    public static final String EXPORT_BLOCKED = "EXPORT_BLOCKED";
    public static final String EXPORT_OVER_CAP_WITH_REASON = "EXPORT_OVER_CAP_WITH_REASON";

    private final ExportCapProperties properties;
    private final AuditRecorder audit;
    private final CurrentUserProvider currentUser;
    /** Null = the yml caps only (unit-test wiring). */
    private final SettingsReader settings;

    public ExportGuard(ExportCapProperties properties, AuditRecorder audit,
                       CurrentUserProvider currentUser) {
        this(properties, audit, currentUser, null);
    }

    @Autowired
    public ExportGuard(ExportCapProperties properties, AuditRecorder audit,
                       CurrentUserProvider currentUser, SettingsReader settings) {
        this.properties = properties;
        this.audit = audit;
        this.currentUser = currentUser;
        this.settings = settings;
    }

    /** The row cap of {@code role} for one bank's users (0 = always needs a reason). */
    public int capFor(Role role, int bankId) {
        String key = capKey(role);
        if (settings == null || key == null) {
            return properties.capFor(role);
        }
        return Math.max(0, settings.getInt(key, bankId));
    }

    /** {@link #capFor(Role, int)} for the caller's bank (else the platform layer). */
    private int currentCapFor(Role role) {
        String key = capKey(role);
        if (settings == null || key == null) {
            return properties.capFor(role);
        }
        return Math.max(0, settings.getIntForCurrentBank(key));
    }

    private static String capKey(Role role) {
        if (role == Role.READ_WRITE) {
            return SettingKeys.EXPORT_CAP_READ_WRITE;
        }
        if (role == Role.ADMIN) {
            return SettingKeys.EXPORT_CAP_ADMIN;
        }
        return null;
    }

    /**
     * One export attempt.
     *
     * @param type   "file" or "database"
     * @param format the file format (CSV, XLSX, ...) or the database target
     *               description (never credentials)
     * @param reason the caller's stated reason, raw (validated here)
     */
    public record Attempt(int jobId, String type, String format, int rows, String reason) {
    }

    /** What {@link #authorize} decided for an allowed export. */
    public record Decision(int cap, boolean overCap, String reason) {
    }

    /**
     * Applies the caller's role cap. Returns the decision for an allowed
     * export; throws {@link ExportCapExceededException} (already audited) for
     * a refused one, and IllegalArgumentException (400) for an over-long
     * reason.
     */
    public Decision authorize(Attempt attempt) {
        String reason = normalizeReason(attempt.reason());
        Role role = currentUser.role();
        int cap = currentCapFor(role);
        if (attempt.rows() <= cap) {
            return new Decision(cap, false, null);
        }
        Map<String, Object> values = baseValues(attempt, role, cap);
        if (reason == null) {
            audit.record(AuditEventSpec.builder(EXPORT_BLOCKED, AuditOutcome.DENIED)
                    .entity("IngestionJob", String.valueOf(attempt.jobId()))
                    .newValues(values)
                    .build());
            throw new ExportCapExceededException(cap, attempt.rows(), properties.getReasonMaxLength());
        }
        values.put("reason", reason);
        audit.record(AuditEventSpec.builder(EXPORT_OVER_CAP_WITH_REASON, AuditOutcome.SUCCESS)
                .entity("IngestionJob", String.valueOf(attempt.jobId()))
                .newValues(values)
                .build());
        return new Decision(cap, true, reason);
    }

    /** The export-log row: written once the export itself succeeded. */
    public void recordExport(Attempt attempt, Decision decision, int exportedRows) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("jobId", attempt.jobId());
        values.put("type", attempt.type());
        values.put("format", attempt.format());
        values.put("rowCount", exportedRows);
        values.put("cap", decision.cap());
        values.put("overCap", decision.overCap());
        audit.record(AuditEventSpec.builder(EXPORT_RUN, AuditOutcome.SUCCESS)
                .entity("IngestionJob", String.valueOf(attempt.jobId()))
                .newValues(values)
                .build());
    }

    /**
     * Trimmed reason, control characters removed; null when absent/blank.
     * Over the configured length is a 400, not a silent truncation — the
     * recorded justification must be exactly what the user stated.
     */
    String normalizeReason(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > properties.getReasonMaxLength()) {
            throw new IllegalArgumentException("The export reason must be at most "
                    + properties.getReasonMaxLength() + " characters.");
        }
        return cleaned;
    }

    private static Map<String, Object> baseValues(Attempt attempt, Role role, int cap) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("jobId", attempt.jobId());
        values.put("type", attempt.type());
        values.put("format", attempt.format());
        values.put("rows", attempt.rows());
        values.put("cap", cap);
        values.put("role", role == null ? null : role.name());
        return values;
    }
}
