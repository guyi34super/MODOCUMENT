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

A **platform-set** BANK setting (scope `BANK (platform-set)` below,
`platformControlled` in the API) resolves exactly the same way, but its
bank value (step 1) is written by the **platform owner** for that bank
(`/api/platform/banks/{bankId}/settings`), never by the bank: the bank's
administrators see it read-only (`SETTING_LOCKED` on a bank PUT). With no
per-bank value every bank inherits the platform value, so a deployment that
never sets one behaves exactly as if the setting were platform-wide. Today
this covers the allowed API-client authentication methods
(`integration.auth.allow*`).

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

A platform-set setting's **per-bank value** is the platform layer's too:
`GET /api/platform/banks/{bankId}/settings` (`platform:read`) shows one
customer bank's platform-set settings as that bank sees them, and
`PUT` (`platform:settings`) changes them under the same tier rules; a
security value is one `PLATFORM_SETTING_UPDATE` whose payload names the
bank (`{bankId, changes:[{key, from, to, baseVersion}]}`), approved by a
second platform admin and written into that bank's own rows. Unknown bank:
404; the platform bank: 409 `USE_PLATFORM_SETTINGS`. In the console it is
the bank's page → **Settings set by the platform**.

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

## Who reads what

Every consumer reads its setting **per call** from the in-memory snapshot
(`SettingsReader`, no database access), so a change applies on the next
request — no restart. The yml/`@Value` beans stay as they were: they are the
shipped default the resolution falls back to. **Parity:** with nothing
stored, every consumer behaves exactly as before the settings framework
(`SettingsConsumersParityTest` enumerates each value).

Which bank a value is read for:

| Consumer | Settings | Bank |
|---|---|---|
| `PasswordPolicy` | `security.password.minLength` | the account's bank (`validate(password, email, bankId)`: account creation, own password change); the caller's bank for `validate(password, email)` (maker submissions, resets); the platform layer when no bank is bound (bootstrap, onboarding before the bank exists) |
| `LoginLockoutPolicy` | `security.lockout.*` | the user's bank, on every failed sign-in |
| `AuthUseCase` | `security.mfa.requireForAllRoles`, `security.mfa.requireForAdmins` (locked on), `security.session.idleTimeout` | the user's bank; for refresh, the token owner's bank. A PLATFORM_ADMIN always owes MFA |
| `JwtService` | `security.jwt.ttlMinutes` | platform (an explicit legacy `ttl-hours` still pins it) |
| `ApiRateLimitInterceptor` | `ratelimit.<bucket>` incl. `ratelimit.adminSecurity` | the caller's bank (JWT bank in `TenantContext`), per request; `ratelimit.adminJobs` is platform-wide |
| `LlmRateLimiter` | `ratelimit.llm.perUserMax` | the thread's bank (`check(userId)` / `tryCheck(userId)`) or an explicit one (`check(userId, bankId)` / `tryCheck(userId, bankId)`) |
| `AuthRateLimiter`, `MfaRateLimiter` | `ratelimit.auth.*`, `ratelimit.mfa.*` | platform (the caller is anonymous) |
| `ExportGuard` | `export.cap.readWrite`, `export.cap.admin` | the caller's bank; other roles keep their yml cap (none = 0) |
| `RetentionPurgeJob` / `RetentionProperties` | `retention.customerDataDays` | each bank's own cutoff inside the per-bank sweep (1–30 days: a bank can only shorten retention) |
| `AlertRuleCatalogue` | `alerts.<rule>.threshold` / `.window` | platform; a STORED value overlays the yml rule, rebuilt on `SettingsChangedEvent` |
| `WebhookAlertChannel`, `EmailAlertChannel` | `alerts.webhook.minSeverity`, `alerts.email.minSeverity` | platform |
| `MaskingPolicy` | `privacy.masking.screenWorking` | the caller's bank |
| `ChatEditTableUseCase` | `chatEdit.fullTableMaxChangeRatio` | the caller's bank |
| `AiSectionSplitter`, `AiDocumentComposer` | `doccreation.sectionAiEnabled`, `doccreation.generateAiEnabled`, AND `ai.enabled` | the caller's bank |
| `ParsingSettings.forBank(id)` | `parsing.*` (batch size/concurrency platform-wide) | fresh `ParsingProperties` copy per job; `excelForBank(id)` for the Excel switch |
| `MappingSettings.forBank(id)` | `mapping.llmFallbackEnabled`, `mapping.confidenceThreshold` | fresh `MappingProperties` copy; `AutoMapUseCase` resolves once per mapping run |
| `IntegrationSettings` (flow engine: `FlowRunner`, `IntegrationScheduler`, `OutboundDispatcher`, `PayloadBuilder`) | `integration.maxRowsPerRun`, `integration.runTimeout`, `integration.webhookRetryAttempts` | the flow's (or delivery's) bank, per run / per delivery attempt |
| `IntegrationSettings` (client auth: `ClientTokenService`, `ApiClientAuthenticator`, and through it `ApiClientProvisioning`) | `integration.clientTokenTtl`, `integration.auth.allow*` | the API client's bank, on every token issue and every authenticated call (a method switched off refuses existing clients at once) |
| `IntegrationFlowUseCase`, `IntegrationAgentUseCase` | `integration.minScheduleInterval`, `integration.outboundPiiMasking`, `integration.agentHeartbeatTimeout` | the caller's bank (application layer: `SettingsReader` directly) |

