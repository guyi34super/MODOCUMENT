package com.modocument.application.audit;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.domain.model.AuditOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The audit action catalogue (ws28): every action name the application
 * writes to the append-only AuditEvent table, with the category the audit
 * viewer's filter picker groups it under ({@code GET /api/admin/audit/actions}).
 * Documented one-for-one in {@code docs/security/audit-events.md}
 * ({@code AuditActionCatalogueDocTest} keeps the two in step, and checks that
 * every action an alert rule watches is catalogued).
 *
 * <p>The constants are the ws28 additions; older actions are written as
 * literals at their call sites and only listed here.
 */
public final class AuditActions {

    // ---- ws28: gap fill ----------------------------------------------------
    public static final String TEMPLATE_CREATED = "TEMPLATE_CREATED";
    public static final String TEMPLATE_FIELD_CREATED = "TEMPLATE_FIELD_CREATED";
    public static final String TEMPLATE_FIELD_UPDATED = "TEMPLATE_FIELD_UPDATED";
    public static final String TEMPLATE_FIELD_DELETED = "TEMPLATE_FIELD_DELETED";
    public static final String VALIDATION_RULE_CREATED = "VALIDATION_RULE_CREATED";
    public static final String VALIDATION_RULE_UPDATED = "VALIDATION_RULE_UPDATED";
    public static final String VALIDATION_RULE_DELETED = "VALIDATION_RULE_DELETED";
    public static final String FILE_UPLOADED = "FILE_UPLOADED";
    public static final String INGESTION_JOB_CREATED = "INGESTION_JOB_CREATED";
    public static final String INGESTION_REMAPPED = "INGESTION_REMAPPED";
    public static final String INGESTION_ROWS_EDITED = "INGESTION_ROWS_EDITED";
    public static final String DOC_GENERATED = "DOC_GENERATED";
    public static final String DOC_DOWNLOADED = "DOC_DOWNLOADED";
    public static final String EXPORT_DOWNLOADED = "EXPORT_DOWNLOADED";
    public static final String TOKEN_REFRESHED = "TOKEN_REFRESHED";

    /** ws32: a record search (query hash prefix and result count only, never values). */
    public static final String TEMPLATE_RECORDS_SEARCHED = "TEMPLATE_RECORDS_SEARCHED";
    /** ws32: a data source of a PDF template was added, saved or removed (counts only). */
    public static final String DOC_BLUEPRINT_MAPPING_SAVED = "DOC_BLUEPRINT_MAPPING_SAVED";
    /** ws32: a document was filled from records (region keys and record ids only). */
    public static final String DOC_FILLED_FROM_RECORD = "DOC_FILLED_FROM_RECORD";
    /** ws33: a template's field rules were saved (counts only). */
    public static final String DOC_FIELD_RULES_SAVED = "DOC_FIELD_RULES_SAVED";
    /** ws33: field rules were imported from a flow template (counts and ids only). */
    public static final String DOC_FIELD_RULES_IMPORTED = "DOC_FIELD_RULES_IMPORTED";
    /** ws33: a document was validated; SUCCESS = VALIDATED, FAILED = stays DRAFT (counts only). */
    public static final String DOC_VALIDATED = "DOC_VALIDATED";

    /**
     * A refusal outside method security: an AccessDeniedException raised
     * inside a use case, an owner-scoped 404 (ownership check rejected) or an
     * invalid/revoked bearer token. AGGREGATED by the recorder: at most one
     * row per (bank, actor-or-ip, entity type) per minute, with
     * {@code newValues.count}.
     */
    public static final String ACCESS_DENIED = "ACCESS_DENIED";

    /** Read audit: once per actor, entity and hour (deduplicated in memory). */
    public static final String SENSITIVE_DATA_VIEWED = "SENSITIVE_DATA_VIEWED";

