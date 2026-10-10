# Phase 2 — shared contracts (Wave 0)

Fixed by the Supervisor before any engineer starts, so the five workstreams
can be built in parallel without re-negotiating shapes. Changing anything
here needs the Supervisor. Plan: settings, platform owner, admin dashboard,
code-based extraction fallback, release/updates, bank integration hub.

## 1. Roles and permissions (landed in Wave 0)

`domain/model/Role`: `ADMIN, APPROVER, READ_WRITE, READ_ONLY` (bank roles,
`isBankRole()`), then `PLATFORM_ADMIN` (ws21) and `API_CLIENT` (ws26) — never
a bank user's role. Grants: `application/security/RolePermissions` (pinned by
`docs/security/role-permission-matrix.csv`).

| Permission | Holders | Guards (owner) |
|---|---|---|
| `SETTINGS_READ` / `SETTINGS_WRITE` | ADMIN | `/api/admin/settings` (SE1) |
| `SYSTEM_INFO` | every human role | `/api/system/**` (SE5) |
| `TEMPLATE_PUBLISH` | ADMIN | template visibility (SE3) |
| `INTEGRATION_ADMIN` | ADMIN | `/api/admin/integrations/**` (SE1/SE2/SE4/SE5) |
| `INTEGRATION_RUN` | ADMIN, READ_WRITE | run triggers + run history (SE4) |
| `PLATFORM_READ` / `PLATFORM_BANKS` / `PLATFORM_SETTINGS` / `PLATFORM_ANNOUNCE` | PLATFORM_ADMIN | `/api/platform/**` (SE2, SE1, SE5) |
| `LOOKUP_ADMIN` | **PLATFORM_ADMIN only** (moved off bank ADMIN) | lookup writes |
| `INTEGRATION_INGEST` / `_RESULTS` / `_DOCS` / `_EVENTS` | API_CLIENT | `/api/integration/v1/**` (SE2) |
| `AGENT_CONNECT` | API_CLIENT (kind AGENT) | `/api/agent/v1/**` (SE5) |

Every new permission is listed in `RoutePermissionCoverageTest.RESERVED`
until its routes land — **the workstream that adds the first route for a
permission removes it from RESERVED in the same commit.**

`PermissionChecker` additions (SE2):
- a `PLATFORM_*` permission additionally requires the caller's bank to be
  the platform bank;
- for `API_CLIENT` principals the token's `scope` claim narrows the grant
  (granted = role grant ∩ scopes).

**Platform bank** (landed in Wave 0): `application/port/PlatformBankLookup`
(`platformBankId()`, `isPlatformBank(id)`), implemented by
`infrastructure/platform/PlatformBankDirectory` — resolves the bank whose
code is `modocument.platform.bank-code` (default `MOI-PLATFORM`) as system in
its own read-only transaction (the Bank table is RLS-protected) and caches
the id. Platform settings rows, platform announcements and egress approvals
are owned by this bank. SE2 creates the bank (bootstrap) and may extend the
directory (kind/status) but keeps this interface.

## 2. Maker/checker change types (landed in Wave 0; appliers per workstream)

| ChangeType | Maker permission | Applier owner | Payload (JSON) |
|---|---|---|---|
| `SETTING_UPDATE` | SETTINGS_WRITE | SE1 | `{changes:[{key, from, to, baseVersion}]}` |
| `PLATFORM_SETTING_UPDATE` | PLATFORM_SETTINGS | SE1 | `{changes:[{key, from, to, bankMin?, bankMax?, baseVersion}]}` |
| `USER_PASSWORD_RESET` | USER_ADMIN | SE3 | `{userId, passwordHash}` (hash taken at submit, never plaintext) |
| `USER_MFA_RESET` | USER_ADMIN | SE3 | `{userId, reason}` |
| `BANK_CREATE` | PLATFORM_BANKS | SE2 | `{code, name, users:[{email, displayName, role, passwordHash}] (≥2, ≥1 ADMIN)}` |
| `BANK_SUSPEND` | PLATFORM_BANKS | SE2 | `{bankId, reason}` |
| `BANK_ADMIN_ADD` | PLATFORM_BANKS | SE2 | `{bankId, email, displayName, passwordHash, reason}` |
| `INTEGRATION_CONNECTION_CHANGE` | INTEGRATION_ADMIN | SE4 | `{connectionId?, name, type, mode, config, secretCiphertext?, secretRef?}` |
| `INTEGRATION_FLOW_ENABLE` | INTEGRATION_ADMIN | SE4 | `{flowId, flowVersion}` |
| `API_CLIENT_CREATE` | INTEGRATION_ADMIN | SE2 | `{name, authMethod, scopes, allowedCidrs, expiresAt, certFingerprint?}` (secret generated at APPLY, shown once to the maker via the change result) |
| `AGENT_REGISTER` | INTEGRATION_ADMIN | SE5 | `{name}` |
| `EGRESS_RULE_APPROVE` | PLATFORM_BANKS | SE1 | `{ruleId}` |

