# MO-009 Part 5 — Logging, monitoring, backup and restore, troubleshooting

Part of the [MO-009 Developer & Technical Documentation set](../mo-009-developer-technical-documentation.md). Command results: [A2-verified-commands.md](../evidence/A2-verified-commands.md).

## 1. Logging

### 1a. Application logs

| Feature | How | Proof |
|---|---|---|
| Correlation | Every request gets a request id (an inbound `X-Request-Id` is honoured when it is a safe token), echoed in the `X-Request-Id` response header and put in the MDC with the client IP | `src/main/java/com/modocument/infrastructure/security/RequestIdFilter.java` |
| Identity on every line | MDC `userId`, `role` (`IdentityContextFilter`), `bankId` (`TenantContext`, `*` for system work), copied to async threads (`TenantTaskDecorator`) | `src/main/resources/logback-spring.xml` header; `src/main/java/com/modocument/infrastructure/security/IdentityContextFilter.java`; `src/main/java/com/modocument/infrastructure/async/TenantTaskDecorator.java` |
| Default format | Spring Boot's console pattern plus `[requestId] [u:userId b:bankId]` | `src/main/resources/logback-spring.xml`; seen in the 2026-10-19 run log (C5, C7) |
| Structured JSON | Profile `structured-logs`: Spring Boot structured logging, format `MODOCUMENT_LOG_FORMAT` = `ecs` (default), `logstash` or `gelf`; MDC keys become JSON fields | `src/main/resources/application.yml` (`structured-logs` document) |
| Redaction | PII and credential patterns masked in the message and in exception text (`%mask`, `%maskEx`), and in every string member of structured JSON (`RedactingJsonMembersCustomizer`) | `src/main/java/com/modocument/infrastructure/logging/RedactingConverter.java`; `src/main/java/com/modocument/infrastructure/logging/RedactingJsonMembersCustomizer.java` |
| AI calls | Prompts and completions are never stored or logged; logs and `AI_CALL` audit rows carry purpose, model and lengths only | `README.md` "AI integration"; `src/test/java/com/modocument/infrastructure/ai/AiGatewayC8LoggingTest.java` |
| Slow connections | A DB connection held longer than 30 s logs a WARN with the borrowing stack | `src/main/resources/application.yml` (`leak-detection-threshold`) |

**Where the logs go.**

- Local: the console (`mvn spring-boot:run`), or `docker compose logs app frontend`.
- AWS: the ECS task definition sends both containers to one CloudWatch log group with the `awslogs` driver (stream prefixes `app` and `frontend`), kept 90 days (`infra/terraform/modules/ecs-app/main.tf`; `log_retention_days = 90` in `infra/terraform/envs/production/main.tf`). The 7-year WORM archive bucket is in `infra/terraform/modules/backups/main.tf` (`log_archive`). Real log data: `NOT IN REPO (live cloud)`.
- Gap: the Terraform envs leave `spring_profiles` empty, so ECS tasks log the human-readable pattern, not JSON. To ship JSON, set `spring_profiles = "structured-logs"` in the env's `ecs_app` module (`infra/terraform/modules/ecs-app/variables.tf`). Decision owner: `TO CONFIRM (organisational)`.

### 1b. Audit trail

- Table `AuditEvent` (migration `V14__audit_event.sql`), append-only, enforced in the database and tested by `AuditAppendOnlyPgIT` (`src/test/java/com/modocument/pgit/AuditAppendOnlyPgIT.java`). Row anatomy: id (UUIDv7), time, bank, actor, session jti, request id, IP, action, outcome, entity, old/new values (`docs/security/audit-events.md`).
- Catalogue of actions: `docs/security/audit-events.md`, generated from `AuditActions` and pinned by `AuditActionCatalogueDocTest`.
- **Audit viewer:** Administration → Audit log (`frontend/src/pages/AuditLogPage.jsx`, `/admin/audit`; platform: `/platform/audit`). API: `GET /api/admin/audit` (filters, keyset paging), `/api/admin/audit/export` (CSV, at most 50 000 rows, masked), `/api/admin/audit/actions`, `/api/platform/audit[/counts]`; permission `audit:read` (`docs/security/audit-events.md` "Viewer and API").
- To trace one action: take the `X-Request-Id` from the response or the log line and filter the audit viewer on it.

### 1c. Security alerts