    /** The audit viewer was opened: once per actor per 15 minutes. */
    public static final String AUDIT_LOG_VIEWED = "AUDIT_LOG_VIEWED";
    public static final String AUDIT_LOG_EXPORTED = "AUDIT_LOG_EXPORTED";

    /** {@code newValues.kind} of an ACCESS_DENIED row. */
    public static final String DENIED_KIND_USE_CASE = "USE_CASE";
    public static final String DENIED_KIND_NOT_FOUND = "NOT_FOUND_PROBE";
    public static final String DENIED_KIND_INVALID_TOKEN = "INVALID_TOKEN";

    /** One catalogue entry. */
    public record Action(String name, String category) {
    }

    private static final Map<String, String> CATALOGUE = build();

    private AuditActions() {
    }

    /** Every action, grouped by category in display order. */
    public static List<Action> all() {
        List<Action> actions = new ArrayList<>(CATALOGUE.size());
        CATALOGUE.forEach((name, category) -> actions.add(new Action(name, category)));
        return Collections.unmodifiableList(actions);
    }

    public static Optional<String> categoryOf(String action) {
        return Optional.ofNullable(CATALOGUE.get(action));
    }

    /**
     * The ACCESS_DENIED spec of an owner-scoped 404: an ownership check
     * rejected the id (another user's or bank's row, or no row at all — the
     * caller cannot tell, the trail records the probe). Aggregated by the
     * recorder like every ACCESS_DENIED.
     */
    public static AuditEventSpec notFoundProbe(String entityType, Object entityId) {
        return AuditEventSpec.builder(ACCESS_DENIED, AuditOutcome.DENIED)
                .entity(entityType, entityId == null ? null : String.valueOf(entityId))
                .newValues(Map.of("kind", DENIED_KIND_NOT_FOUND))
                .build();
    }

