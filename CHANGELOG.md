# Changelog

All notable changes to MoDocument are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

- Entries go under `## [Unreleased]`, in the sections Added, Changed,
  Deprecated, Removed, Fixed and Security.
- `scripts/bump-version.sh X.Y.Z` turns `[Unreleased]` into the release
  heading. It also sets the same version in `pom.xml` and
  `frontend/package.json` (+ lock).
- CI runs `scripts/check-version-sync.sh` and fails when the three disagree.
- The app serves the released sections at `GET /api/system/release-notes`.
- Release process: [docs/release/runbook.md](docs/release/runbook.md).
  Migration rules: [docs/release/migration-policy.md](docs/release/migration-policy.md).

## [Unreleased]

### Added
- Exact PDF templates for document creation: the template is the uploaded PDF itself, with an
  overlay of fill-in regions (text, multi-line, date, number, checkbox, photo, signature).
  Logo, fonts, layout and page geometry stay exactly as uploaded; generation draws the values
  onto it and refuses an output that is not pixel-identical outside the regions
  (`500 EXACTNESS_FAILED`, audited `DOC_GENERATED` FAILED).
- Two upload flows: a blank form becomes the template as-is; a filled document has its data
  removed first (`flow=BLANK|WITH_DATA|AUTO`). The upload is a DRAFT for review; the removed
  sample values are shown only during the review and deleted on confirm.
- Template editor API: `PUT /api/doccreation/blueprints/{id}/overlay` saves regions and edits
  (remove, replace or add text, add an image) as a new version with an optimistic
  `baseVersion` (409 when stale); page previews `GET …/blueprints/{id}/pages/{page}` (PNG,
  cached by content hash); template images `POST …/blueprints/{id}/edit-images`.
- WYSIWYG builder API: `PUT /api/doccreation/instances/{id}/values` merges values per field;
  page previews with the values drawn in; text that does not fit is shrunk to the new setting
  `doccreation.pdfMinFontSize` (default 6 pt, 4-10) and then continues on an added page, with an
  overflow warning in the builder.
- Photos: upload once into a tray (`POST /api/doccreation/instances/{id}/images`), auto-fill
  by file name and then reading order, drag onto any photo box (`PUT …/images/{imageId}`, a
  filled box swaps).
- Database (ws31): V300 template columns on `DocBlueprint`/`DocBlueprintVersion`, V301
  `DocTemplateRegion`, V302 `DocTemplateEdit`, `DocInstanceImage` and the document's
  `ValuesJson`/`GeneratedPdfKey`, all bank-scoped under row level security.
- Audit actions `DOC_BLUEPRINT_CREATED` and `DOC_LEGACY_PURGED`; `DOC_BLUEPRINT_VERSION_SAVED`
  and `DOC_GENERATED` now carry the region/edit counts and the exactness result.
- Data subject requests find and erase document values (`docValues` store); the retention purge
  also clears values, photos and the generated PDF of idle documents.

### Changed
- Document creation outputs PDF only: `download` and the approver's `review-file` always return
  `application/pdf` (`format` is ignored). The release queue lists the document's values and
  photos per region group.
- The setting `doccreation.sectionAiEnabled` is now labelled "Template AI assistance": it lets
  the model improve the regions and the data found in an upload; code detection always runs.
- Values and photos of a SUBMITTED or RELEASED document can no longer change (409), and a
  document changed after its last generation must be generated again before it is submitted.

### Removed
- Word templates: `.docx` upload, the PDF-to-Word reflow, sections, keywords, paragraph and
  image blocks, and the AI composer. Routes removed without the deprecation window (the feature
  was not deployed): `/blueprints/{id}/paragraphs`, `/sections`, `/resection`,
  `/sections/{sid}/keywords`, `/instances/{id}/fields`, `/instances/{iid}/sections/…/blocks`,
  `/blocks/{id}` and `/assets*` (docs/api/versioning-policy.md §8). The application no longer
  calls LibreOffice.