Each applier: `@Component implements PendingChangeApplier` (model:
`UserRoleChangeApplier`), add `TYPE_LABELS` + `summarize()` entries in
`frontend/src/pages/PendingChangesPage.jsx` (SE3 owns the file — send SE3
the label/summary line) and a row in `docs/security/maker-checker.md`.
Disabling/reverting anything is always direct (the lower-risk direction).

## 3. Routes (by owner)

All JSON; errors use the existing `ApiExceptionHandler` shape
`{error, code, message}`. 202 responses for maker/checker return the
`PendingChange` DTO (`isPendingChange()` in `frontend/src/api/changes.js`).

**SE1 — settings / secrets / egress**
- `GET  /api/admin/settings` (SETTINGS_READ) → `{layer:"BANK", revision, groups:[{id,label,settings:[SettingView]}]}`
- `PUT  /api/admin/settings` (SETTINGS_WRITE) body `{changes:[{key, value|null, expectedVersion}], comment}` → 200 `{applied:[SettingView], pendingChange:null}` or 202 `{applied:[…], pendingChange:{…}}`
- `GET|PUT /api/platform/settings` (PLATFORM_READ / PLATFORM_SETTINGS) same shapes, `SettingView` adds `bankMin, bankMax`
- `GET|POST /api/admin/integrations/egress-rules` (INTEGRATION_ADMIN) request a destination; `GET /api/platform/egress-rules`, `POST /api/platform/egress-rules/{id}/approve` → 202 (PLATFORM_BANKS)
- `SettingView = {key, type, tier, scope, group, label, description, unit, value, source:"BANK|PLATFORM|DEFAULT", default, min, max, hardMin, hardMax, allowed, requiresApproval, requiresRestart, locked, clamped, pendingChangeId, version}`
- Error codes: `SETTING_OUT_OF_RANGE`(400), `SETTING_LOCKED`(400), `SETTING_UNKNOWN`(400), `SETTING_VERSION_CONFLICT`(409), `SETTING_CHANGE_PENDING`(409), `USE_PLATFORM_SETTINGS`(409)

**SE2 — platform, banks, machine identity, integration API**
- `GET /api/platform/overview` (PLATFORM_READ) → `{banks:[BankSummary], totals}`; `BankSummary = {bankId, code, name, kind, status, users, mfaEnrolledPct, jobs7d, failures7d, aiShare7d, codeShare7d, lastActivityAt}` — counts only
- `GET /api/platform/banks`, `GET /api/platform/banks/{id}` (PLATFORM_READ)
- `POST /api/platform/banks` (PLATFORM_BANKS) → 202 BANK_CREATE; `POST /api/platform/banks/{id}/suspend` → 202; `POST …/reactivate` → 200; `POST …/admins` → 202
- `GET|POST /api/admin/integrations/api-clients`, `POST …/{id}/rotate` (secret shown once), `POST …/{id}/revoke` (INTEGRATION_ADMIN)
- `POST /api/integration/oauth/token` (public route; client authentication inside) → `{access_token, token_type:"Bearer", expires_in, scope}`
- `/api/integration/v1/**` (client scopes):
  - `GET /templates` (INTEGRATION_INGEST) → bank-shared templates + fields
  - `POST /records` (INTEGRATION_INGEST) `{templateId, externalBatchId, records:[{…}]}` + `Idempotency-Key` → 202 `{jobId}`
  - `POST /files` (INTEGRATION_INGEST, multipart `file`, `templateId`) → 202 `{jobId}`
  - `GET /jobs/{id}` (INTEGRATION_RESULTS) → `{jobId, status, currentStep, parsedBy, counts:{rows, valid, rejected}, errorMessage}`
  - `GET /jobs/{id}/results?format=json|csv` and `GET /jobs/{id}/errors` (INTEGRATION_RESULTS)
  - `GET /documents/{id}` (INTEGRATION_DOCS) — released only
  - `GET /events?after=&limit=` (INTEGRATION_EVENTS) → `{events:[{id, type, occurredAt, entity, data}], next}`
