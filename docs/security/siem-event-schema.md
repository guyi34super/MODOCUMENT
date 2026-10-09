# SIEM event schema and export (SEC-031, gate E6)

MoDocument exports its security events to a bank's SIEM in near real time.
There is **one event source**: the append-only audit trail (`AuditEvent`,
V14 — every login, refusal, change, export, alert delivery, DSR and retention
run lands there first). The SIEM feed is a rendering of that table, the alert
framework reads the same table, and neither has a second pipeline that could
drift from it.

- Serializer: `infrastructure/siem/SiemEventMapper` (the only one — both
  transports below use it).
- Pinned: `SiemFeedE6Test#schemaDocFieldListMatchesTheSerializedOutput`
  asserts that the **Field reference** table below lists exactly the fields
  the serializer emits for a fully populated event. A field cannot be added,
  renamed or dropped without this document changing in the same commit.

## Event document

One JSON object per audit row, Elastic Common Schema (ECS 8.11) names where
ECS has a home for the value and a `modocument.*` namespace for the rest.
Absent values are **omitted** (ECS convention), never `null` — e.g. an
anonymous failed login has no `user.id`, a system job has no `source.ip`.

```json
{
  "@timestamp": "2026-10-09T10:15:30.123Z",
  "ecs": { "version": "8.11" },
  "event": {
    "id": "0192f1c4-7a3e-7c11-9b2e-5d0c6a1f4e88",
    "action": "EXPORT_BLOCKED",
    "outcome": "failure",
    "kind": "event",
    "module": "modocument",
    "dataset": "modocument.audit"
  },
  "organization": { "id": "3" },
  "user": { "id": "42", "email": "jane@bank.example", "roles": ["READ_WRITE"] },
  "source": { "ip": "203.0.113.9" },
  "trace": { "id": "b7c1e0d2-..." },
  "modocument": {
    "outcome": "DENIED",
    "session_jti": "0192f1c4-...",
    "entity": { "type": "IngestionJob", "id": "17" },
    "old_values": null,
    "new_values": { "jobId": 17, "type": "file", "format": "CSV", "rows": 10000, "cap": 5000, "role": "READ_WRITE" }
  }
}
```

(`old_values` is shown as `null` only for illustration — in a real document an
absent snapshot is simply not there.)

## Field reference

| Field | Type | Source (AuditEvent column) | Notes |
|---|---|---|---|
| `@timestamp` | date (ISO-8601, UTC) | `OccurredAt` | When the action happened (recorded app-side). |
| `ecs.version` | keyword | constant | `8.11`. |
| `event.id` | keyword (UUIDv7) | `AuditEventId` | Unique, time-ordered; **the pull-feed cursor**. Dedupe key for at-least-once push. |
| `event.action` | keyword | `Action` | The audit action, e.g. `LOGIN_FAILED`, `ACCOUNT_LOCKED`, `EXPORT_BLOCKED`, `ALERT_SENT`. |
| `event.outcome` | keyword | `Outcome` | ECS mapping: `SUCCESS` → `success`; `DENIED`, `FAILED` → `failure`. |
| `event.kind` | keyword | constant | `event`. |
| `event.module` | keyword | constant | `modocument`. |
| `event.dataset` | keyword | constant | `modocument.audit` — route on this. |
| `organization.id` | keyword | `BankId` | The tenant. A bank's feed only ever carries its own id (gate E6). |
| `user.id` | keyword | `ActorUserId` | Absent for system jobs and unknown-account logins. |
| `user.email` | keyword | `ActorEmail` | The claimed email on anonymous flows (a failed login names the attempted account). |
| `user.roles` | keyword[] | `ActorRole` | One element: `ADMIN`, `APPROVER`, `READ_WRITE`, `READ_ONLY`. |
| `source.ip` | ip | `Ip` | Client address as seen behind the trusted proxy (SEC-022). |
| `trace.id` | keyword | `RequestId` | The `X-Request-Id` — the same id is on every application log line of that request (E9). |
| `modocument.outcome` | keyword | `Outcome` | The precise outcome: `SUCCESS`, `DENIED` (refused), `FAILED` (attempted and failed). |
| `modocument.session_jti` | keyword (UUID) | `SessionJti` | Access-token jti (refresh-family jti on `LOGIN_SUCCESS`). |
| `modocument.entity.type` | keyword | `EntityType` | What the action touched, e.g. `AppUser`, `Template`, `IngestionJob`, `AlertLog`. |
| `modocument.entity.id` | keyword | `EntityId` | Its id. |
| `modocument.old_values` | object | `OldValues` | Free-form JSON snapshot before the change (action-specific; not part of the stability promise below). |
| `modocument.new_values` | object | `NewValues` | Free-form JSON snapshot after the change / event details (e.g. export row counts, alert channel). |

### Action vocabulary (non-exhaustive)

