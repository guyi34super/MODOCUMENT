# Optimization round (v1.2.0) — shared contracts (Wave 0)

Plan: speed & DB, frontend load, cost & footprint, code health, audit logs.
Fixed by the Supervisor before Wave 1; changing anything here needs the
Supervisor. Same rules as `docs/phase2/contracts.md` (file ownership, RLS on
every BankId table, TenantContext registry, expand/contract migrations,
`openapi-routes.txt` regenerated, no model names in commits).

## 1. Migrations (RANGES.md)

| Range | Owner | Content |
|---|---|---|
| V270–V279 (ws27) | SE1 | performance indexes (V270, may be non-transactional for `CONCURRENTLY` via a `V270__….sql.conf` with `executeInTransaction=false`), housekeeping columns/indexes |
| V280–V284 (ws28) | SE2 | audit (only if the viewer needs schema; indexes are SE1's V270) |
| V285–V289 (ws28) | SE4 | `AiResultCache` table; `IngestionJob.ValidationErrorCount` (nullable int) |
| V290–V299 (ws29) | SE5 | file-store references (only if needed) |

## 2. Permission

`AUDIT_READ` (`audit:read`): ADMIN, APPROVER, PLATFORM_ADMIN (landed in
Wave 0; listed in `RoutePermissionCoverageTest.RESERVED` until SE2's first
route lands — SE2 removes it).

## 3. Audit API (SE2) — used by the UI (SE3)

- `GET /api/admin/audit` (AUDIT_READ, caller's bank)
  - query: `actor` (email or user id), `action` (repeatable), `entityType`,
    `entityId`, `outcome` (`SUCCESS|DENIED|FAILED`), `from`, `to` (ISO-8601),
    `cursor` (opaque), `limit` (1–200, default 50)
  - → `{events:[AuditEventView], nextCursor|null}` newest first, keyset on
    `(OccurredAt, AuditEventId)`
  - `AuditEventView = {id, occurredAt, action, outcome, actorUserId,
    actorEmail, actorRole, entityType, entityId, ip, requestId,
    oldValues, newValues}` — values pass through the existing PII masking for
    the caller's role
- `GET /api/admin/audit/export?…same filters…` → `text/csv` streamed, max
  50 000 rows (ExportGuard caps); audited `AUDIT_LOG_EXPORTED`
- `GET /api/admin/audit/actions` → `{actions:[{name, category}]}` (filter
  picker; the catalogue in `docs/security/audit-events.md`)
- `GET /api/platform/audit` (AUDIT_READ + platform bank): the platform bank's
  own events (same shape) + `GET /api/platform/audit/counts?from&to` →
  `{banks:[{bankId, code, total, denied, failed}]}` counts only
- Viewing a page is audited `AUDIT_LOG_VIEWED` (once per actor per 15 min,
  deduplicated in memory) — no recursion: reading audit never audits reads of
  the viewed rows

## 4. New audit actions (SE2 unless noted)

`TEMPLATE_CREATED`, `TEMPLATE_FIELD_CREATED|UPDATED|DELETED`,
`VALIDATION_RULE_CREATED|UPDATED|DELETED`, `FILE_UPLOADED`,
`INGESTION_JOB_CREATED`, `INGESTION_REMAPPED`, `INGESTION_ROWS_EDITED`,
`DOC_GENERATED`, `DOC_DOWNLOADED`, `EXPORT_DOWNLOADED`, `TOKEN_REFRESHED`,
`ACCESS_DENIED` (use-case `AccessDeniedException`, owner-scoped 404 probes,
invalid/revoked bearer — aggregated: at most one row per actor-or-IP per
action per minute, with a `count`), `SENSITIVE_DATA_VIEWED` (once per actor,
entity, hour), `AUDIT_LOG_VIEWED`, `AUDIT_LOG_EXPORTED`,
`HOUSEKEEPING_COMPLETED` (SE1), `AI_RESULT_CACHE_HIT` is NOT audited (metric
only). New alert rule `access-denied-burst` (WINDOWED, MEDIUM, group by
ACTOR/IP, threshold setting `alerts.accessDeniedBurst.*`).

## 5. Ingestion summary (SE4) — used by the UI (SE3)

- `GET /api/ingestion/jobs?page=0&size=50&view=summary` →
  `{items:[IngestionJobSummary], page, size, total}`;
  `IngestionJobSummary = {ingestionJobId, fileName, templateId, templateName,
  status, currentStep, parsedBy, parseFallbackReason, validationErrorCount,
  unmappedFieldCount, createdDate, updatedDate}`
- Without `view=summary` the endpoint keeps its old shape (compat) but is
  capped at 200 newest jobs; the UI moves to summary.
- `GET /api/dashboard/summary` (existing dashboard permission of the user
  dashboard) → `{templates, ingestion:{total, byStatus:{…}, recent:[summary ×8]},
  extraction:{total, running, recent:[…×5]}, imports:{total, recent:[…×5]}}`

Paged lists (same `{items, page, size, total}` envelope, `size` ≤ 200):
`/api/admin/users`, `/api/admin/changes`, `/api/admin/integrations/runs`
(already limit-based: keep, add `page`).

## 6. Settings keys (SE1)

`integration.historyRetention` (DURATION, BANK, OPERATIONAL, default 90d,
range 7d–400d), `audit.readAuditEnabled` (BOOLEAN, BANK, SECURITY, default
true, platform-controlled), `alerts.accessDeniedBurst.threshold|window` (SE2).

## 7. File store (SE5)

Port `application/port/FileStore` (landed in Wave 0). Keys keep today's
relative layout. `modocument.storage.backend: local|s3` (default `local`),
`modocument.storage.s3.{bucket,prefix,region,kms-key-id,temp-cache-mb}`.
Every current `Path`-based caller (UploadStorage, export copies, doc assets,
RetentionPurger, IngestionJobDeletionUseCase, DataSubjectUseCase,
IntegrationApiUseCase, PayloadBuilder, IngestionExportController,
DocInstanceUseCase) moves to the port; `localCopy` for parsers.

## 8. Deprecation headers (SE2)

Deprecated routes answer normally plus `Deprecation: true`,
`Sunset: <RFC 7231 date, 2027-04-01>`, `Link: </docs/api/versioning-policy.md>;
rel="deprecation"`, and `deprecated: true` in OpenAPI.

## 9. File ownership this round

| File | Owner |
|---|---|
| `application.yml` | SE1 (others add their own keys in their own blocks) |
| `pom.xml` | SE5 (SE4 may add nothing; S3 SDK + profile changes are SE5's) |
| `Dockerfile`, `agent/Dockerfile`, `infra/**` (incl. CloudFront), `.github/workflows/**` | SE5 |
| `frontend/nginx.conf` (gzip, cache headers, `/assets/`) | SE3 this round; keep `scripts/check-nginx-headers.sh` green |
| `frontend/src/**` | SE3 (SE4 touches none) |
| `TenantContext` registry, RANGES, Permission/Role/RolePermissions, matrix | Supervisor |
| `RetentionPurger` | SE1 (SE5 swaps its file deletes to FileStore — coordinate: SE5 edits only the file-deletion helper lines) |