    private static Map<String, String> build() {
        Map<String, String> c = new LinkedHashMap<>();
        add(c, "Authentication", "LOGIN_SUCCESS", "LOGIN_FAILED", "LOGOUT", "ACCOUNT_LOCKED",
                TOKEN_REFRESHED, "TOKEN_REFRESH_REUSE_DETECTED", "SESSION_IDLE_EXPIRED",
                "SESSION_ABSOLUTE_EXPIRED", "TOKEN_VERSION_BUMPED", "PASSWORD_CHANGED",
                "PASSWORD_REHASHED", "MFA_ENROLLED", "MFA_ENABLED", "MFA_DISABLED",
                "RECOVERY_CODE_USED", "IDENTITY_LINKED", "USER_PROVISIONED_OIDC",
                "USER_ROLE_MAPPED_OIDC");
        add(c, "Access control", "AUTHORIZATION_DENIED", ACCESS_DENIED, "SELF_APPROVAL_BLOCKED",
                "DOC_SELF_RELEASE_BLOCKED", "SOD_SCAN_COMPLETED", "SOD_VIOLATION_FOUND");
        add(c, "User administration", "BOOTSTRAP_ADMIN_CREATED", "USER_CREATED", "USER_UPDATED",
                "USER_ROLE_CHANGED", "USER_DISABLED", "USER_ENABLED", "USER_UNLOCKED",
                "USER_PASSWORD_RESET", "USER_MFA_RESET", "USER_LIST_EXPORTED",
                "ACCESS_REVIEW_GENERATED");
        add(c, "Maker/checker", "CHANGE_REQUESTED", "CHANGE_APPROVED", "CHANGE_REJECTED",
                "CHANGE_CANCELLED", "CHANGE_APPLY_FAILED");
        add(c, "Templates", TEMPLATE_CREATED, "TEMPLATE_UPDATED", "TEMPLATE_DELETED",
                "TEMPLATE_VISIBILITY_CHANGED", TEMPLATE_FIELD_CREATED, TEMPLATE_FIELD_UPDATED,
                TEMPLATE_FIELD_DELETED, VALIDATION_RULE_CREATED, VALIDATION_RULE_UPDATED,
                VALIDATION_RULE_DELETED);
        add(c, "Ingestion", FILE_UPLOADED, INGESTION_JOB_CREATED, INGESTION_REMAPPED,
                INGESTION_ROWS_EDITED, "INGESTION_PARSE_FALLBACK", "JOB_DELETED");
        add(c, "Exports", "EXPORT_RUN", EXPORT_DOWNLOADED, "EXPORT_BLOCKED",
                "EXPORT_OVER_CAP_WITH_REASON");
        add(c, "Documents", DOC_GENERATED, DOC_DOWNLOADED, "DOC_SUBMITTED", "DOC_RELEASED",
                "DOC_RELEASE_REJECTED", "DOC_BLUEPRINT_CREATED", "DOC_BLUEPRINT_VERSION_SAVED",
                "DOC_LEGACY_PURGED", DOC_BLUEPRINT_MAPPING_SAVED, DOC_FILLED_FROM_RECORD,
                DOC_FIELD_RULES_SAVED, DOC_FIELD_RULES_IMPORTED, DOC_VALIDATED);
        add(c, "Privacy", SENSITIVE_DATA_VIEWED, TEMPLATE_RECORDS_SEARCHED, "DSR_SEARCH", "DSR_EXPORTED",
                "DSR_ERASED", "RETENTION_PURGE_COMPLETED");
        add(c, "Audit", AUDIT_LOG_VIEWED, AUDIT_LOG_EXPORTED);
        add(c, "AI", "AI_CALL", "AI_PROMPT_REFUSED", "AI_PROMPT_BLOCKED_PII",
                "AI_OUTPUT_BLOCKED", "AI_OUTPUT_FLAGGED", "AI_CIRCUIT_OPENED");
        add(c, "Settings", "SETTING_UPDATED", "SECURITY_SETTING_UPDATED",
                "PLATFORM_SETTING_UPDATED");
        add(c, "Platform", "PLATFORM_BOOTSTRAPPED", "BANK_ONBOARDED", "BANK_ONBOARDING_REVERTED",
                "BANK_SUSPENDED", "BANK_REACTIVATED", "BANK_ADMIN_ADDED", "EGRESS_RULE_REQUESTED",
                "EGRESS_RULE_APPROVED", "EGRESS_RULE_REVOKED", "ANNOUNCEMENT_PUBLISHED",
                "ANNOUNCEMENT_ARCHIVED", "VERSION_DEPLOYED");
        add(c, "Integration", "API_CLIENT_CREATED", "API_CLIENT_ROTATED", "API_CLIENT_REVOKED",
                "API_CLIENT_TOKEN_ISSUED", "API_CLIENT_AUTH_FAILED",
                "API_CLIENT_INITIAL_SECRET_READ", "INTEGRATION_API_CALL",
                "INTEGRATION_CONNECTION_CHANGED", "INTEGRATION_FLOW_SAVED",
                "INTEGRATION_FLOW_ENABLED", "INTEGRATION_FLOW_DISABLED", "INTEGRATION_FLOW_DELETED",
                "INTEGRATION_RUN_COMPLETED", "INTEGRATION_RUN_FAILED", "INTEGRATION_DELIVERY_DEAD",
                "AGENT_REGISTERED", "AGENT_ENROLLED", "AGENT_ENROLMENT_INVALID", "AGENT_ONLINE",
                "AGENT_OFFLINE", "AGENT_REVOKED");
        add(c, "Alerting & operations", "ALERT_SENT", "ALERT_DELIVERY_FAILED",
                "HOUSEKEEPING_COMPLETED");
        return Collections.unmodifiableMap(c);
    }

    private static void add(Map<String, String> catalogue, String category, String... names) {
        for (String name : names) {
            if (catalogue.putIfAbsent(name, category) != null) {
                throw new IllegalStateException("Audit action listed twice: " + name);
            }
        }
    }
}
