package com.modocument.domain.model;

/**
 * Fine-grained permissions ({@code resource:action}) that every API route is
 * mapped onto via {@code @RequiresPermission}. Roles grant sets of these (see
 * {@code RolePermissions}); the enum is only the vocabulary.
 *
 * <p>No constant is reserved any more: {@link #USER_ADMIN} left the
 * reserved list with ws09 (user management), {@link #CHANGE_APPROVE} with
 * ws10 (maker/checker approval workflow) and {@link #DOC_RELEASE} with
 * ws11 (document release — it now guards the release/reject/queue/review
 * routes on /api/doccreation/instances).
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
    DOC_RELEASE("doc:release");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    /** The stable {@code resource:action} code used in docs and the matrix CSV. */
    public String code() {
        return code;
    }
}
