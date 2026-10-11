package com.modocument.domain.model;

/**
 * Fine-grained permissions ({@code resource:action}) that every API route is
 * mapped onto via {@code @RequiresPermission}. Roles grant sets of these (see
 * {@code RolePermissions}); the enum is only the vocabulary.
 *
 * <p>The phase-2 constants (settings, platform owner, integrations) are
 * listed as RESERVED in {@code RoutePermissionCoverageTest} until the
 * workstream that owns each one lands its routes; each workstream removes
 * its own entries.
 */
public enum Permission {

    /** Read templates and their fields. */
    TEMPLATE_READ("template:read"),
    /** Create/update/delete templates, fields, composite creation. */
    TEMPLATE_WRITE("template:write"),

    /** Read validation rules / run row validation against own templates. */
    VALIDATION_READ("validation:read"),
    /** Create/update/delete validation rules. */
    VALIDATION_WRITE("validation:write"),

    /** Read import history and details. */
    IMPORT_READ("import:read"),
    /** Create/update/delete import history and details. */
    IMPORT_WRITE("import:write"),

    /** Read ingestion jobs and the supported-types catalogue. */
    INGESTION_READ("ingestion:read"),
    /** Create/drive/edit ingestion jobs (resume, remap, mapping, rows...). */
    INGESTION_WRITE("ingestion:write"),

    /** Export reviewed data as a file or into a database (PRIV-005). */
    EXPORT_RUN("export:run"),

    /** Upload files into the shared upload storage. */
    FILE_UPLOAD("file:upload"),

    /** Call LLM-spending endpoints (prompts, auto-mapping, chat edit, transform). */
    AI_USE("ai:use"),

    /** Read extraction sources, jobs, records and audit trails. */
    EXTRACTION_READ("extraction:read"),
    /** Create/update/delete extraction sources and jobs. */
    EXTRACTION_WRITE("extraction:write"),
    /** Start/pause/resume/cancel extraction jobs and bridge their results. */
    EXTRACTION_RUN("extraction:run"),

    /** Read doc-creation blueprints, instances and assets. */
    DOC_READ("doc:read"),
    /** Create/update/delete blueprints, instances, blocks and assets. */
    DOC_WRITE("doc:write"),
    /** Generate and download the reproduced document. */
    DOC_GENERATE("doc:generate"),

    /** Read the shared lookup tables (ProcessStep, TemplateDataTypes). */
    LOOKUP_READ("lookup:read"),
    /** Mutate the shared lookup tables — admin only, they affect every tenant. */
    LOOKUP_ADMIN("lookup:admin"),

    /** Operational metrics of the extraction machinery — admin only. */
    METRICS_READ("metrics:read"),

    /** Self-service on the caller's own account (me/profile/password/logout). */
    PROFILE_SELF("profile:self"),

    /** Bank-scoped user administration (ws09: /api/admin/users routes). */
    USER_ADMIN("user:admin"),
    /** Approve/reject pending maker/checker changes (ws10: /api/admin/changes decisions). */
    CHANGE_APPROVE("change:approve"),
    /** Release/reject submitted documents and review the queue (ws11, gate B8). */
    DOC_RELEASE("doc:release"),

    /**
     * Read the caller's own bank's audit events as the SIEM feed (ws18,
     * SEC-031, gate E6: GET /api/siem/events). ADMIN only — a bank's SIEM
     * integration authenticates as a dedicated admin account of that bank.
     */
    SIEM_READ("siem:read"),
    /**
     * Browse, search and export the bank's audit trail (/api/admin/audit,
     * optimization round ws28); platform admins see the platform bank's own
     * trail plus cross-bank counts only.
     */
    AUDIT_READ("audit:read"),

    // ---- Phase 2: bank-level administration ---------------------------------
    /** Read the bank's effective settings (ws20: GET /api/admin/settings). */
    SETTINGS_READ("settings:read"),
    /** Change bank settings (operational tier applies, security tier needs approval). */
    SETTINGS_WRITE("settings:write"),
    /** Running version, build and release notes (ws24: /api/system/**). */
    SYSTEM_INFO("system:info"),
    /** Share a template with the whole bank, or edit a bank-shared one (ws22). */
    TEMPLATE_PUBLISH("template:publish"),
    /** Manage bank integrations: connections, flows, API clients, agents (ws25/26). */
    INTEGRATION_ADMIN("integration:admin"),
    /** Trigger integration runs and read their history (ws25). */
    INTEGRATION_RUN("integration:run"),

    // ---- Phase 2: platform owner (granted ONLY to PLATFORM_ADMIN) ------------
    /** Read the platform console: banks, counts-only overview, releases (ws21). */
    PLATFORM_READ("platform:read"),
    /** Onboard, suspend and reactivate banks; approve bank egress rules (ws21/25). */
    PLATFORM_BANKS("platform:banks"),
    /** Platform defaults and the bounds banks may set (ws20). */
    PLATFORM_SETTINGS("platform:settings"),
    /** Post maintenance and release announcements (ws24). */
    PLATFORM_ANNOUNCE("platform:announce"),

    // ---- Phase 2: integration API scopes (granted ONLY to API_CLIENT) --------
    /** Send records or files into the bank's tenant (POST /api/integration/v1/records|files). */
    INTEGRATION_INGEST("integration:ingest"),
    /** Read job status, validated results and rejected rows. */
    INTEGRATION_RESULTS("integration:results"),
    /** Download released documents. */
    INTEGRATION_DOCS("integration:docs"),
    /** Read the integration event feed. */
    INTEGRATION_EVENTS("integration:events"),
    /** The on-prem Connector Agent's protocol (/api/agent/v1/**). */
    AGENT_CONNECT("agent:connect");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    /** The stable {@code resource:action} code used in docs and the matrix CSV. */
    public String code() {
        return code;
    }
}
