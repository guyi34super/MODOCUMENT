# MO-009 Part 3 — API and interface reference

Part of the [MO-009 Developer & Technical Documentation set](../mo-009-developer-technical-documentation.md). The machine-readable contract is generated from the code; this part tells a developer where it is, how it is versioned, how callers authenticate, what errors look like and which route family needs which permission.

## 1. OpenAPI contract

| Item | Where | Proof |
|---|---|---|
| Spec, generated from the code by springdoc | `GET /v3/api-docs` (JSON), `GET /v3/api-docs.yaml` | `docs/api/versioning-policy.md` §1; `src/main/resources/application.yml` (`springdoc`) |
| Route inventory (METHOD path), 245 operations, pinned in CI | `docs/api/openapi-routes.txt` | file header |
| CI check | `OpenApiContractTest`: spec well-formed, every handler route in the spec and nothing else, inventory equal to the file, `bearerAuth` global, 429 documented on rate-limited routes, explicit `@Tag` and stable `operationId` per operation | `src/test/java/com/modocument/api/contract/OpenApiContractTest.java`; **verified (C5)**: 7 tests pass |
| Regenerate after a deliberate change | `mvn -Dtest=OpenApiContractTest -Dmodocument.contract.update=true test`, then review `git diff docs/api/openapi-routes.txt` | `docs/api/versioning-policy.md` §1 |
| Groups | No springdoc groups are configured: one spec, organised by controller `@Tag` | `src/main/java/com/modocument/api/config/OpenApiConfig.java` |
| Swagger UI | `/swagger-ui/index.html`, `local` profile only | `src/main/resources/application-local.yml`; **verified (C7)**: HTTP 200 on `local` |
| Exposure outside `local` | Needs a valid bearer token; `MODOCUMENT_API_DOCS_ENABLED=false` removes it | `docs/api/versioning-policy.md` §5 |
| Hand-written `static/swagger/spec.yaml` | Deprecated, not maintained | `docs/api/versioning-policy.md` §1 |

## 2. Versioning and deprecation

- **v1** is the current API: the unprefixed `/api/**` plus the legacy CRUD paths (`/Templates/**`, `/TemplateFields/**`, `/TemplateDataTypes/**`, `/ProcessStep/**`, `/ImportHistory/**`, `/ImportDetails/**`, `/Validation*/**`). A breaking change ships under `/api/v2/...`, served alongside v1 (`docs/api/versioning-policy.md` §2).
- Additive vs. breaking is defined in `docs/api/versioning-policy.md` §3. Deprecation lasts at least six months and is announced with `Deprecation` / `Sunset` headers (`docs/api/versioning-policy.md` §4; `src/main/java/com/modocument/api/config/DeprecationHeaderInterceptor.java`).
- Routes deprecated or removed in 1.2.0, and the Word path removed from document creation, are listed in `docs/api/versioning-policy.md` §7–§8.

## 3. Authentication

| Caller | Method | Routes | Proof |
|---|---|---|---|
| Bank and platform users (SPA) | Password login → short-lived JWT access token (`Authorization: Bearer`, default 15 min) + rotating 7-day refresh token in the httpOnly `modoc_refresh` cookie; TOTP MFA step (`/api/auth/mfa/verify`), mandatory for ADMIN in production | `/api/auth/*` and every user route | `README.md` "Authentication"; `src/main/java/com/modocument/api/controller/AuthController.java`, `MfaController.java` |
| Bank users via SSO | OIDC (Entra ID, Keycloak) authenticates; the app then issues its own tokens; password login stays as break-glass | `/oauth2/**`, `/login/oauth2/**`, `/api/auth/providers` | `docs/security/identity-providers.md`; `application-oidc.yml.example` |
| Bank systems (Integration API) | OAuth2 client credentials (`POST /api/integration/oauth/token`), API key (`Authorization: ApiKey mdk_…`) or mutual TLS (RFC 8705 certificate-bound tokens) | `/api/integration/v1/**` | `docs/integration/developer-guide.md` §2 |
| Connector Agent | One-time enrolment token → API client of kind AGENT with scope `AGENT_CONNECT` → client-credentials token (or mTLS) | `/api/agent/v1/**` | `docs/integration/agent.md` "Enrolment" |
| Uptime monitors, load balancers | None | `GET /api/probe/synthetic` (rate-limited per IP), `/actuator/health` | `src/main/java/com/modocument/api/config/SecurityConfig.java` |