- Upgrade note: existing Word templates become `DOCX_LEGACY`; they are hidden from the lists and
  answer `410 GONE`. A bank administrator removes them, with their documents and stored files, via
  `POST /api/admin/doccreation/purge-legacy` (audited, the caller's bank only). The
  `doccreation.generateAiEnabled` setting has no effect any more and goes in a later release, as
  do the `DocBlueprintSection`/`DocContentBlock` tables.

### Fixed
- Saving the form fields of one section no longer wipes the other sections' values, and
  editing sections no longer drops form fields: both bugs belonged to the removed Word path,
  and document values are now merged per field.
- A filled PDF upload no longer removes low-confidence data guesses by default (BUG-PDF-01).
  A guess below 0.6 confidence, such as a free-standing e-mail address or date that may be
  printed form text, is now only suggested: the API returns it with `"applied":false` and its
  field with `"suggested":true`. It stays in the draft template, and the editor lists it as
  "Suggested — not removed", with Apply and Dismiss.
  - Confirming applies exactly the edits the client sends; unapplied suggestions are ignored.
  - Restoring a data removal in the editor also removes its linked field (`regionKey`), unless
    you edited that field.
- `detectedBy` reports `CODE+AI` only when the AI actually changed a label, group or kind, or
  the data classification.

### Security
- Admins must set up MFA again by default (`MODOCUMENT_MFA_REQUIRE_FOR_ADMIN` defaults to
  `true`, go-live gate B3). An earlier change had turned it off in every environment, though it
  was meant only for docker-compose and the `local` profile. Those two still turn it off.

## [1.2.0] - 2026-10-11

Optimization release: faster pages and APIs, a smaller and cheaper deployment,
a complete audit trail with a viewer, and one connector library shared by the
server and the Connector Agent. The ingestion job list drops from 16.5 MB to
about 20 kB, the initial JavaScript halves (614.9 → 292 kB), and the
application jar shrinks from 369 MB to 157 MB. Read the migration note under
Changed before upgrading database-export callers or agent database flows.

### Added
- Audit log API: `GET /api/admin/audit` returns the caller's bank events newest first, with
  filters (actor, action, entity, outcome, date range), keyset paging and per-role masking of
  values. `GET /api/admin/audit/export` returns CSV, at most 50 000 rows. `GET
  /api/admin/audit/actions` returns the action catalogue. The platform console gets
  `GET /api/platform/audit` (the platform bank's own events) and `GET
  /api/platform/audit/counts` (counts per bank). Permission `AUDIT_READ` (ADMIN, APPROVER,
  PLATFORM_ADMIN). Viewing is audited `AUDIT_LOG_VIEWED` and exporting `AUDIT_LOG_EXPORTED`.
- New audit actions:
  - `TEMPLATE_CREATED`, `TEMPLATE_FIELD_CREATED/UPDATED/DELETED`
  - `VALIDATION_RULE_CREATED/UPDATED/DELETED`
  - `FILE_UPLOADED`, `INGESTION_JOB_CREATED`, `INGESTION_REMAPPED`, `INGESTION_ROWS_EDITED`
  - `DOC_GENERATED`, `DOC_DOWNLOADED`, `EXPORT_DOWNLOADED`, `TOKEN_REFRESHED`
  - `ACCESS_DENIED`: use-case refusals, owner-scoped 404 probes and invalid bearer tokens. It is
    aggregated to one row per actor or address per minute, with a count.
  - `SENSITIVE_DATA_VIEWED`: ingestion job detail, extraction records, user list and DSR search,
    once per actor, entity and hour, gated by `audit.readAuditEnabled`.

  The catalogue is in `docs/security/audit-events.md`.
- Alert rule `access-denied-burst` (windowed, MEDIUM), tunable with
  `alerts.accessDeniedBurst.threshold|window`.
- Audit log page `/admin/audit` (ADMIN, APPROVER) and `/platform/audit` (PLATFORM_ADMIN):
  filters, "Load more" keyset paging, CSV export, an old → new diff view, entity links and,
  on the platform variant, counts per bank.
- `GET /api/dashboard/summary` (`ingestion:read`): the dashboard's counts and newest rows in one
  call, from count queries.
- Paged lists. `GET /api/ingestion/jobs?view=summary&page=&size=` returns an
  `IngestionJobSummary` page (`{items, page, size, total}`, at most 200 per page) with no parsed,
  mapped or validation JSON. The same envelope is used by `GET /api/admin/users`, the pending
  change list and integration runs when `page` is sent; without `page` the old array is returned.
- File storage port with two backends, chosen by `modocument.storage.backend`: `local` (the
  default, today's disk layout) and `s3` (SSE-KMS, keys under `bank/{id}/…`). Uploads, export
  copies, doc-creation assets and exports, retention and DSR deletes all go through it.
  Terraform gets a `file-store` module (versioned, encrypted, TLS-only bucket with lifecycle
  rules, task-role IAM).
- OCR works in the image: `tesseract-ocr` with English data is installed, so scanned PDFs and
  images are read instead of silently skipped.
- AI result cache (`ai.resultCache.enabled`): an identical document text, template version,
  mode, model and prompt version reuses the earlier result instead of calling the model. It is
  bank-scoped under RLS and purged with retention.
- `HousekeepingJob` (daily): bounded deletes of old refresh tokens, expired revoked-token
  entries, finished integration runs and their logs, terminal outbox rows, unreferenced
  integration events and expired idempotency keys, audited `HOUSEKEEPING_COMPLETED` per bank.
  New setting `integration.historyRetention` (90 days, 7–400).
- `connector-core`: one connector library (REST, database, SFTP, webhook) shared by the server
  and the Connector Agent. The agent gains OAuth2 client-credentials and mTLS for REST
  connections. See `docs/integration/connector-sharing.md`.

### Changed
- **Migration note — database export.** `POST /api/ingestion/jobs/{id}/export/database` no
  longer accepts a raw `jdbcUrl`; such a body gets a 400. Send the target as `vendor`
  (`POSTGRESQL`, `ORACLE`, `SQLSERVER` or `MARIADB`), `host`, `port` (optional, vendor default),
  `database`, `schema` (optional) and `sslmode` (optional: `disable`, `allow`, `prefer`,
  `require`, `verify-ca` or `verify-full`), plus `username`, `password`, `tableName` (or `table`),
  `createTableIfMissing` and `reason` as before. The server builds the JDBC URL with the
  integration hub's `DbDialect` and applies connect, login, socket and query timeouts. The
  destination passes the bank-aware egress check, so a private-network target needs an approved
  egress rule. Example: `jdbc:postgresql://db:5432/staging?sslmode=require` becomes
  `{"vendor":"POSTGRESQL","host":"db","port":5432,"database":"staging","sslmode":"require"}`.
  H2 targets are no longer accepted.
- **Faster reads.**
  - The ingestion job list no longer returns every job's JSON. The UI asks for the summary page:
    13.7 MB → 18 KB for 50 jobs of 1,000 rows. The legacy unpaged list is capped at the 200
    newest jobs. A single job's JSON columns are parsed once per request.
  - N+1 queries removed: `/ImportDetails/GetAll` (bank query instead of an in-memory filter),
    the doc release queue, the blueprint list, the pending-change list, the Integration API
    `/templates` and template owner names.
  - Each authenticated request reads `AppUsers` once instead of twice.
- **Database.**
  - New indexes (built `CONCURRENTLY`) for the audit alert window, dashboard run counts, the
    outbox and run ticks, the pending-change list and housekeeping. The alert sweep's query is
    bounded to its window.
  - Hikari pool sized explicitly (max 20, `MODOCUMENT_DB_POOL_MAX`; leak detection on) and
    Hibernate JDBC batching (50, ordered inserts and updates).
  - Extraction records are saved one transaction per batch, not per record. The outbound
    dispatcher claims deliveries with a single `UPDATE … RETURNING` on PostgreSQL.
- **Scheduling.** A scheduler pool of 4 threads; the integration tick runs on its own executor,
  so a slow bank endpoint no longer delays settings sync or alerts.
- **Frontend load.** Pages are code-split (initial JS 614.9 kB → 292 kB, 182.8 → 93 kB gzip).
  `qrcode` loads only on MFA enrolment. nginx compresses CSS, JS, JSON and SVG (level 5), caches
  `/assets/` for a year as immutable and serves `index.html` with `no-cache`. Polling pauses in
  hidden tabs and never overlaps calls; version and supported-types calls are made once per
  session.
- **AI cost.** Row batches use a column/rows output shape (−38 % output tokens on the golden
  corpus; the old shape is still parsed). Chat edits and extraction agents use the configured
  default model.
- **Footprint.**
  - The application jar is 369 MB → 157 MB. MongoDB and Kafka moved to the `scale` Maven
    profile; a default jar started in scale mode refuses to start.
  - The image uses a layered jar and an AppCDS archive (startup about 25 % faster). The JVM
    runs with `MaxRAMPercentage=70`, G1 and exit on out-of-memory.
  - Terraform: tasks of 1 vCPU / 3 GiB (two per node), the NAT gateway count, RDS Multi-AZ and
    log retention are variables (staging: one NAT, single-AZ, 90-day logs; about −$128 a
    month). CloudFront caches and compresses `/assets/*`.
- **Connector behaviour (server and agent now share one implementation).**
  - SFTP reads are two-phase: a file is moved or deleted only after MoDocument has accepted it.
    A file that cannot be ingested stays in the folder and fails the run.
  - An oversized SFTP file is skipped and left in place on both sides (the agent used to fail
    the run).
  - Agent database flows quote table and column names like DIRECT flows, so they are
    **case-sensitive**. On Oracle, name the view in upper case. The schema comes from the
    connection's `schema` setting.
  - Database values arrive as text and binary columns are not record data, as on the server.
    Cursors stored by older agents still work.
  - Full list: `docs/integration/connector-sharing.md` §6.
- CI: the PostgreSQL job runs only the integration tests, JaCoCo runs behind `-Pcoverage`, and
  Docker builds use Maven and buildx caches. Test Spring contexts 27 → 19.

### Deprecated
- Unused legacy routes (sunset 2027-04-01). Each answers with `Deprecation`, `Sunset` and `Link`
  headers and is marked `deprecated` in OpenAPI:
  - `/ImportDetails/*`, `/ProcessStep/*`
  - TemplateDataTypes Create/Update/GetById, ImportHistory Create/Update/GetById
  - `/TemplateFields/GetById`, `/api/automapper/generate`, `/api/fileupload/upload`,
    `/api/extraction/metrics`

  See `docs/api/versioning-policy.md` §7.

### Removed
- `POST /api/aifiles/send` (an open AI prompt proxy) and the unused
  `POST /api/transform/transform`. Both were removed under the security exception of the
  versioning policy.
- Playwright and the JavaScript-rendering web fetcher (189 MiB). Web extraction sources fetch
  pages as plain HTML, as the image always did; the "Render JavaScript" option is shown as
  unavailable.

### Fixed
- `PUT /api/ingestion/jobs/{id}/mapping` (the manual mapping editor) writes one
  `INGESTION_REMAPPED` audit row (source `MANUAL`, mapped column count, no cell values) (BUG-10).
- `POST /api/ingestion/jobs/{id}/chat-edit` writes one `INGESTION_ROWS_EDITED` audit row with
  the row and changed-row counts, never cell values, like `PUT …/rows` (BUG-11).
- A stored-object delete that fails after a retention purge, a DSR erase or a job deletion is
  no longer lost: 3 attempts in the call, then the key is queued in the bank-scoped
  `PendingFileDelete` table (V290) and retried every 5 minutes with backoff until the store
  confirms it (`modocument.retention.file-delete-retry.*`) (BUG-12).
- Alert rule `access-denied-burst` counts refusals, not aggregated rows: the windowed sweep sums
  the `count` of each `ACCESS_DENIED` row, so a burst inside one minute reaches the threshold
  (OBS-01). The default stays 3 refusals per actor or address in 5 minutes; a platform admin
  can raise it to 12 with `alerts.accessDeniedBurst.threshold`.
- A DSR erase clears the bank's AI result cache, which holds copies of extracted rows; the
  cache is also listed in the retention schedule (30 days).
- A page whose code chunk is still missing after the one automatic reload shows an error with
  "Reload page" and "Go to home" instead of a blank page (BUG-09).

### Security
- SSRF: the extraction REST connector and the web crawler no longer let the HTTP client follow
  redirects. Each hop is re-checked against the egress policy, and credentials never follow a
  redirect to another origin.

## [1.1.0] - 2026-10-10

Phase 2: settings every admin can change within guardrails, a platform owner
console, the admin dashboard, code-based extraction when the AI is
unavailable, a release pipeline with in-app release notes, and the Bank
Integration Hub (connections, flows, the Integration API and the on-prem
Connector Agent). Before the first deployment of this version, set
`MODOCUMENT_CONNECTOR_KEY` and the platform-admin bootstrap secrets in each
environment (docs/release/runbook.md §11).

### Added
- Release versioning: one version for `pom.xml`, `frontend/package.json` and
  this changelog, kept in sync by `scripts/bump-version.sh` and checked in CI
  by `scripts/check-version-sync.sh`.
- Build identity: commit id and build time in every build
  (`git.properties`, Spring Boot build info). Docker build arguments
  `MODOCUMENT_VERSION`, `GIT_COMMIT` and `BUILD_TIME` also set the OCI image
  labels.
- `GET /api/system/version` shows the running version, commit, build time,
  database schema version and environment. `GET /api/system/release-notes`
  shows the release notes from this file. Every signed-in bank user can call
  both (permission `SYSTEM_INFO`).
- Migration safety checks: `MigrationPolicyTest` enforces the
  expand/contract rules for migrations from V200 on. `FlywayVersionRangeTest`
  checks every migration against the version ranges in
  `db/migration/RANGES.md`. Rules: `docs/release/migration-policy.md`.
- Phase 2 contracts: roles `PLATFORM_ADMIN` and `API_CLIENT`, the new
  permission and maker/checker change-type vocabulary, and the platform bank
  lookup.
- Editable settings with guardrails: every operational setting is changeable
  per bank from the admin console; security settings need a second admin's
  approval and can never go below the hard limits.
- Platform owner console (`/platform`): an overview of every customer bank
  (counts only), onboarding a bank with its first users, suspending and
  reactivating a bank, and a break-glass administrator. Onboarding,
  suspension and the break-glass administrator need a second platform admin's
  approval.
- An optional second bootstrap administrator
  (`MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL` / `_PASSWORD`), so a fresh
  install can approve its first user change. The app warns at startup while
  the default bank has fewer than two administrators or approvers.
- Release pipeline: pushing a `vX.Y.Z-rc.N` or `vX.Y.Z` tag runs the tests,
  builds each image once, scans it (fixable HIGH/CRITICAL findings stop the
  release), writes CycloneDX SBOMs, deploys staging with images pinned by
  digest and smoke-tests it, and creates the GitHub Release. `vX.Y.Z` then
  deploys the same images to production after two approvals. The *Deploy*
  workflow redeploys an existing version (rollback or roll-forward).
- Announcements: the platform owner publishes information, maintenance and
  release banners to every bank or to chosen banks, with a display window.
  Maintenance banners show their window in local time and cannot be
  dismissed. Each new version announces itself once and records
  `VERSION_DEPLOYED` in every bank's audit trail.
- The app shows its version on the profile page, offers a reload when the
  server runs a newer version, and gives admins the release notes of a new
  version ("What's new").

- Bank Integration Hub (Admin → Integrations): MoDocument plugs into a
  bank's own systems to receive and send data.
  - Connections to REST/JSON APIs, databases (PostgreSQL, Oracle, SQL Server,
    MySQL/MariaDB), SFTP drops and webhooks, either directly from the cloud
    or through the bank's on-prem Connector Agent. Credentials are
    write-only, encrypted per bank (`MODOCUMENT_CONNECTOR_KEY`) or never
    leave the bank (agent mode). New or changed connections need a second
    admin's approval.
  - Flows: inbound (pull records or files on a schedule or on demand into
    ingestion) and outbound (push validated records, rejected rows, job
    status, released documents or events when they happen). The admin ticks
    what is sent; personal data is masked by default; enabling an outbound
    flow needs a second admin. Dry-run preview, run history, retries and a
    dead-letter queue.
  - Private-network destinations (VPN/PrivateLink) only after the platform
    owner approves an egress rule.
- Integration API v1 (`/api/integration/v1`) for the bank's systems: list
  shared templates, send records or files, read job status, results and
  errors, released documents and an event feed. API clients authenticate
  with OAuth2 client credentials, API keys or mutual TLS, chosen per client,
  with IP allow-lists, expiry, rotation and instant revocation. Developer
  guide and Postman collection in `docs/integration/`.
- On-prem Connector Agent (`agent/`): a small outbound-only service the bank
  runs inside its network; enrolled from the dashboard with a one-time
  token, limited by a bank-controlled local allow-list. Released as an image
  and a jar with each version.
- Admin dashboard: integration counts for the bank (runs in the last 24 h,
  dead letters, offline agents, Integration API calls) and the share of
  uploads parsed by AI or by code over 7 days.

- Template extraction settings: each template can follow the bank's default
  extraction mode or choose AI with code fallback, code only or AI only, and
  carry per-field aliases, label/value patterns and date formats.
- Jobs show how they were parsed ("Extracted by code — AI unavailable
  (quota)"), the code confidence and a low-confidence warning.
- Schema inference and extraction summaries keep working without the AI,
  from the sample values and counters (marked as produced by code).
- The platform owner can restrict, per bank, which API authentication
  methods its clients may use (OAuth2, API keys, mutual TLS); bank admins
  see these settings read-only.
- Connector Agent: REST pagination (page, cursor and next-link), mutual-TLS
  authentication of the agent itself, and an `agent-offline` alert when an
  agent stops sending heartbeats.
- Per-bank integration counts on the platform bank page (counts only).
- End-to-end tests in CI: pull requests run the smoke set; `main` also runs
  the integration-hub suite against a bank simulator (database, REST, SFTP).
- A local mutual-TLS listener for developers (`docker-compose.mtls-dev.yml`).

### Changed
- Accessibility of the admin, settings, platform and integration pages:
  every table is named, colour-only status has a text equivalent, a skip
  link, status and error announcements, and keyboard focus that stays
  in place in the connection wizard and the flow builder.
- The Agents page offers the one-time enrolment command only while it can
  still be read, and shows when it expires.
- Bootstrap administrators must change their configured password at first
  sign-in. `MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE=true` turns this
  off on dev stacks only.
- Users of a suspended bank cannot sign in or refresh a session, and their
  live sessions end when the suspension is approved.
- The weekly image rebuild opens (or updates) the issue "Security: base image
  refresh" when it finds fixable HIGH or CRITICAL vulnerabilities; it never
  pushes images.
- `GET /api/system/version` now reports `deployedAt`.
- Terraform (not yet applied): the ECS service rolls back a failed deployment
  automatically (deployment circuit breaker). CI now owns task-definition
  revisions, so Terraform ignores them.
- Terraform: the app container gets `MODOCUMENT_DEPLOY_ENVIRONMENT` and
  `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true`.
- Terraform: the CI deploy roles trust GitHub `environment:staging` and
  `environment:production` subjects. Production also keeps
  `refs/heads/main` for one release, during the transition.

- Staging and production refuse to start without `MODOCUMENT_CONNECTOR_KEY`.
- Every pending change is listed to, and decided by, the right checkers:
  other approvers of the same bank, or a second platform admin for
  platform changes.
- An API client stops working when the user who created it is disabled or
  removed; revoking a Connector Agent revokes its API client at once.
- Terraform: optional platform-admin bootstrap secrets
  (`platform_admin_bootstrap_enabled`).

### Fixed
- An AI model that stopped answering could be retried and recorded as a
  generic error; it is now recorded as a timeout, counts toward the AI
  circuit breaker and goes straight to the code fallback.
- After a forced password change, the sign-in page shows the "Password
  changed" notice again.
- A failure after a new bank was created no longer leaves the bank and its
  first users behind; the onboarding is reverted and can be approved again.
- A break-glass bank administrator's password is checked against that
  bank's own password policy.
- `scripts/bump-version.sh` accepts release-candidate versions (`1.1.0-rc.1`).

## [1.0.0] - 2026-10-10

First production-ready release: the Go-Live Security Gate program (20
workstreams) merged into `main`.

### Added
- Banks as tenants, the roles ADMIN, APPROVER, READ_WRITE and READ_ONLY, and
  revocable JWT sessions. Only an admin can create users.
- TOTP multi-factor authentication. Admins must use it.
- OIDC sign-in through Entra ID or Keycloak, with just-in-time provisioning.
- Bank-scoped user administration with a joiner/mover/leaver lifecycle and a
  user-list export.
- Maker/checker approval for user and template changes. Report release is
  split from report preparation, and AI-generated content needs a review.
- A 30-day retention purge, plus data-subject export and erasure.
- Security alerts (webhook, e-mail), a bank-scoped SIEM feed and a public
  synthetic uptime probe.
- An OpenAPI contract generated from the code, with the route list pinned in
  CI. k6 load-test scenarios.
- Terraform for AWS af-south-1: ECS on EC2, Multi-AZ RDS PostgreSQL,
  CloudFront and WAF, immutable backups and GitHub OIDC deploy roles. Not yet
  applied.
- A security evidence pack under `docs/security/`.

### Changed
- Every route now requires a named permission. A test fails the build if a
  route has none.
- Uploads, ingestion, exports, document generation and authentication are
  rate-limited. Responses say when to retry (`Retry-After`).
- The AI gateway is local-first. Prompts are pseudonymised, a prompt never
  mixes data from two banks, and model output is scanned before use.

### Fixed
- ECS go-live blockers: the trusted proxy chain behind the ALB and
  CloudFront, the first-boot admin, and the RDS CA bundle in the image.
- Extraction counters no longer lose increments when two writers create the
  first row at the same time.

### Security
- Tenant isolation in the database: PostgreSQL row-level security, a
  separate runtime role without BYPASSRLS, and a PostgreSQL integration test
  suite.
- An append-only audit trail with request IDs, and structured logs.
- Passwords hashed with Argon2id, a password policy, and account lockout with
  backoff. Session lifetimes are bounded.
- Request validation, database integrity constraints and upload content
  checks.
- PII classification with masking by role and channel. Logs redact PII and
  credentials.
- Security headers. Containers run as non-root with all capabilities dropped.
- nginx drops client-sent `Forwarded` and `X-Forwarded-*` headers.
- Supply chain: GitHub Actions pinned by SHA, gitleaks, semgrep, npm audit,
  CycloneDX SBOMs, Dependabot, CODEOWNERS review and documented
  branch-protection settings.

[Unreleased]: https://github.com/guyi34super/MODOCUMENT/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/guyi34super/MODOCUMENT/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/guyi34super/MODOCUMENT/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/guyi34super/MODOCUMENT/releases/tag/v1.0.0
