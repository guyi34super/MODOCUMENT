# Settings catalogue (ws20)

Every operational and security setting an administrator can change from the
dashboard. The catalogue — keys, types, scopes, tiers, hard limits and the
Spring property each one maps to — is code
(`infrastructure/settings/SettingDefinitions`); only changed values are
stored (`BankSetting`, V200). The table below is generated from that code
and pinned by `SettingsCatalogueDocTest`.

## How a value is resolved

For a bank (`SettingsReader#getInt(key, bankId)` and friends):

1. the bank's own override (`/api/admin/settings`), else
2. the platform owner's value (`/api/platform/settings`, stored as rows of
   the platform bank), else
3. the **shipped default**: the Spring property in the last column as
   configured (application.yml or its environment variable), or the code
   default when the setting has no property yet.

Stored values are always kept inside
`[max(hardMin, platform bankMin), min(hardMax, platform bankMax)]`; the
setting then shows `clamped: true`. Unparseable or no-longer-allowed stored
values are skipped with a WARN (the setting inherits). A PLATFORM-scope
setting has no bank layer: every bank gets the platform value or the
shipped default.

**Hard limits** are the go-live gate's floors and ceilings: no platform
value, bank bound or override can leave them. A **locked** setting has
exactly one allowed value. A shipped default outside its limits is a startup
WARN — or a refused boot when `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true`
(staging/production); without that flag the configured value stays in force
exactly (dev/test profiles switch e.g. retention off on purpose).

## Who changes what

| Tier | Bank layer (`/api/admin/settings`, `settings:write`) | Platform layer (`/api/platform/settings`, `platform:settings`) |
|---|---|---|
| OPERATIONAL | applies at once, audited `SETTING_UPDATED` | applies at once, audited `SETTING_UPDATED` |
| SECURITY | maker/checker `SETTING_UPDATE`, audited `SECURITY_SETTING_UPDATED` (alert `security-setting-changed`, MEDIUM) | maker/checker `PLATFORM_SETTING_UPDATE`, audited `PLATFORM_SETTING_UPDATED` (alert `platform-change`, HIGH) |
| bank bounds (`bankMin`/`bankMax`) | — | always maker/checker `PLATFORM_SETTING_UPDATE` |

`value: null` resets a setting to the inherited value. Every change carries
the `version` the caller read (`expectedVersion`); a stale one answers
409 `SETTING_VERSION_CONFLICT`, and an approved change whose rows moved in
the meantime is marked FAILED. Other refusals: 400 `SETTING_OUT_OF_RANGE`,
`SETTING_LOCKED`, `SETTING_UNKNOWN`; 409 `SETTING_CHANGE_PENDING` (an open
change already covers the key) and `USE_PLATFORM_SETTINGS` (a platform-bank
caller on the bank endpoint).

Changes reach every application task within one poll interval
(`modocument.settings.poll-interval-ms`, default 5 s): each write bumps the
`ConfigRevision` counter and every task reloads its in-memory snapshot when
the counter moves.

## Never in the UI