Public routes are exactly: `/api/auth/login`, `/api/auth/refresh`, `/api/auth/mfa/verify`, `/api/auth/providers`, `/oauth2/**`, `/login/oauth2/**`, `/actuator/health`, `/error`, `GET /api/probe/synthetic`, `POST /api/integration/oauth/token`, `POST /api/agent/v1/enroll`, and — only with `permit-dev-paths` (the `local` profile) — Swagger, `/v3/api-docs` and the H2 console. Machine routes admit machine callers only; every other route admits users only (`src/main/java/com/modocument/api/config/SecurityConfig.java`). **Verified (C7)**: an anonymous `GET /Templates/GetAll` answers 401.

## 4. Authorisation: roles and permissions

Each route carries `@RequiresPermission` (`src/main/java/com/modocument/api/security/RequiresPermission.java`). Roles map to permissions in `src/main/java/com/modocument/application/security/RolePermissions.java`:

| Role | Permissions (summary) |
|---|---|
| `READ_ONLY` | `template:read`, `validation:read`, `import:read`, `ingestion:read`, `extraction:read`, `doc:read`, `lookup:read`, `profile:self`, `system:info` |
| `READ_WRITE` | READ_ONLY + the matching `:write` permissions, `file:upload`, `ai:use`, `export:run`, `extraction:run`, `doc:generate`, `integration:run` |
| `APPROVER` | READ_ONLY + `change:approve`, `doc:release`, `audit:read` |
| `ADMIN` | Every permission except the `platform:*` ones, `lookup:admin` and the machine scopes |
| `PLATFORM_ADMIN` | `platform:read`, `platform:banks`, `platform:settings`, `platform:announce`, `lookup:admin`, `profile:self`, `system:info`, `user:admin`, `change:approve`, `siem:read`, `lookup:read`, `audit:read` |
| `API_CLIENT` | Machine scopes only: `integration:ingest`, `integration:results`, `integration:docs`, `integration:events`, `agent:connect` |

`RoutePermissionCoverageTest` checks that every route is covered (`src/test/java/com/modocument/api/security/RoutePermissionCoverageTest.java`). The full matrix: `docs/security/role-permission-matrix.csv`; the role catalogue and segregation of duties: `docs/security/role-catalogue-and-sod.md`.

## 5. Route families

Counts are from `docs/api/openapi-routes.txt` (grouped by path prefix, C12); permissions are the `Permission` values used by each controller (`src/main/java/com/modocument/api/controller/`, `src/main/java/com/modocument/doccreation/api/`).

