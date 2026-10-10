# Security alerting and export caps (ws18)

Gate items **D1** (security alerts for failed logins, admin/root use and
large exports reach a person in under 5 minutes) and **D2** (a 10,000-row
export by a normal user is blocked and alerted within 5 minutes); controls
**SEC-032** (configurable thresholds, < 5 min), **PRIV-002** (export
limits with justification) and the **SUP-S11** synthetic probe. The SIEM side
(SEC-031, gate E6) is documented in [siem-event-schema.md](siem-event-schema.md).

## Design: the audit trail is the single event source

Every security-relevant action already lands in the append-only `AuditEvent`
table (V14) through the `AuditRecorder` port. Detection does not add a second
event pipeline — it reads that trail:

```
business code ──AuditRecorder──▶ AuditEvent row (commit)
                                     │
        ┌────────────────────────────┼────────────────────────────┐
        ▼ post-commit event           ▼ minute sweep (SQL)          ▼ pull / push
 IMMEDIATE rules                 WINDOWED rules                  SIEM feed
 (ImmediateAlertListener)        (AlertSweepJob)                 (siem-event-schema.md)
        └──────────────┬──────────────┘
                       ▼
          AlertService: dedupe → AlertLog row → channels (log / webhook / e-mail)
                       │
                       └─▶ ALERT_SENT / ALERT_DELIVERY_FAILED audit rows
```

- **Immediate** — `AuditEventWriter` publishes an `AuditRecordedEvent` inside
  the writing transaction; `ImmediateAlertListener` is an
  `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`, so
  only durable rows alert. DENIED/FAILED rows commit in their own
  transaction, so a refusal (`EXPORT_BLOCKED`, `ACCOUNT_LOCKED`) alerts even
  though the request then fails. The listener only matches rules in memory
  and hands off to the alert executor: the business thread is never slowed
  down or rolled back by alerting. Typical latency: well under a second.
- **Windowed** — `AlertSweepJob` runs every minute
  (`modocument.alerts.sweep.interval-ms`), counts each windowed rule's
  matching audit rows inside the rule window grouped by bank + actor/ip/
  entity, and raises an alert for every group at or above the threshold.
  Worst-case latency: one sweep interval (60 s) plus delivery.
- **Dedupe** — one alert per rule / bank / group per rule window. An
  in-memory map plus a persisted check on `AlertLog.DedupeKey` (SHA-256 of
  `rule|bank|group` — the raw key, possibly an email or ip, is never stored),
  so a sustained burst raises one alert per window, across restarts too.
- **No loops** — rows whose action starts with `ALERT_` never match a rule.

## Rule catalogue (SEC-032: configuration, not code)

`modocument.alerts.rules` in `application.yml` binds a list of rules
(`AlertProperties.Rule`), validated fail-fast at startup by
`AlertRuleCatalogue` (a broken rule stops the deployment rather than leaving
a silent detection gap) and logged one INFO line per rule. Replacing the
list replaces the catalogue; the env variables below tune the defaults.
`AlertRuleCatalogueTest` pins the shipped defaults.

Rule fields: `name`, `description`, `trigger` (IMMEDIATE | WINDOWED),
`actions` (`"*"` = any), optional `outcomes` / `actor-roles` filters,
`group-by` (ACTOR | IP | ENTITY | BANK), `threshold` (windowed), `window`
(aggregation + dedupe window, 1m–24h), `severity` (INFO < LOW < MEDIUM <
HIGH < CRITICAL), `enabled`.