Secrets and infrastructure wiring stay in environment configuration: JWT and
MFA keys, database/Flyway credentials, the AI API key, base URL and model,
webhook/SIEM/OIDC secrets and URLs, CORS origins, the global SSRF allow-list,
the storage root, cron expressions and multipart limits.
`SettingsCatalogueSafetyTest` refuses any key or property that looks like
one. (Bank integration credentials are a separate, write-only case — see
the integration hub's `SecretVault`.)

## The catalogue

Limits are `hardMin – hardMax` for ranged settings and the allowed values
for switches and choices. Settings marked "consumer lands later" are
catalogued now with their code default; the code that reads them arrives
with its workstream.

<!-- BEGIN GENERATED: SettingsCatalogueDocTest -->
| Key | Tab | Scope | Tier | Type | Default | Limits | Locked | Restart | Property (shipped default) |
|---|---|---|---|---|---|---|---|---|---|
| `security.password.minLength` | Sign-in & passwords | BANK | SECURITY | INT | 12 characters | 12 – 128 |  |  | `modocument.security.password.min-length` |
| `security.lockout.allowedAttempts` | Sign-in & passwords | BANK | SECURITY | INT | 5 attempts | 3 – 10 |  |  | `modocument.security.lockout.allowed-attempts` |
| `security.lockout.baseMinutes` | Sign-in & passwords | BANK | SECURITY | INT | 1 minutes | 1 – 60 |  |  | `modocument.security.lockout.base-minutes` |
| `security.lockout.capMinutes` | Sign-in & passwords | BANK | SECURITY | INT | 30 minutes | 30 – 1440 |  |  | `modocument.security.lockout.cap-minutes` |
| `security.session.idleTimeout` | Sign-in & passwords | BANK | SECURITY | DURATION | 12h | 15m – 12h |  |  | `modocument.security.session.idle-timeout` |
| `security.mfa.requireForAllRoles` | Sign-in & passwords | BANK | SECURITY | BOOLEAN | false | true, false |  |  | — (code default; consumer lands later) |
| `security.mfa.requireForAdmins` | Sign-in & passwords | BANK | SECURITY | BOOLEAN | true | true | **locked** |  | `modocument.security.mfa.require-for-admin` |
| `security.jwt.ttlMinutes` | Sign-in & passwords | PLATFORM | SECURITY | INT | 15 minutes | 5 – 15 |  |  | `modocument.security.jwt.ttl-minutes` |
| `ratelimit.enabled` | Rate limits | BANK | SECURITY | BOOLEAN | true | true | **locked** |  | `modocument.ratelimit.enabled` |
| `ratelimit.window` | Rate limits | PLATFORM | SECURITY | DURATION | 1h | 1h – 1d |  | yes | `modocument.ratelimit.window-millis` |
| `ratelimit.uploads` | Rate limits | BANK | SECURITY | INT | 20 per user per window | 1 – 200 |  |  | `modocument.ratelimit.uploads` |
| `ratelimit.ingestion` | Rate limits | BANK | SECURITY | INT | 60 per user per window | 1 – 600 |  |  | `modocument.ratelimit.ingestion` |
| `ratelimit.transform` | Rate limits | BANK | SECURITY | INT | 30 per user per window | 1 – 300 |  |  | `modocument.ratelimit.transform` |
| `ratelimit.exports` | Rate limits | BANK | SECURITY | INT | 60 per user per window | 1 – 600 |  |  | `modocument.ratelimit.exports` |
| `ratelimit.docGenerate` | Rate limits | BANK | SECURITY | INT | 30 per user per window | 1 – 300 |  |  | `modocument.ratelimit.doc-generate` |
| `ratelimit.extraction` | Rate limits | BANK | SECURITY | INT | 30 per user per window | 1 – 300 |  |  | `modocument.ratelimit.extraction` |
| `ratelimit.dsr` | Rate limits | BANK | SECURITY | INT | 10 per user per window | 1 – 100 |  |  | `modocument.ratelimit.dsr` |
| `ratelimit.passwordChange` | Rate limits | BANK | SECURITY | INT | 5 per user per window | 1 – 10 |  |  | `modocument.ratelimit.password-change` |
| `ratelimit.mfaCode` | Rate limits | BANK | SECURITY | INT | 10 per user per window | 1 – 20 |  |  | `modocument.ratelimit.mfa-code` |
| `ratelimit.adminJobs` | Rate limits | PLATFORM | SECURITY | INT | 10 per user per window | 1 – 50 |  |  | `modocument.ratelimit.admin-jobs` |
| `ratelimit.llm.perUserMax` | Rate limits | BANK | SECURITY | INT | 60 per user per hour | 1 – 600 |  |  | `modocument.security.rate-limit.llm.per-user-max` |
| `ratelimit.auth.perIpMax` | Rate limits | PLATFORM | SECURITY | INT | 30 per 15 minutes | 1 – 120 |  |  | `modocument.security.rate-limit.auth.per-ip-max` |
| `ratelimit.auth.perEmailMax` | Rate limits | PLATFORM | SECURITY | INT | 10 per 15 minutes | 1 – 40 |  |  | `modocument.security.rate-limit.auth.per-email-max` |
| `ratelimit.mfa.perIpMax` | Rate limits | PLATFORM | SECURITY | INT | 5 per minute | 1 – 20 |  |  | `modocument.security.rate-limit.mfa.per-ip-max` |
| `ratelimit.mfa.perUserMax` | Rate limits | PLATFORM | SECURITY | INT | 5 per minute | 1 – 20 |  |  | `modocument.security.rate-limit.mfa.per-user-max` |
| `export.cap.readWrite` | Data protection | BANK | SECURITY | INT | 5000 rows | 0 – 50000 |  |  | `modocument.export.caps.READ_WRITE` |
| `export.cap.admin` | Data protection | BANK | SECURITY | INT | 50000 rows | 0 – 500000 |  |  | `modocument.export.caps.ADMIN` |
| `retention.enabled` | Data protection | BANK | SECURITY | BOOLEAN | true | true | **locked** |  | `modocument.retention.enabled` |
| `retention.customerDataDays` | Data protection | BANK | SECURITY | INT | 30 days | 1 – 30 |  |  | `modocument.retention.customer-data-days` |
| `privacy.masking.screenWorking` | Data protection | BANK | SECURITY | BOOLEAN | false | true, false |  |  | `modocument.privacy.masking.screen-working` |
| `privacy.tokenizePii` | Data protection | BANK | SECURITY | BOOLEAN | true | true | **locked** |  | `aifiles.privacy.tokenize-pii` |
| `ai.enabled` | Data protection | BANK | SECURITY | BOOLEAN | true | true, false |  |  | — (code default; consumer lands later) |
| `alerts.loginFailuresPerUser.threshold` | Security alerts | PLATFORM | SECURITY | INT | 5 events | 1 – 20 |  |  | `modocument.alerts.rules[login-failures-per-user].threshold` |
| `alerts.loginFailuresPerUser.window` | Security alerts | PLATFORM | SECURITY | DURATION | 5m | 1m – 1h |  |  | `modocument.alerts.rules[login-failures-per-user].window` |
| `alerts.loginFailuresPerIp.threshold` | Security alerts | PLATFORM | SECURITY | INT | 5 events | 1 – 20 |  |  | `modocument.alerts.rules[login-failures-per-ip].threshold` |
| `alerts.loginFailuresPerIp.window` | Security alerts | PLATFORM | SECURITY | DURATION | 5m | 1m – 1h |  |  | `modocument.alerts.rules[login-failures-per-ip].window` |
| `alerts.authorizationDeniedBurst.threshold` | Security alerts | PLATFORM | SECURITY | INT | 10 events | 1 – 40 |  |  | `modocument.alerts.rules[authorization-denied-burst].threshold` |
| `alerts.authorizationDeniedBurst.window` | Security alerts | PLATFORM | SECURITY | DURATION | 5m | 1m – 1h |  |  | `modocument.alerts.rules[authorization-denied-burst].window` |
| `alerts.adminActionBurst.threshold` | Security alerts | PLATFORM | SECURITY | INT | 30 events | 1 – 120 |  |  | `modocument.alerts.rules[admin-action-burst].threshold` |
| `alerts.adminActionBurst.window` | Security alerts | PLATFORM | SECURITY | DURATION | 10m | 1m – 1h |  |  | `modocument.alerts.rules[admin-action-burst].window` |
| `alerts.exportBurst.threshold` | Security alerts | PLATFORM | SECURITY | INT | 5 events | 1 – 20 |  |  | `modocument.alerts.rules[export-burst].threshold` |
| `alerts.exportBurst.window` | Security alerts | PLATFORM | SECURITY | DURATION | 10m | 1m – 1h |  |  | `modocument.alerts.rules[export-burst].window` |
| `alerts.webhook.minSeverity` | Security alerts | PLATFORM | SECURITY | ENUM | INFO | INFO, LOW, MEDIUM |  |  | `modocument.alerts.webhook.min-severity` |
| `alerts.email.minSeverity` | Security alerts | PLATFORM | SECURITY | ENUM | INFO | INFO, LOW, MEDIUM |  |  | `modocument.alerts.email.min-severity` |
| `extraction.defaultMode` | AI & extraction | BANK | OPERATIONAL | ENUM | AI_WITH_CODE_FALLBACK | AI_WITH_CODE_FALLBACK, CODE_ONLY, AI_ONLY |  |  | — (code default; consumer lands later) |
| `extraction.code.minConfidence` | AI & extraction | BANK | OPERATIONAL | DECIMAL | 0.75 | 0.5 – 0.95 |  |  | — (code default; consumer lands later) |
| `parsing.deterministicEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `modocument.parsing.deterministic-enabled` |
| `parsing.combinedExtractionMapping` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `modocument.parsing.combined-extraction-mapping` |
| `parsing.excel.llmEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | false | true, false |  |  | `modocument.parsing.excel.llm-enabled` |
| `mapping.llmFallbackEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `mapping.llm-fallback-enabled` |
| `mapping.confidenceThreshold` | AI & extraction | BANK | OPERATIONAL | DECIMAL | 0.7 | 0.5 – 0.95 |  |  | `mapping.confidence-threshold` |
| `chatEdit.fullTableMaxChangeRatio` | AI & extraction | BANK | OPERATIONAL | DECIMAL | 0.6 | 0.1 – 1 |  |  | `modocument.chat-edit.full-table-max-change-ratio` |
| `doccreation.sectionAiEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `modocument.doccreation.section-ai-enabled` |
| `doccreation.generateAiEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `modocument.doccreation.generate-ai-enabled` |
| `ai.circuitBreaker.failureThreshold` | AI & extraction | PLATFORM | OPERATIONAL | INT | 5 failures | 1 – 50 |  |  | — (code default; consumer lands later) |
| `ai.circuitBreaker.openDuration` | AI & extraction | PLATFORM | OPERATIONAL | DURATION | 2m | 10s – 30m |  |  | — (code default; consumer lands later) |
| `parsing.llmBatchSize` | AI & extraction | PLATFORM | OPERATIONAL | INT | 50 rows | 5 – 500 |  |  | `modocument.parsing.llm-batch-size` |
| `parsing.llmBatchConcurrency` | AI & extraction | PLATFORM | OPERATIONAL | INT | 8 batches | 1 – 32 |  |  | `modocument.parsing.llm-batch-concurrency` |
| `integration.maxRowsPerRun` | Integrations | BANK | OPERATIONAL | INT | 50000 rows | 1 – 500000 |  |  | — (code default; consumer lands later) |
| `integration.runTimeout` | Integrations | BANK | OPERATIONAL | DURATION | 15m | 1m – 2h |  |  | — (code default; consumer lands later) |
| `integration.minScheduleInterval` | Integrations | BANK | OPERATIONAL | DURATION | 5m | 5m – 1d |  |  | — (code default; consumer lands later) |
| `integration.webhookRetryAttempts` | Integrations | BANK | OPERATIONAL | INT | 5 attempts | 0 – 10 |  |  | — (code default; consumer lands later) |
| `integration.outboundPiiMasking` | Integrations | BANK | SECURITY | BOOLEAN | true | true, false |  |  | — (code default; consumer lands later) |
| `integration.clientTokenTtl` | Integrations | BANK | SECURITY | DURATION | 15m | 5m – 15m |  |  | — (code default; consumer lands later) |
| `integration.auth.allowOauth2` | Integrations | PLATFORM | SECURITY | BOOLEAN | true | true, false |  |  | — (code default; consumer lands later) |
| `integration.auth.allowApiKeys` | Integrations | PLATFORM | SECURITY | BOOLEAN | true | true, false |  |  | — (code default; consumer lands later) |
| `integration.auth.allowMtls` | Integrations | PLATFORM | SECURITY | BOOLEAN | true | true, false |  |  | — (code default; consumer lands later) |
| `integration.agentHeartbeatTimeout` | Integrations | BANK | OPERATIONAL | DURATION | 2m | 1m – 1h |  |  | — (code default; consumer lands later) |
<!-- END GENERATED -->