- Rules are data in `application.yml` (`modocument.alerts.rules`): 26 rules (C11), each `IMMEDIATE` (fires on one audit row after commit) or `WINDOWED` (evaluated by a sweep every minute), deduplicated per rule/bank/group (`src/main/resources/application.yml`; `src/main/java/com/modocument/infrastructure/alerting/AlertRuleCatalogue.java`).
- Channels: the log (always, logger `modocument.security.alert`, tag `SECURITY_ALERT`), a webhook (`MODOCUMENT_ALERTS_WEBHOOK_URL`, optional HMAC `X-MoDocument-Signature`), e-mail (`MODOCUMENT_ALERTS_EMAIL_TO` + `SPRING_MAIL_HOST`). Every alert and its delivery is recorded in `AlertLog` and shown under Administration → Security alerts (`frontend/src/pages/AdminAlertsPage.jsx`, `GET /api/admin/alerts`) (`docs/security/alerting-and-export-caps.md`).
- Windowed thresholds are editable settings (`docs/settings/catalogue.md`, "Security alerts" tab).

### 1d. SIEM export

`GET /api/siem/events` (`siem:read`) streams the caller's bank's audit events as NDJSON in an ECS-mapped schema; an optional single-bank webhook push is configured with `MODOCUMENT_SIEM_WEBHOOK_URL`, `_SECRET`, `_BANK_CODE` (`docs/security/siem-event-schema.md`; `src/main/java/com/modocument/api/controller/SiemFeedController.java`). A test against a bank's real SIEM is open (README gate SEC-031): `NOT IN REPO (live cloud)`.

## 2. Monitoring

### 2a. Health and probe endpoints

| Endpoint | Auth | Answers | Used by | Proof |
|---|---|---|---|---|
| `GET /actuator/health` | none | `{"status":"UP"}` (Mongo and mail health excluded so they cannot mark the app DOWN) | Docker `HEALTHCHECK`, compose healthcheck, ECS container health check | `Dockerfile`; `docker-compose.yml`; `infra/terraform/modules/ecs-app/main.tf`; `src/main/resources/application.yml` (`management`) |
| `GET /api/probe/synthetic` | none, 60/min per IP | `{"status","version","db","checkedAt"}` — one DB round trip through proxy → app → DB | External uptime monitors; the release smoke test | `src/main/java/com/modocument/api/controller/SyntheticProbeController.java`; `scripts/release/smoke-test.sh` |
| `GET /nginx-health` | none | `ok` | nginx liveness | `frontend/nginx.conf` |
| `GET /api/system/version` | `system:info` | version, commit, schema version | Operators, soak checks | `docs/release/runbook.md` §3 |

**Verified (C7)** on the `local` profile: health `{"status":"UP"}`; the probe returned `{"status":"UP","version":"1.2.0","db":true,…}`.

### 2b. Metrics

- Actuator exposes **health only** (`management.endpoints.web.exposure.include: health`); there is no Micrometer registry such as Prometheus in `pom.xml`. **Verified (C7)**: `GET /actuator/metrics` is not served anonymously (401).
- Application figures are in the product: the admin dashboard (`GET /api/admin/dashboard`, `metrics:read`; `AdminDashboardPage.jsx`), the platform overview (`GET /api/platform/overview`), and the deprecated `GET /api/extraction/metrics`.
- AWS metrics and alarms (`infra/terraform/modules/security-monitoring/main.tf`): ALB target 5xx, ECS CPU, RDS free storage, and an `AccountLocked` metric filter on the app log group (more than 5 lockouts in 15 minutes); GuardDuty findings via EventBridge; all to one SNS topic with an optional ops e-mail subscription. CloudTrail with log-file validation goes to a CloudWatch log group (≥ 365 days, 400 by default) and a locked bucket. VPC flow logs: 30 days (`infra/terraform/modules/network/main.tf`; env `flow_log_retention_days = 30`).
- None of this is applied: `NOT IN REPO (live cloud)`. SLA targets and the status-page plan: `docs/security/sla-and-status.md`.

### 2c. Scheduled jobs to watch

Retention purge, housekeeping, SoD scan, alert sweep, settings poll, agent-offline sweep, integration tick and extraction retry sweeper share one scheduler pool (`MODOCUMENT_SCHEDULER_POOL_SIZE`, default 4) (`src/main/resources/application.yml` `spring.task.scheduling`). Their crons and switches: `MODOCUMENT_RETENTION_*`, `MODOCUMENT_HOUSEKEEPING_*`, `MODOCUMENT_SOD_SCAN_*`, `MODOCUMENT_ALERTS_SWEEP_*` (`src/main/resources/application.yml`).

## 3. Backup and restore

### 3a. What the Terraform configures (written, not applied)