| Rule | Trigger | Audit actions | Group | Threshold / window | Severity | Env override |
|---|---|---|---|---|---|---|
| `account-locked` | immediate | `ACCOUNT_LOCKED` | actor | – / 5m | HIGH | |
| `refresh-token-reuse` | immediate | `TOKEN_REFRESH_REUSE_DETECTED` | actor | – / 5m | CRITICAL | |
| `export-blocked` | immediate | `EXPORT_BLOCKED` | actor | – / 5m | HIGH | |
| `export-over-cap` | immediate | `EXPORT_OVER_CAP_WITH_REASON` | entity (job) | – / 5m | INFO | |
| `sod-violation` | immediate | `SOD_VIOLATION_FOUND` | entity | – / 5m | CRITICAL | |
| `sod-attempt-blocked` | immediate | `SELF_APPROVAL_BLOCKED`, `DOC_SELF_RELEASE_BLOCKED` | actor | – / 15m | MEDIUM | |
| `ai-prompt-refused` | immediate | `AI_PROMPT_REFUSED` | actor | – / 5m | HIGH | |
| `ai-output-blocked` | immediate | `AI_OUTPUT_BLOCKED` | actor | – / 15m | MEDIUM | |
| `privileged-change` | immediate | `BOOTSTRAP_ADMIN_CREATED`, `USER_ROLE_CHANGED`, `MFA_DISABLED`, `USER_LIST_EXPORTED`, `DSR_EXPORTED`, `DSR_ERASED`, `USER_PASSWORD_RESET`, `USER_MFA_RESET` | actor | – / 5m | MEDIUM | |
| `admin-login` | immediate | `LOGIN_SUCCESS` by role ADMIN | actor | – / 60m | LOW | `MODOCUMENT_ALERT_ADMIN_LOGIN_WINDOW` |
| `login-failures-per-user` | windowed | `LOGIN_FAILED` | actor | ≥ 5 / 5m | HIGH | `MODOCUMENT_ALERT_LOGIN_FAILURES_PER_USER`, `..._WINDOW` |
| `login-failures-per-ip` | windowed | `LOGIN_FAILED` | ip | ≥ 5 / 5m | HIGH | `MODOCUMENT_ALERT_LOGIN_FAILURES_PER_IP`, `..._WINDOW` |
| `authorization-denied-burst` | windowed | `AUTHORIZATION_DENIED` | actor | ≥ 10 / 5m | MEDIUM | `MODOCUMENT_ALERT_AUTHZ_DENIED_THRESHOLD` |
| `admin-action-burst` | windowed | any SUCCESS by role ADMIN | actor | ≥ 30 / 10m | MEDIUM | `MODOCUMENT_ALERT_ADMIN_BURST_THRESHOLD` |
| `export-burst` | windowed | `EXPORT_RUN` | actor | ≥ 5 / 10m | MEDIUM | `MODOCUMENT_ALERT_EXPORT_BURST_THRESHOLD` |
| `security-setting-changed` (ws20) | immediate | `SECURITY_SETTING_UPDATED` | actor (the approver) | – / 5m | MEDIUM | |
| `platform-change` (ws20/ws21) | immediate | `PLATFORM_SETTING_UPDATED`, `PLATFORM_BOOTSTRAPPED`, `BANK_ONBOARDED`, `BANK_SUSPENDED`, `BANK_REACTIVATED`, `BANK_ADMIN_ADDED`, `EGRESS_RULE_APPROVED` (wave 3) | entity (bank lifecycle rows are audited in the customer bank and the platform bank: one alert each) | – / 5m | HIGH | |
| `parse-fallback-surge` (ws23) | windowed | `INGESTION_PARSE_FALLBACK` | bank | ≥ 20 / 1h | MEDIUM | `MODOCUMENT_ALERT_PARSE_FALLBACK_SURGE_THRESHOLD` |
| `ai-circuit-opened` (ws23) | immediate | `AI_CIRCUIT_OPENED` | bank | – / 15m | HIGH | |

**Platform tuning (ws20).** Every windowed rule's threshold and window is
also a platform setting (`alerts.<rule>.threshold` / `.window`, Security
alerts tab, maker/checker): a stored value overlays the yml one on every
task within one settings poll, without a restart. Thresholds range from 1
to 4x the shipped default, windows from 1 to 60 minutes; the channels'
minimum severities (`alerts.webhook.minSeverity`, `alerts.email.minSeverity`)
are platform settings too.