| Family | Ops | Controller(s) | Permissions | Purpose |
|---|---|---|---|---|
| `/Templates`, `/TemplateFields` | 7 + 5 | `TemplateController`, `TemplateFieldsController`, `TemplateCompositeController` | `template:read`, `template:write`, `template:publish` (visibility) | Flow templates and their fields, extraction mode and hints |
| `/ValidationRules`, `/Validation` | 4 + 1 | `ValidationRuleController`, `ValidationController` | `validation:read`, `validation:write` | Field rules; run rows through the rule engine |
| `/TemplateDataTypes`, `/ProcessStep` | 5 + 5 | `TemplateDataTypesController`, `ProcessStepController` | `lookup:read`, `lookup:admin` | Lookup tables (legacy CRUD) |
| `/ImportHistory`, `/ImportDetails` | 5 + 5 | `ImportHistoryController`, `ImportDetailsController` | `import:read`, `import:write` | Legacy import records |
| `/api/ingestion` | 16 | `IngestionJobController`, `IngestionExportController` | `ingestion:read`, `ingestion:write`, `ai:use` (chat edit), `export:run` | Upload → parse → map → validate → export jobs |
| `/api/fileupload`, `/api/automapper` | 1 + 1 | `FileUploadController`, `AutoMapperController` | `file:upload`, `ai:use` | Deprecated in 1.2.0 |
| `/api/extraction` | 18 | `ExtractionSourceController`, `ExtractionJobController`, `ExtractionMonitoringController` | `extraction:read`, `extraction:write`, `extraction:run`, `ai:use`, `metrics:read` | External-source extraction and bridge to ingestion |
| `/api/doccreation` | 59 | `DocBlueprintController`, `DataTemplateController`, `BlueprintDataMappingController`, `DocBlueprintSourceController`, `DocFieldRuleController`, `DocInstanceController`, `DocFillController`, `DocReleaseController`, `TemplateRecordController` | `doc:read`, `doc:write`, `doc:generate`, `doc:release`, `ingestion:read` (records) | PDF templates, data templates, mapping, documents, release review |
| `/api/dashboard` | 1 | `DashboardController` | `ingestion:read` | Home dashboard summary |
| `/api/auth` | 12 | `AuthController`, `MfaController`, `AuthProvidersController` | public entry points; `profile:self` | Login, refresh, logout, profile, password, MFA |
| `/api/announcements` | 1 | `AnnouncementController` | `profile:self` | Live announcements banner |
| `/api/system` | 2 | `SystemInfoController` | `system:info` | Version and release notes |
| `/api/admin/users`, `/api/admin/changes` | 10 + 5 | `UserAdminController`, `PendingChangeController` | `user:admin`; `change:approve` | Users and maker/checker approvals |
| `/api/admin/settings` | 2 | `AdminSettingsController` | `settings:read`, `settings:write` | Bank settings |
| `/api/admin/audit`, `/api/platform/audit` | 3 + 2 | `AuditLogController` | `audit:read` | Audit viewer and CSV export |
| `/api/admin/alerts`, `/api/admin/sod`, `/api/admin/retention`, `/api/admin/dsr` | 1 + 1 + 2 + 3 | `AlertAdminController`, `SodAdminController`, `RetentionAdminController`, `DataSubjectController` | `user:admin` | Security alerts, SoD scan, retention, data-subject requests |
| `/api/admin/dashboard` | 1 | `AdminDashboardController` | `metrics:read` | Admin KPIs |
| `/api/admin/doccreation` | 1 | `DocLegacyPurgeController` | `user:admin` | Purge legacy Word templates |
| `/api/admin/integrations` | 32 | `IntegrationConnectionController`, `IntegrationFlowController`, `IntegrationRunController`, `ApiClientAdminController`, `IntegrationAgentAdminController`, `EgressRuleController` | `integration:admin`, `integration:run` | Connections, flows, runs, API clients, agents, egress rules |
| `/api/platform/banks`, `/settings`, `/announcements`, `/egress-rules`, `/overview` | 8 + 2 + 4 + 2 + 1 | `PlatformBankController`, `PlatformSettingsController`, `PlatformAnnouncementController`, `EgressRuleController` | `platform:read`, `platform:banks`, `platform:settings`, `platform:announce` | Platform owner console |
| `/api/siem` | 1 | `SiemFeedController` | `siem:read` | NDJSON audit feed |
| `/api/probe` | 1 | `SyntheticProbeController` | public | Uptime probe |
| `/api/integration/oauth`, `/api/integration/v1` | 1 + 8 | `IntegrationApiController` | public token endpoint; `integration:ingest`, `integration:results`, `integration:docs`, `integration:events` | Integration API v1 |
| `/api/agent` | 6 | `AgentProtocolController` | public enrolment; `agent:connect` | Agent protocol |

## 6. Integration API v1 (bank systems)

Base URL `https://<host>/api/integration/v1` (`docs/integration/developer-guide.md`):