Authentication: `LOGIN_SUCCESS`, `LOGIN_FAILED`, `ACCOUNT_LOCKED`, `LOGOUT`,
`TOKEN_REFRESH_REUSE_DETECTED`, `SESSION_IDLE_EXPIRED`,
`SESSION_ABSOLUTE_EXPIRED`, `MFA_*`, `PASSWORD_*`, `RECOVERY_CODE_USED`.
Authorization / SoD: `AUTHORIZATION_DENIED`, `SELF_APPROVAL_BLOCKED`,
`DOC_SELF_RELEASE_BLOCKED`, `SOD_SCAN_COMPLETED`, `SOD_VIOLATION_FOUND`.
Administration: `USER_*`, `CHANGE_*`, `BOOTSTRAP_ADMIN_CREATED`,
`ACCESS_REVIEW_GENERATED`. Data: `EXPORT_RUN`, `EXPORT_BLOCKED`,
`EXPORT_OVER_CAP_WITH_REASON`, `DSR_*`, `RETENTION_*`, `JOB_DELETED`,
`TEMPLATE_*`. AI gateway: `AI_CALL`, `AI_PROMPT_REFUSED`,
`AI_PROMPT_BLOCKED_PII`, `AI_OUTPUT_FLAGGED`, `AI_OUTPUT_BLOCKED`. Alerting:
`ALERT_SENT`, `ALERT_DELIVERY_FAILED`. New actions appear as features ship —
consumers must treat `event.action` as an open set.

## Transports

### 1. Pull feed — `GET /api/siem/events` (recommended; multi-bank safe)

- Permission `siem:read` (ADMIN only). The bank's SIEM connector
  authenticates as a dedicated admin account **of that bank** (access token
  via the normal login + refresh-cookie rotation, or an OIDC-provisioned
  service user).
- **Bank-scoped by construction (E6):** the bank is taken from the caller's
  token, never from a parameter; the query filters `BankId = <caller's bank>`
  explicitly, and on PostgreSQL the `AuditEvent` row-level-security policy
  filters on the request's tenant as well. Bank A's credentials cannot read
  bank B's events — not by parameter, not by cursor (a foreign event id as
  `since` only positions the cursor; it reveals nothing).
- Query: `since` = the last `event.id` processed (exclusive; omit on the
  first call), `limit` = 1–1000 (default 500).
- Response: `200`, `Content-Type: application/x-ndjson`, one document per
  line, oldest first. Headers: `X-Next-Cursor` (pass as `since` next time;
  unchanged when the page is empty) and `X-Has-More` (`true` when the page
  was full — call again at once).
- Keyset paging on the UUIDv7 id (time-ordered; PostgreSQL compares `uuid`
  bytewise, so `id > cursor ORDER BY id` is stable). Events younger than
  `modocument.siem.feed.settle` (default 5 s) are held back for one poll so a
  transaction that commits late can never land behind the cursor. Poll every
  15–60 s for near-real-time.
- Reads of the feed are not themselves audited (each poll would add an event
  to the next page); a refused call is audited `AUTHORIZATION_DENIED`.

### 2. Push — `SiemWebhookForwarder` (optional; single-bank deployments)

- `modocument.siem.webhook.url` (+ optional `.secret`) — every committed audit
  event of **one** bank (`modocument.siem.webhook.bank-code`, required) is
  POSTed within seconds, in batches of up to 100, as `application/x-ndjson`
  (the same documents as above). With a secret, each body carries
  `X-MoDocument-Signature: sha256=<hex HMAC-SHA256(secret, body)>`.
- **Single-bank deployment assumption:** the forwarder drops every other
  bank's events before rendering them. A deployment that serves several
  banks leaves the push off and gives each bank's SIEM the pull feed.
- Delivery is at-least-once while the process runs: a failed POST keeps its
  batch at the head of a bounded in-memory queue and the alert framework's
  minute sweep retries it. Queue overflow (default 10,000) drops the oldest
  events and a restart loses the queue — both are logged; the pull feed is
  the gap-free backfill (dedupe on `event.id`).
- The URL is operator configuration, not user input, so it does not pass
  through the user-URL SSRF policy (`OutboundHostPolicy`), which would refuse
  the private-network collectors it legitimately points at. See
  `WebhookAlertChannel`'s javadoc.

### 3. Structured application logs

With the `structured-logs` profile every log line is an ECS JSON document
carrying `requestId`, `userId`, `bankId` and `ip`; each audit row also logs one
`audit action=... outcome=...` line, and every security alert logs a WARN on
logger `modocument.security.alert` tagged `SECURITY_ALERT` with
`alert.id/rule/severity/bank_id/audit_event_id` fields. Log shipping
(Filebeat/Fluent Bit) is an infrastructure concern; note that application
logs are **not** bank-partitioned — give them to the operator's SIEM, and
each bank's SIEM the per-bank feed above.

## Field-stability promise

- Every field in the **Field reference** table keeps its name, type and
  meaning. Removing or renaming one is a breaking change: it requires a new
  `event.dataset` (e.g. `modocument.audit.v2`) served alongside the old one
  for at least one release.
- New fields may be added at any time (additive change); consumers must
  ignore unknown fields.
- `event.action` is an open vocabulary; values are never re-purposed.
- `modocument.old_values` / `modocument.new_values` are free-form,
  action-specific snapshots and are **not** covered by this promise.
- The doc ↔ serializer equality is enforced by
  `SiemFeedE6Test#schemaDocFieldListMatchesTheSerializedOutput`.