How the D1 signals map: *failed logins* → `login-failures-per-user/-ip`
(+ `account-locked` on the 6th wrong password); *admin/root use* →
`admin-login` (every privileged sign-in, once per admin per hour),
`privileged-change` (bootstrap/root creation, role changes, MFA removal,
bulk personal-data exports) and `admin-action-burst`; *large exports* →
`export-blocked`, `export-over-cap` and `export-burst` (splitting a large
export into many capped ones).

Note: two rules can fire for one burst (five failures by one user from one
address raise both the per-user and the per-ip alert) — they are different
signals (an account under attack vs. a source attacking).

## Channels: reaching a person

`AlertChannel` beans; every alert goes to each enabled channel whose
`min-severity` it meets (default `INFO` everywhere: the curated catalogue is
low-noise, and even an informational large export must reach a person).
Delivery runs on a private, bounded executor wrapped with
`TenantTaskDecorator` (the triggering request id follows the alert into its
audit rows); a saturated executor logs the alert instead of blocking.

| Channel | Config | Notes |
|---|---|---|
| `log` | always on | WARN on logger `modocument.security.alert`, marker `SECURITY_ALERT` (ECS `tags`), fields `alert.id/rule/severity/bank_id/audit_event_id/event_count`. With the `structured-logs` profile the SIEM receives every alert even without any other channel. Redacted like every log line. |
| `webhook` | `MODOCUMENT_ALERTS_WEBHOOK_URL`, optional `MODOCUMENT_ALERTS_WEBHOOK_SECRET`, `..._MIN_SEVERITY` | **The phone path.** Generic JSON POST `{"text": "...", "alert": {id, rule, severity, bankId, summary, auditEventId, eventCount, createdAt, source}}` — `text` is what Slack/Teams incoming webhooks display; point it at a WhatsApp/SMS bridge, PagerDuty/Opsgenie or an on-call relay for phone notification. With a secret, `X-MoDocument-Signature: sha256=<HMAC-SHA256(secret, body)>`. Timeouts 3 s connect / 5 s read. |
| `email` | `MODOCUMENT_ALERTS_EMAIL_TO` (comma list) **and** `SPRING_MAIL_HOST` (+ port/user/password), `MODOCUMENT_ALERTS_EMAIL_FROM` | Plain-text mail (e-mail-to-SMS gateways are another phone path). `spring-boot-starter-mail` is on the classpath but inert: Boot creates the mail sender only when `spring.mail.host` is set, and the channel is a no-op without one. The mail health indicator is disabled so an SMTP outage never marks the app DOWN. |

**Why the webhook URLs bypass `OutboundHostPolicy`.** That policy is the SSRF
guard for *user-supplied* URLs (extraction sources, crawled links): it
refuses loopback/private/link-local targets so a user cannot steer the server
into the internal network. The alert and SIEM webhook URLs are *operator
configuration* (environment/YAML, the same trust level as the database URL);
no request parameter or stored user data can change them, and their
legitimate targets are often internal (an on-prem pager relay, a SIEM
collector on a private network) — exactly what the SSRF policy would block.
Plain HTTP to a non-loopback host is allowed but logged as a warning.

### Delivery evidence and retries

- `AlertLog` (V160) — one row per alert: rule, severity, bank, triggering
  `AuditEventId`, PII-scrubbed `Summary` (via `PiiPatterns`; never audit
  value payloads or the free-text export reason), `EventCount`,
  `CreatedAt` → `DeliveredAt`, `Channel` (channels that accepted it) and
  `Status` (PENDING → SENT, or FAILED). RLS-covered (BankId), listed in
  `RlsPolicyCoveragePgIT`.
- Each channel acceptance is audited `ALERT_SENT` (with `latencyMs`), each
  first failure `ALERT_DELIVERY_FAILED`. A PENDING row is retried by the
  minute sweep (only the channels still owed) until
  `modocument.alerts.delivery.retry-window` (default 30m), then marked
  FAILED with a final `ALERT_DELIVERY_FAILED`.