Stay yml-only on purpose: the locked switches (`ratelimit.enabled`,
`retention.enabled`, `privacy.tokenizePii`), the restart-only rate-limit
window, every limiter's key bound, and `parsing.llmSinglePassMaxRows`.
Settings without a property (extraction mode and code confidence, the AI
circuit breaker, the integration tab) are read by their own workstreams.

## The tabs

The settings screens show one tab per group, in this order: **Sign-in &
passwords**, **Rate limits**, **Data protection**, **Security alerts**
(platform only), **AI & extraction** and **Integrations**. A bank
administrator sees the BANK-scope settings of each tab; the platform
console shows every setting with its bank bounds.

**Integrations** (ws25/ws26) holds the integration hub's guardrails:

- maximum rows per run, run timeout, minimum schedule interval, webhook
  delivery attempts and the agent-offline timeout (operational: apply at
  once);
- masking PII in new outbound flows and the API-client token lifetime
  (security: maker/checker);
- which API-client authentication methods the bank may use — OAuth2 client
  credentials, API keys, mutual TLS. These are **platform-set**: the
  platform owner chooses the default for every bank on the platform
  settings screen and, where needed, a bank's own value on that bank's
  page; the bank sees them read-only. Switching a method off refuses new
  clients of that method and stops existing ones authenticating.

Connection credentials are not settings: they are entered write-only on the
connection and sealed by the `SecretVault` (docs/integration/secrets.md).

**Change notification.** Each task publishes `SettingsChangedEvent` after it
loads a new snapshot — after a settings write commits on that task, and
when its `ConfigRevisionPoller` sees another task's write. Consumers that
pre-compute (the alert catalogue) rebuild on it; all others just read the
next value.

### MFA verify per address (QA finding OBS-04)

`ratelimit.mfa.perIpMax` ships at **30 per minute** (was 5; limits 1–120);
`ratelimit.mfa.perUserMax` stays **5 per minute** (limits 1–20). A bank's
staff sign in from behind one NAT address, and at 5/min colleagues finishing
their sign-in in the same minute locked each other out. The per-address
limit is only a flood guard: every verify attempt needs a challenge, which
needs the account's password, and the per-user limit bounds the guesses
against any one account (at most 25 over a 5-minute challenge, about
7.5 × 10⁻⁵ success odds with the ±1-step window) however many addresses an
attacker uses. Keep the per-user limit strict; raise the per-address limit
further only for very large NATs. Tests: `MfaRateLimiterTest`.

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
for switches and choices. Settings without a property start from their code
default (see "Who reads what" for the consumer). Every WINDOWED alert rule of
application.yml has exactly one threshold and one window setting
(`SettingsCatalogueSafetyTest` keeps the two in sync).