| Endpoint | Scope | Purpose |
|---|---|---|
| `GET /templates` | `INTEGRATION_INGEST` | Bank-shared templates the client may send data to |
| `POST /records` | `INTEGRATION_INGEST` | JSON records against a template |
| `POST /files` | `INTEGRATION_INGEST` | A file against a template |
| `GET /jobs/{id}`, `/jobs/{id}/results?format=json\|csv`, `/jobs/{id}/errors` | `INTEGRATION_RESULTS` | Job status, validated rows, rejected rows |
| `GET /documents/{id}` | `INTEGRATION_DOCS` | A released document |
| `GET /events?after=<cursor>&limit=<n>` | `INTEGRATION_EVENTS` | Event feed |

- Every `POST` needs an `Idempotency-Key` (replays return the stored answer with `Idempotent-Replayed: true`; keys kept 24 h per client) (`docs/integration/developer-guide.md` §3).
- Webhooks from MoDocument are signed; verification is in `docs/integration/developer-guide.md` §6.
- Postman collection: `docs/integration/modocument-integration-v1.postman_collection.json` (12 requests: token, templates, records, file, job status/results/errors, document, events).
- API clients are created under Administration → Integrations → API clients, approved by a second admin; the secret is shown once to the maker (`docs/integration/developer-guide.md` §1).

## 7. Agent protocol

`/api/agent/v1`: `POST /enroll` (public, one-time token, 10 per 15 min per IP), `GET /tasks?wait=25` (long-poll, server cap 30 s), `POST /runs/{id}/records` (pages ≤ 1000), `GET /runs/{id}/payload`, `POST /runs/{id}/result`, `POST /heartbeat` every 30 s (`docs/integration/agent.md`; `docs/phase2/contracts.md` §7.4). Install and configuration: `agent/README.md`. Shared connector code: `docs/integration/connector-sharing.md`.

## 8. Error format

| API | Body | Proof |
|---|---|---|
| User API | `{"error": "<message>", "requestId": "<id>"}`; bean-validation failures `{"error": "Validation failed.", "fields": {...}}`; a pending password change `{"error": …, "code": "PASSWORD_CHANGE_REQUIRED", "requestId": …}` | `src/main/java/com/modocument/api/controller/ApiExceptionHandler.java` |
| Integration API | `{"error", "code", "message", "requestId"}` with codes such as `INVALID_REQUEST`, `INSUFFICIENT_SCOPE`, `NOT_FOUND` (also for another bank's id), `IDEMPOTENCY_KEY_REUSED`, `RATE_LIMITED`, `INTERNAL_ERROR` | `docs/integration/developer-guide.md` §5; `src/main/java/com/modocument/api/controller/IntegrationApiExceptionHandler.java` |

Every response carries `X-Request-Id`; a safe inbound value is honoured, otherwise one is generated (`src/main/java/com/modocument/infrastructure/security/RequestIdFilter.java`). Quote it when reporting a problem; it is also stored on the audit row (`docs/security/audit-events.md` "Row anatomy").

## 9. Rate limits

| Limit | Default | Proof |
|---|---|---|
| Login | 30 per IP, 10 per e-mail per 15 min → 429 | `README.md` "Configuration" (`modocument.security.rate-limit.auth.*`) |
| LLM-backed endpoints | 60 per user per hour | `README.md` "Configuration" (`modocument.security.rate-limit.llm.*`) |
| Expensive endpoints, per user per hour | uploads 20, ingestion 60, transform 30, exports 60, doc-generate 30, extraction 30, dsr 10, password-change 5, mfa-code 10, admin-jobs 10, admin-security 10 (`MODOCUMENT_RATELIMIT_*`) | `src/main/resources/application.yml` `modocument.ratelimit`; `src/main/java/com/modocument/infrastructure/ratelimit/ApiRateLimitInterceptor.java`; bucket → route table in `docs/security/owasp-api-top10.md` |
| Integration API | 600 calls per minute per client | `docs/integration/developer-guide.md` §5 |
| Synthetic probe | 60 per IP per minute | `src/main/resources/application.yml` `modocument.probe` |

Over-limit calls answer 429 with `Retry-After`. Counters are in memory per instance, so N replicas allow up to N × the limit (`src/main/resources/application.yml` comment). Some limits are also editable settings (`docs/settings/catalogue.md`, "Rate limits" tab).