| Data | Mechanism | Retention | Proof |
|---|---|---|---|
| PostgreSQL (RDS) | Automated backups + point-in-time recovery; backup window 01:00–02:00 UTC; automated backups kept on delete; final snapshot on destroy; deletion protection on in production; Multi-AZ in production | 35 days (module default; the envs do not override it) | `infra/terraform/modules/rds/main.tf`; `infra/terraform/modules/rds/variables.tf`; `infra/terraform/envs/production/main.tf` |
| PostgreSQL (AWS Backup) | Daily plan at 00:30 UTC, copy to a replica vault in ap-southeast-2; resources selected by tag `Backup=modocument` (the RDS instance carries it) | 90 days in both vaults | `infra/terraform/modules/backups/main.tf`; `infra/terraform/modules/backups/variables.tf`; `infra/terraform/envs/production/providers.tf` |
| Vault lock (immutability) | Compliance-mode vault lock on both vaults, 3-day cooling-off, min 90 / max 366 days — only when `enforce_backup_locks = true`, which is to be set **after** the restore drill; staging never locks | — | `infra/terraform/modules/backups/main.tf`; `infra/terraform/envs/staging/main.tf`; `infra/terraform/README.md` "Apply order" |
| Log archive | S3 bucket with Object Lock (COMPLIANCE, when locks are on), versioning, Glacier lifecycle | 7 years (2557 days) | `infra/terraform/modules/backups/main.tf` (`log_archive`) |
| Files (uploads, exports, PDF templates, document assets) | S3 file store: versioning, SSE-KMS, public access blocked; non-current versions expire after 7 days; `exports/` expires after 30 days; `uploads/` expiry off by default | No backup copy beyond versioning | `infra/terraform/modules/file-store/main.tf` |

**Gap.** The file store is not in the AWS Backup selection and has no cross-region copy; a deleted or overwritten object can be recovered only within 7 days through S3 versioning. The bucket also holds PDF templates (configuration, never purged by retention). Proposed action: add the bucket to an AWS Backup plan or replicate it; owner `TO CONFIRM (organisational)`. The backups module's own comment still says to "add the uploads EFS/S3 when file storage moves off the container volume" (`infra/terraform/modules/backups/main.tf`), and ws29 has since moved it (`infra/terraform/modules/file-store/main.tf` header).

Targets: RPO ≤ 24 h from vault recovery points, ≤ 5 min via RDS PITR within the 35-day window; RTO target 4 h, "validated by the gate D6 restore drill — pending" (`docs/security/sla-and-status.md`).

### 3b. Restore drill status

No restore-drill record exists in the repository. The drill is gate D6, owned by Rushal, to be run in staging before the production locks are switched on (`docs/security/security-roadmap.md`; `README.md` "Implement later"). Status: `NOT IN REPO (live cloud)`. The repository has no written AWS restore runbook; §3d below is a proposed procedure that the drill must confirm.

### 3c. Local and development backups

The compose stack keeps data in the named volumes `modocument-postgres` and `modocument-files` (`docker-compose.yml`). The repository contains no backup script. A logical backup and restore of the database works as follows — **verified (C7b)** on 2026-10-19 against a database freshly migrated by the app (PostgreSQL 16 client and server):

```bash
pg_dump -h <host> -p <port> -U <superuser> -Fc -f modocument.dump <database>
createdb -h <host> -p <port> -U <superuser> -O <owner> <restore_database>
pg_restore -h <host> -p <port> -U <superuser> -d <restore_database> --no-owner --role=<owner> modocument.dump
```

Result of the verified run: dump 311,508 bytes; restore in about 1 s; source and restored database both showed 60 tables, 58 rows in `flyway_schema_history` and 50 row-level-security policies.

Notes:

- Stop the app or accept a non-quiescent dump; `pg_dump` itself is transactionally consistent.
- Restore as the schema **owner**, not as `modocument_app`, and re-grant `modocument_app` if the role does not exist on the target (`docker/postgres-init/01-app-role.sh`; `src/main/resources/db/migration/V12__app_role_grants.sql`).
- Files: back up the storage root (`MODOCUMENT_STORAGE_ROOT`, default `wwwroot`, the `modocument-files` volume in compose) together with the dump, because rows reference file keys. Not verified here (no Docker daemon).

### 3d. Proposed AWS restore procedure (to be confirmed by the D6 drill)