- Wrong-bank ids → **404**, never 403.

**SE3 — dashboard, user actions, templates, UI**
- `GET /api/admin/dashboard` (METRICS_READ) → see plan §3 (users, approvals, ingestion incl. `parsedBy:{AI,CODE,AI_PARTIAL}`, extraction, alerts, retention, templates, settings, version, integrations:{runs24h:{ok,failed}, deadLetters, agentsOffline, apiCalls24h})
- `POST /api/admin/users/{id}/unlock` (USER_ADMIN) → 200
- `POST /api/admin/users/{id}/password-reset` `{temporaryPassword}` → 202
- `POST /api/admin/users/{id}/mfa-reset` `{reason}` → 202
- `PATCH /api/admin/users/{id}` `{displayName}` → 200
- `PUT /Templates/{id}/Visibility` `{visibility:"PRIVATE"|"BANK"}` (TEMPLATE_PUBLISH)

**SE4 — extraction fallback, connectors, flows**
- Template DTOs gain `extractionMode` (`null|AI_WITH_CODE_FALLBACK|CODE_ONLY|AI_ONLY`); field DTOs gain `aliases:[string], labelPattern, valuePattern, dateFormats:[string]`
- Ingestion job DTO gains `parsedBy, parseStrategy, parseConfidence, parseFallbackReason`
- `GET|POST /api/admin/integrations/connections`, `GET|PUT|DELETE …/{id}`, `POST …/{id}/test` (INTEGRATION_ADMIN) — secrets write-only
- `GET|POST /api/admin/integrations/flows`, `GET|PUT|DELETE …/{id}`, `POST …/{id}/enable` (202 for OUTBOUND), `POST …/{id}/disable`, `POST …/{id}/preview` (dry run, 20 rows) (INTEGRATION_ADMIN)
- `POST /api/admin/integrations/flows/{id}/run`, `GET /api/admin/integrations/runs`, `GET …/runs/{id}`, `POST …/runs/{id}/retry` (INTEGRATION_RUN)

**SE5 — system, announcements, agent**
- `GET /api/system/version` (SYSTEM_INFO) → `{version, commit, buildTime, schemaVersion, deployedAt, environment}`
- `GET /api/system/release-notes?limit=5` (SYSTEM_INFO) → `{releases:[{version, date, sections:{Added:[…], Changed:[…], Fixed:[…], Security:[…]}}]}`
- `GET /api/announcements/active` (PROFILE_SELF) → `{announcements:[{id, kind, severity, title, body, startsAt, endsAt, maintenanceStartsAt, maintenanceEndsAt, releaseVersion, dismissible}]}`
- `GET|POST /api/platform/announcements`, `PUT|DELETE …/{id}` (PLATFORM_READ / PLATFORM_ANNOUNCE)
- `GET|POST /api/admin/integrations/agents` (POST → 202 AGENT_REGISTER; applying returns the one-time enrolment token)
- `/api/agent/v1/**` (AGENT_CONNECT, except `POST /enroll` which is public with the enrolment token): `GET /tasks?wait=30`, `POST /runs/{id}/records`, `GET /runs/{id}/payload`, `POST /runs/{id}/result`, `POST /heartbeat`

## 4. Shared string vocabularies

| Name | Values |
|---|---|
| `ExtractionMode` | `AI_WITH_CODE_FALLBACK` (default), `CODE_ONLY`, `AI_ONLY` |
| `ParsedBy` | `AI`, `CODE`, `AI_PARTIAL` |
| `FallbackReason` | `AI_DISABLED`, `NOT_CONFIGURED`, `CIRCUIT_OPEN`, `QUOTA`, `ERROR`, `TIMEOUT`, `OUTPUT_REJECTED`, `INVALID_OUTPUT`, `PROMPT_REFUSED`, `MODE_CODE_ONLY` |
| `TemplateVisibility` | `PRIVATE`, `BANK` |
| `BankKind` / `BankStatus` | `CUSTOMER`, `PLATFORM` / `ACTIVE`, `SUSPENDED` |
| `SettingTier` / `SettingScope` | `OPERATIONAL`, `SECURITY` / `PLATFORM`, `BANK` |
| `ConnectionType` / `ConnectionMode` | `REST_API`, `DATABASE`, `SFTP`, `WEBHOOK` / `DIRECT`, `AGENT` |
| `DbVendor` | `POSTGRESQL`, `ORACLE`, `SQLSERVER`, `MARIADB` |
| `FlowDirection` / `FlowTrigger` | `INBOUND`, `OUTBOUND` / `MANUAL`, `SCHEDULE`, `EVENT` |
| `FlowEvent` | `INGESTION_VALIDATED`, `INGESTION_FAILED`, `DOC_RELEASED`, `EXTRACTION_COMPLETED` |
| `PayloadKind` | `VALIDATED_RECORDS`, `REJECTED_ROWS`, `JOB_STATUS`, `RELEASED_DOCUMENTS`, `EVENTS` |
| `ApiAuthMethod` | `OAUTH2_CLIENT_CREDENTIALS`, `API_KEY`, `MTLS` |
| `AnnouncementKind` / severity | `INFO`, `MAINTENANCE`, `RELEASE` / `INFO`, `WARNING`, `CRITICAL` |