- **The < 5 minutes evidence:** detection = `AlertLog.CreatedAt −
  AuditEvent.OccurredAt` (immediate: sub-second; windowed: ≤ one sweep
  interval); notification = `DeliveredAt − CreatedAt` (seconds; shown in the
  admin panel as "in Ns").
- Admin view: `GET /api/admin/alerts?limit=50` (`user:admin`, the caller's
  bank only) and the **Administration → Security alerts** page.

## Export caps with a stated reason (D2, PRIV-002)

`modocument.export.caps` — per-role row caps (`MODOCUMENT_EXPORT_CAP_READ_WRITE`
default **5,000**, `MODOCUMENT_EXPORT_CAP_ADMIN` default **50,000**; a role
without an entry gets 0 — fail closed). Applied by `ExportGuard` to **both**
export paths (file download and database push) after the rows are counted
and **before** anything is produced:

| Rows vs. cap | Reason | Result | Audit | Alert |
|---|---|---|---|---|
| ≤ cap | – | exported | `EXPORT_RUN` (format/target, rowCount, cap) | (`export-burst` if repeated) |
| > cap | none | **403** `{"code":"EXPORT_CAP_EXCEEDED","cap":5000,"rows":10000,"reasonMaxLength":500,"error":"..."}` | `EXPORT_BLOCKED` (DENIED: rows, cap, role) | `export-blocked` (HIGH, immediate) |
| > cap | ≤ 500 chars | exported | `EXPORT_OVER_CAP_WITH_REASON` (reason verbatim) + `EXPORT_RUN` | `export-over-cap` (INFO, immediate) |
| any | > 500 chars | **400** (never truncated) | – | – |

- The reason travels as the `reason` query parameter on
  `GET /api/ingestion/jobs/{id}/export/file`, in the body of
  `POST /api/ingestion/jobs/{id}/export/file` (`{"format","reason"}` — the
  SPA uses this so free text never sits in a URL) or as `reason` in the
  database-export body. Control characters are stripped; it is stored in the
  audit trail only, never in an alert summary.
- The export log is the audit trail: `EXPORT_RUN` rows (who = the audit
  actor, format/target, row count) are append-only like every audit row and
  reach the SIEM feed.
- SPA: on a 403 `EXPORT_CAP_EXCEEDED` the Export step shows "This export
  needs a reason", asks for it (≤ 500 chars, "do not include personal data")
  and retries the same file/database export with it.

## Synthetic probe (SUP-S11)

`GET /api/probe/synthetic` — public (external uptime monitors hold no
credentials), GET only, rate-limited per client ip
(`MODOCUMENT_PROBE_RATE_LIMIT`, default 60/min → 429 with `Retry-After`).
Answers `{"status":"UP","version":"1.0.0","db":true,"checkedAt":"..."}` (503
with `DOWN`/`false` when the database round trip fails) — nothing else: no
exception text, host names or pool details. Unlike `/actuator/health` it
travels the full path (proxy, security chain, MVC, a real `SELECT 1` through
the application pool). The version comes from Boot's build-info.

## Tenancy

Registered in the `TenantContext` call-site registry: the windowed sweep
reads `AuditEvent` across banks as SYSTEM (a burst is detected wherever it
lands); every AlertLog write and `ALERT_*` audit row runs under
`runAs(alert bank)`, so an alert is visible only to its own bank. The SIEM
push forwarder's bank-code lookup is the only other SYSTEM read.

## Operational limits

- Multi-instance deployments: each instance runs the sweep; the persisted
  dedupe makes duplicate alerts unlikely but not impossible (check-then-insert
  is per instance). The SIEM push queue is per instance and in memory.
- The alert executor queue is bounded (`delivery.queue-capacity`, 1000); on
  saturation the immediate alert is logged (SECURITY_ALERT marker) instead
  of persisted.
- Test profile: the scheduled sweep is off (`modocument.alerts.sweep.enabled:
  false`); immediate alerting stays on. Gate tests: `AlertingGateD1Test`,
  `ExportCapGateD2Test`, `SiemFeedE6Test`, `SyntheticProbeTest`.