1. Declare the incident and freeze deployments (`docs/security/incident-response-stub.md`).
2. Choose the recovery point: an RDS point in time within 35 days, or an AWS Backup recovery point (90 days, local or ap-southeast-2 vault).
3. Restore to a **new** RDS instance in the same DB subnet group and security group, with the same KMS key and parameter group (`rds.force_ssl=1`) (`infra/terraform/modules/rds/main.tf`).
4. Point the app at it: the task's `MODOCUMENT_DB_URL` comes from the module input `db_jdbc_url` (`infra/terraform/modules/ecs-app/main.tf`); change it through Terraform, then re-deploy the current version with `.github/workflows/deploy.yml`.
5. Files: restore objects from S3 versions (within 7 days) if needed.
6. Check: `/api/probe/synthetic` reports `db: true`; `GET /api/system/version` shows the expected schema version; sample business records are present; the audit trail is continuous.
7. Record times achieved against RTO/RPO in the drill log.

Every step above is `NOT IN REPO (live cloud)` until the drill has been run and recorded.

## 4. Troubleshooting / FAQ

| Symptom | Cause | Fix | Proof |
|---|---|---|---|
| `docker compose up` stops with `required variable … is missing a value` | One of the four required secrets is not set | Create `.env` from `.env.example` with generated values | `docker-compose.yml`; verified (C6) |
| App stops at start-up: `MODOCUMENT_JWT_SECRET is too short` or set to the development default | Missing, short or known JWT key | `openssl rand -base64 48` | `src/main/java/com/modocument/api/config/SecurityConfig.java` |
| App refuses to start on an empty database | No bootstrap admin pair | Set `MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | `docker-compose.yml` comment; `src/main/java/com/modocument/infrastructure/bootstrap/BootstrapAdminRunner.java` |
| `Refusing to start: MODOCUMENT_CONNECTOR_KEY is not set` | Guardrails on (staging/production) without the connector key | Set the key (`openssl rand -base64 32`) | `docs/integration/secrets.md` |
| App stops with a message about the `scale` profile | A default jar started with `scale` (no Mongo/Kafka client) | Build with `-Pscale` or use `extraction.store=sql` | `src/main/java/com/modocument/infrastructure/extraction/ScaleModeEnvironmentGuard.java` |
| Maven cannot resolve `modocument-connector-core` | The sibling library is not installed | `mvn -B -f connector-core/pom.xml install` | `README.md` "Build & test" |
| Flyway checksum / validation error at start-up | An applied migration was edited | Revert the edit; add a new migration instead | `docs/release/migration-policy.md` |
| `OpenApiContractTest` fails after adding an endpoint | The route inventory changed | Regenerate `docs/api/openapi-routes.txt` and review the diff | `docs/api/versioning-policy.md` §1 |
| `RoutePermissionCoverageTest` fails | New handler without `@RequiresPermission` | Add the permission | `src/test/java/com/modocument/api/security/RoutePermissionCoverageTest.java` |
| Every call answers 403 right after the first login | `MustChangePassword` is set | Change the password (`PUT /api/auth/password` or the forced page), sign in again | `src/main/java/com/modocument/infrastructure/security/PasswordChangeRequiredGate.java`; verified (C7) |
| `curl` to `localhost:8080` fails or answers 401 | Compose does not publish 8080; no bearer token | Go through `http://localhost:3000`, or uncomment the loopback port mapping; send `Authorization: Bearer <token>` | `docker-compose.yml`; verified (C7) |
| New users cannot be created | Only one administrator: maker/checker needs a second | Seed the second admin, or the platform owner adds a break-glass admin | `README.md` "First run: two administrators"; `docs/platform/platform-owner.md` |
| AI features answer 503, or jobs show `parsedBy: CODE` | No reachable model, bank AI switch off, circuit breaker open or quota spent | Start Ollama and `ollama pull qwen2.5`, or set `MODOCUMENT_AI_*`; read `parseFallbackReason` | `README.md` "Code-based fallback"; `docs/security/ai-controls.md` |
| 429 during local testing | Per-IP/per-user limits; all local traffic comes from one address | Raise the limits for the test run only (as `e2e/run-local.sh` does with `SPRING_APPLICATION_JSON`) | `e2e/run-local.sh` |
| Hosted PostgreSQL connection problems (Supabase) | Transaction pooler breaks prepared statements | Use the session pooler on 5432 with `sslmode=require` | `README.md` "Hosted PostgreSQL" |
| A test or feature behaves differently on PostgreSQL than on H2 | Unit tests run on H2 without RLS; tenant isolation exists only on PostgreSQL with the `modocument_app` role | Run the `*PgIT` suites (`-Ppostgres-it`) or `e2e/run-local.sh`, which uses the app role by default | `pom.xml` profile `postgres-it`; `e2e/run-local.sh` (`E2E_DB_APP_ROLE`) |

Who to ask for live-environment access, on-call and escalation: `TO CONFIRM (organisational)`.