## 5. Audit actions (new) and alert rules

Audit (via `AuditRecorder`): `SETTING_UPDATED`, `SECURITY_SETTING_UPDATED`,
`PLATFORM_SETTING_UPDATED`, `BANK_ONBOARDED`, `BANK_SUSPENDED`,
`BANK_REACTIVATED`, `USER_UNLOCKED`, `USER_PASSWORD_RESET`, `USER_MFA_RESET`,
`TEMPLATE_VISIBILITY_CHANGED`, `INGESTION_PARSE_FALLBACK`, `AI_CIRCUIT_OPENED`,
`VERSION_DEPLOYED`, `ANNOUNCEMENT_PUBLISHED`, `INTEGRATION_CONNECTION_CHANGED`,
`INTEGRATION_FLOW_ENABLED`, `INTEGRATION_FLOW_DISABLED`, `INTEGRATION_RUN_COMPLETED`,
`INTEGRATION_RUN_FAILED`, `INTEGRATION_DELIVERY_DEAD`, `API_CLIENT_CREATED`,
`API_CLIENT_ROTATED`, `API_CLIENT_REVOKED`, `API_CLIENT_AUTH_FAILED`,
`AGENT_REGISTERED`, `AGENT_ENROLLED`, `EGRESS_RULE_APPROVED`.

New yml alert rules (owner adds them to `modocument.alerts.rules`):
`security-setting-changed` (MEDIUM), `platform-change` (HIGH),
`parse-fallback-surge` (20/h, MEDIUM), `ai-circuit-opened` (HIGH),
`integration-failures` (5/h, MEDIUM), `integration-dead-letter` (HIGH),
`api-client-auth-failures` (10/5m, HIGH).

## 6. File ownership (merge-conflict control)

| File | Owner |
|---|---|
| `Permission`, `Role`, `ChangeType`, `RolePermissions`, matrix CSV, `RANGES.md`, `afterMigrate*`, `TenantContext` call-site registry, `RoutePermissionCoverageTest.RESERVED` (each owner removes only its own lines) | Supervisor |
| `application.yml` settings + alert blocks | SE1 (others send their keys) |
| `frontend/src/App.jsx` routes | each owner adds its own lines; Supervisor resolves |
| `frontend/src/components/Layout.jsx`, `PendingChangesPage.jsx`, `components/ui.jsx`, new shared components | SE3 |
| `infrastructure/transform/IngestionParseStepImpl.java` | SE4 |
| `docs/api/openapi-routes.txt` | regenerated by the Supervisor at each merge (`-Dmodocument.contract.update=true`) |
| `.github/workflows/*`, `Dockerfile`, `.dockerignore`, `infra/terraform/**` | SE5 |
| `pom.xml` | SE5 (versioning/plugins); SE4 adds connector drivers; Supervisor resolves |

Merge order inside a wave: SE2 → SE1 → SE3 → SE4 → SE5, QA last.

## 7. Wave 3 — Bank Integration Hub contracts

### 7.1 Java contracts (landed before Wave 3)

Vocabularies: `domain/model/integration/` — `ConnectionType`, `ConnectionMode`,
`DbVendor`, `FlowDirection`, `FlowTrigger`, `FlowEvent`, `PayloadKind`,
`PayloadFormat`, `DbWriteMode`, `RunStatus`, `DeliveryStatus`,
`ApiAuthMethod`, `ApiClientKind`, `OutboundAuthType`. All stored by NAME.

Ports: `application/port/integration/`.