<!-- BEGIN GENERATED: SettingsCatalogueDocTest -->
| Key | Tab | Scope | Tier | Type | Default | Limits | Locked | Restart | Property (shipped default) |
|---|---|---|---|---|---|---|---|---|---|
| `security.password.minLength` | Sign-in & passwords | BANK | SECURITY | INT | 12 characters | 12 – 128 |  |  | `modocument.security.password.min-length` |
| `security.lockout.allowedAttempts` | Sign-in & passwords | BANK | SECURITY | INT | 5 attempts | 3 – 10 |  |  | `modocument.security.lockout.allowed-attempts` |
| `security.lockout.baseMinutes` | Sign-in & passwords | BANK | SECURITY | INT | 1 minutes | 1 – 60 |  |  | `modocument.security.lockout.base-minutes` |
| `security.lockout.capMinutes` | Sign-in & passwords | BANK | SECURITY | INT | 30 minutes | 30 – 1440 |  |  | `modocument.security.lockout.cap-minutes` |
| `security.session.idleTimeout` | Sign-in & passwords | BANK | SECURITY | DURATION | 12h | 15m – 12h |  |  | `modocument.security.session.idle-timeout` |
| `security.mfa.requireForAllRoles` | Sign-in & passwords | BANK | SECURITY | BOOLEAN | false | true, false |  |  | — (code default) |
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
| `ratelimit.adminSecurity` | Rate limits | BANK | SECURITY | INT | 10 per user per window | 1 – 100 |  |  | `modocument.ratelimit.admin-security` |
| `ratelimit.llm.perUserMax` | Rate limits | BANK | SECURITY | INT | 60 per user per hour | 1 – 600 |  |  | `modocument.security.rate-limit.llm.per-user-max` |
| `ratelimit.auth.perIpMax` | Rate limits | PLATFORM | SECURITY | INT | 30 per 15 minutes | 1 – 120 |  |  | `modocument.security.rate-limit.auth.per-ip-max` |
| `ratelimit.auth.perEmailMax` | Rate limits | PLATFORM | SECURITY | INT | 10 per 15 minutes | 1 – 40 |  |  | `modocument.security.rate-limit.auth.per-email-max` |
| `ratelimit.mfa.perIpMax` | Rate limits | PLATFORM | SECURITY | INT | 30 per minute | 1 – 120 |  |  | `modocument.security.rate-limit.mfa.per-ip-max` |
| `ratelimit.mfa.perUserMax` | Rate limits | PLATFORM | SECURITY | INT | 5 per minute | 1 – 20 |  |  | `modocument.security.rate-limit.mfa.per-user-max` |
| `export.cap.readWrite` | Data protection | BANK | SECURITY | INT | 5000 rows | 0 – 50000 |  |  | `modocument.export.caps.READ_WRITE` |
| `export.cap.admin` | Data protection | BANK | SECURITY | INT | 50000 rows | 0 – 500000 |  |  | `modocument.export.caps.ADMIN` |
| `retention.enabled` | Data protection | BANK | SECURITY | BOOLEAN | true | true | **locked** |  | `modocument.retention.enabled` |
| `retention.customerDataDays` | Data protection | BANK | SECURITY | INT | 30 days | 1 – 30 |  |  | `modocument.retention.customer-data-days` |
| `privacy.masking.screenWorking` | Data protection | BANK | SECURITY | BOOLEAN | false | true, false |  |  | `modocument.privacy.masking.screen-working` |
| `privacy.tokenizePii` | Data protection | BANK | SECURITY | BOOLEAN | true | true | **locked** |  | `aifiles.privacy.tokenize-pii` |
| `ai.enabled` | Data protection | BANK | SECURITY | BOOLEAN | true | true, false |  |  | — (code default) |
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
| `alerts.parseFallbackSurge.threshold` | Security alerts | PLATFORM | SECURITY | INT | 20 events | 1 – 80 |  |  | `modocument.alerts.rules[parse-fallback-surge].threshold` |
| `alerts.parseFallbackSurge.window` | Security alerts | PLATFORM | SECURITY | DURATION | 1h | 1m – 1h |  |  | `modocument.alerts.rules[parse-fallback-surge].window` |
| `alerts.apiClientAuthFailures.threshold` | Security alerts | PLATFORM | SECURITY | INT | 10 events | 1 – 40 |  |  | `modocument.alerts.rules[api-client-auth-failures].threshold` |
| `alerts.apiClientAuthFailures.window` | Security alerts | PLATFORM | SECURITY | DURATION | 5m | 1m – 1h |  |  | `modocument.alerts.rules[api-client-auth-failures].window` |
| `alerts.integrationFailures.threshold` | Security alerts | PLATFORM | SECURITY | INT | 5 events | 1 – 20 |  |  | `modocument.alerts.rules[integration-failures].threshold` |
| `alerts.integrationFailures.window` | Security alerts | PLATFORM | SECURITY | DURATION | 1h | 1m – 1h |  |  | `modocument.alerts.rules[integration-failures].window` |
| `alerts.webhook.minSeverity` | Security alerts | PLATFORM | SECURITY | ENUM | INFO | INFO, LOW, MEDIUM |  |  | `modocument.alerts.webhook.min-severity` |
| `alerts.email.minSeverity` | Security alerts | PLATFORM | SECURITY | ENUM | INFO | INFO, LOW, MEDIUM |  |  | `modocument.alerts.email.min-severity` |
| `extraction.defaultMode` | AI & extraction | BANK | OPERATIONAL | ENUM | AI_WITH_CODE_FALLBACK | AI_WITH_CODE_FALLBACK, CODE_ONLY, AI_ONLY |  |  | — (code default) |
| `extraction.code.minConfidence` | AI & extraction | BANK | OPERATIONAL | DECIMAL | 0.75 | 0.5 – 0.95 |  |  | — (code default) |
| `parsing.deterministicEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `modocument.parsing.deterministic-enabled` |
| `parsing.combinedExtractionMapping` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `modocument.parsing.combined-extraction-mapping` |
| `parsing.excel.llmEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | false | true, false |  |  | `modocument.parsing.excel.llm-enabled` |
| `mapping.llmFallbackEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `mapping.llm-fallback-enabled` |
| `mapping.confidenceThreshold` | AI & extraction | BANK | OPERATIONAL | DECIMAL | 0.7 | 0.5 – 0.95 |  |  | `mapping.confidence-threshold` |
| `chatEdit.fullTableMaxChangeRatio` | AI & extraction | BANK | OPERATIONAL | DECIMAL | 0.6 | 0.1 – 1 |  |  | `modocument.chat-edit.full-table-max-change-ratio` |
| `doccreation.sectionAiEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `modocument.doccreation.section-ai-enabled` |
| `doccreation.generateAiEnabled` | AI & extraction | BANK | OPERATIONAL | BOOLEAN | true | true, false |  |  | `modocument.doccreation.generate-ai-enabled` |
| `ai.circuitBreaker.failureThreshold` | AI & extraction | PLATFORM | OPERATIONAL | INT | 5 failures | 1 – 50 |  |  | — (code default) |
| `ai.circuitBreaker.openDuration` | AI & extraction | PLATFORM | OPERATIONAL | DURATION | 2m | 10s – 30m |  |  | — (code default) |
| `parsing.llmBatchSize` | AI & extraction | PLATFORM | OPERATIONAL | INT | 50 rows | 5 – 500 |  |  | `modocument.parsing.llm-batch-size` |
| `parsing.llmBatchConcurrency` | AI & extraction | PLATFORM | OPERATIONAL | INT | 8 batches | 1 – 32 |  |  | `modocument.parsing.llm-batch-concurrency` |
| `integration.maxRowsPerRun` | Integrations | BANK | OPERATIONAL | INT | 50000 rows | 1 – 500000 |  |  | — (code default) |
| `integration.runTimeout` | Integrations | BANK | OPERATIONAL | DURATION | 15m | 1m – 2h |  |  | — (code default) |
| `integration.minScheduleInterval` | Integrations | BANK | OPERATIONAL | DURATION | 5m | 5m – 1d |  |  | — (code default) |
| `integration.webhookRetryAttempts` | Integrations | BANK | OPERATIONAL | INT | 5 attempts | 0 – 10 |  |  | — (code default) |
| `integration.outboundPiiMasking` | Integrations | BANK | SECURITY | BOOLEAN | true | true, false |  |  | — (code default) |
| `integration.clientTokenTtl` | Integrations | BANK | SECURITY | DURATION | 15m | 5m – 15m |  |  | — (code default) |
| `integration.auth.allowOauth2` | Integrations | BANK (platform-set) | SECURITY | BOOLEAN | true | true, false |  |  | — (code default) |
| `integration.auth.allowApiKeys` | Integrations | BANK (platform-set) | SECURITY | BOOLEAN | true | true, false |  |  | — (code default) |
| `integration.auth.allowMtls` | Integrations | BANK (platform-set) | SECURITY | BOOLEAN | true | true, false |  |  | — (code default) |
| `integration.agentHeartbeatTimeout` | Integrations | BANK | OPERATIONAL | DURATION | 2m | 1m – 1h |  |  | — (code default) |
<!-- END GENERATED -->