| Port | Implements | Calls it |
|---|---|---|
| `IntegrationConnector` (SPI: `validate`, `test`, `read`, `write`) + `ConnectionContext`, `SecretMaterial`, `ReadBatch`, `OutboundBatch`, `WriteResult`, `ConnectionTestResult` | SE4 (one bean per `ConnectionType`) | SE4 flow engine; SE5 agent (same classes, local secrets) |
| `SecretVault` (`seal`/`open`, `Sealed(ciphertext, keyVersion)`) | SE1 | SE4 (connections), SE2 if needed |
| `EgressGuard.checkDestination(bankId, host, port)` | SE1 | SE4 connectors, right before connecting |
| `RecordsIngestion.createFromRecords(Command)` | SE4 | SE2 `POST /api/integration/v1/records`, SE4 inbound flows |
| `IntegrationEventLog` (`publish` in-transaction, `page`) | SE4 | producers (SE4 hooks ingestion; doc release), SE2 `GET /api/integration/v1/events` |
| `CurrentClientProvider` + `ClientPrincipal` | SE2 | SE2 integration API, SE5 agent endpoints |
| `ApiClientIssuer.issueAgentClient(...)` | SE2 | SE5 `POST /api/agent/v1/enroll` |
| `AgentRunPort` (`claimTasks`, `acceptRecords`, `payload`, `complete`) | SE4 | SE5 `/api/agent/v1/**` |

**Placeholders (all replaced at the Wave-3 merge; the package is gone).**
`infrastructure/integration/placeholder/` held one bean per port so each
branch booted alone. **The owner deletes its placeholder in the
same commit that adds the real `@Component`.** Nobody else edits those files.
Consumers unit-test against mocks of the port, never the placeholder.

Jobs created by machines stay owner-scoped: the owner is the user who
created the API client (`ClientPrincipal.createdByUserId`) or the flow
(`IntegrationFlow.CreatedBy`). Machine-created jobs use bank-shared
(`BANK`) templates only.

### 7.2 Database sub-assignments

| Migration | Owner | Content |
|---|---|---|
| `V250__ws25_integration_core.sql` | SE4 | `IntegrationConnection`, `IntegrationFlow`, `IntegrationRun` (unique `(FlowId, ScheduledFor)`), `IntegrationRunEvent`, `OutboundDelivery`. `AgentId` is a plain nullable int (no FK yet) |
| `V251__ws25_egress_rules.sql` | SE1 | `BankEgressRule(BankEgressRuleId, BankId, Host, Cidr, Port, Status PENDING/APPROVED/REVOKED, RequestedBy, ApprovedBy, …)` |
| `V252__ws25_integration_events.sql` | SE4 | `IntegrationEvent(Id bigserial, BankId, Type, EntityType, EntityId, DataJson, OccurredAt)`, `IngestionJob.Origin` (nullable) |
| `V260__ws26_machine_identity.sql` | SE2 | `ApiClient`, `ApiClientSecret`, `IdempotencyKey` |
| `V261__ws26_integration_agents.sql` | SE5 | `IntegrationAgent`, then `fk_integrationconnection_agent` added `NOT VALID` (validated in a later release) |

Every new table has `BankId`, so RLS applies automatically. Nothing joins the
non-tenant inventory. Each table also gets its JPA entity, so the H2 schema
matches (`@Check`/`@ColumnDefault` parity like `Template`).

### 7.3 REST shapes the UI (SE3) builds against

Secrets are **write-only**. A view carries `secretSet: true|false`, never a
value. Ids are numbers; times are ISO-8601 UTC.

- `ConnectionView = {id, name, type, mode, agentId, config:{…non-secret…}, authType, secretSet, secretRef, status: "UNTESTED|OK|FAILED|DISABLED", lastTestAt, lastTestResult, version, pendingChangeId, createdBy, updatedAt}`
- `ConnectionRequest = {name, type, mode, agentId?, config, authType, secret?: {…keys of SecretMaterial…}, secretRef?, expectedVersion?}`. POST/PUT return **202** `PendingChange` (`INTEGRATION_CONNECTION_CHANGE`). Rename-only edits return 200.
- `POST …/connections/{id}/test` → `ConnectionTestResult {ok, message, latencyMillis}`. An AGENT connection returns 202 `{runId}` (the test runs through the agent).
- `FlowView = {id, name, direction, connectionId, connectionName, templateId, templateName, trigger, cron, events:[FlowEvent], payloadKinds:[PayloadKind], format, sourceSpec, targetSpec, fieldMapping:[{from, to}], maskPii, rowCap, enabled, pendingChangeId, version, lastRun:{id, status, finishedAt}}`
- `sourceSpec` by connection type:
  - DATABASE: `{table, columns:[…], watermarkColumn?}`
  - REST_API: `{path, query:{}, pagination:{type:"NONE|PAGE|CURSOR|LINK", …}, recordsPath}`
  - SFTP: `{folder, glob, afterRead:"MOVE|DELETE"}`
- `targetSpec` by connection type:
  - DATABASE: `{table, mode:"INSERT|UPSERT", keyColumns:[…]}`
  - REST_API: `{method:"POST|PUT", path, batchSize}`
  - SFTP: `{folder, fileNamePattern}`
  - WEBHOOK: `{path}`
- `POST …/flows/{id}/preview` → `{columns:[…], rows:[{…}] (≤20), suggestedMapping:[{from, to, score}]}`. Nothing is written.
- `RunView = {id, flowId, flowName, direction, status: RunStatus, trigger, scheduledFor, startedAt, finishedAt, attempt, recordsIn, recordsOut, ingestionJobId, error}`. Run detail adds `events:[{at, level, message}]` and `deliveries:[{id, status: DeliveryStatus, attempts, lastError, nextAttemptAt}]`. `POST …/runs/{id}/retry` replays dead deliveries or re-queues a failed run.
- `ApiClientView = {id, name, kind, authMethod, clientId, keyPrefix, certFingerprint, certSubject, scopes:[…], allowedCidrs:[…], expiresAt, status: "ACTIVE|REVOKED|EXPIRED", lastUsedAt, createdBy, pendingChangeId}`
- `ApiClientRequest = {name, authMethod, scopes, allowedCidrs, expiresAt, certificatePem?}` → 202 (`API_CLIENT_CREATE`).
  - After approval, the **maker** gets the one-time `{clientId, secret}` from `GET …/api-clients/{id}/initial-secret`. It is readable once, within 24 h, then deleted.
  - `POST …/{id}/rotate` → 200 `{clientId, secret}` (shown once; the old secret stays valid for the overlap).
  - `POST …/{id}/revoke` → 200.
- `AgentView = {id, name, status: "PENDING_ENROLMENT|ONLINE|OFFLINE|REVOKED", version, latestVersion, updateAvailable, lastHeartbeatAt, apiClientId, connections:[…ids]}`
  - `POST …/agents {name}` → 202 (`AGENT_REGISTER`).
  - After approval, the maker gets `GET …/agents/{id}/enrolment` → `{token, expiresAt, dockerCommand}` (readable once, 15 min).
- `EgressRuleView = {id, host, cidr, port, status, requestedBy, approvedBy, createdAt}`

### 7.4 Agent protocol (`/api/agent/v1`, SE5)

- `POST /enroll {token, name, version, authMethod, certificatePem?}` (public; the token is the credential) → `{agentId, clientId, secret?, tokenUrl}`
- `GET /tasks?wait=30` → `{tasks:[AgentTask]}` (`AgentRunPort.AgentTask` as JSON)
- `POST /runs/{id}/records {records:[…], nextCursor, last}` → 204
- `GET /runs/{id}/payload` → `OutboundBatch` as JSON (`fileContent` base64)
- `POST /runs/{id}/result {success, recordsIn, recordsOut, error, remoteReference}` → 204
- `POST /heartbeat {version, status, connections:[{connectionId, ok, message}]}` → `{latestVersion, pollSeconds}`

### 7.5 Shared-file rules for Wave 3

| File | Owner |
|---|---|
| `api/config/SecurityConfig.java` (client auth filter, public `/api/integration/oauth/token`, `/api/agent/v1/enroll`) | SE2. SE5 sends its public route line |
| `frontend/nginx.conf` + entrypoint (mTLS header pass-through from the trusted ALB only) | SE5 |
| `pom.xml` | SE4 adds the DB drivers and MINA SSHD. SE5 does **not** add a reactor module: `agent/` is a standalone Maven project with its own `pom.xml` and CI job |
| `frontend/src/pages/PendingChangesPage.jsx` | SE3 adds labels/summaries for all five integration ChangeTypes from §2 up front |
| `RoutePermissionCoverageTest.RESERVED` | each owner removes its own permission lines when its first route lands |
| `application.yml` | SE1 owns the settings block. Others add their own keys under `modocument.integration.*` / `modocument.agent.*` |
