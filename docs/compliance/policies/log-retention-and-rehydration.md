# Log retention and rehydration runbook (SEC-033)

| Item | Value |
|---|---|
| Control | SEC-033 — Log retention for 7 years (depends on SEC-031, the central log pipeline) |
| Owner | IT Infrastructure — Bhavish Nobeen |
| Version | 1.0, written 2026-10-19 by agent A8; human review required |
| Status | Design in code, **not deployed**; the rehydration test has never run: `NOT IN REPO (live cloud)` |
| Design decision (2 Oct 2026) | Audit and security logs archived 7 years, locked against deletion; application logs 30–90 days |

Fact IDs TF-nn and defects DEF-n refer to
[A8-infra-checks.md](../evidence/A8-infra-checks.md).

## 1. Retention by log source

| Log | Where | Hot retention | 7-year copy | Locked? | Proof |
|---|---|---|---|---|---|
| Application audit trail (`AuditEvent`) | PostgreSQL | Kept indefinitely: the retention purge never touches it | **None outside the database.** Database backups keep 35 days (PITR) and 90 days (vault) only | Append-only in the database: UPDATE/DELETE/TRUNCATE revoked from the runtime role and blocked by triggers | `src/main/resources/db/migration/V14__audit_event.sql`; `src/test/java/com/modocument/pgit/AuditAppendOnlyPgIT.java`; `docs/security/retention-and-dsr.md` |
| Security alerts (`AlertLog`) | PostgreSQL | Kept with the audit trail | None outside the database | Database permissions | `src/main/resources/db/migration/V160__ws18_alert_log.sql` |
| AWS API activity (CloudTrail) | S3 log archive + CloudWatch | CloudWatch 400 days (code refuses < 365) | S3 log archive: Glacier after 90 days, expiry at 2557 days (7 years) | Object Lock COMPLIANCE 7 years **once `enforce_backup_locks = true`**; log-file validation on | TF-93, TF-94, TF-44 to TF-46 |
| Session Manager sessions | S3 log archive (`ssm-sessions/`) + CloudWatch | as above | as above | as above | TF-97 (see DEF-7) |
| App and nginx logs | CloudWatch `/ecs/<name>` | 90 days | **None** | No | TF-67 |
| VPC flow logs | CloudWatch | 30 days | None | No | TF-18 |
| PostgreSQL logs (connections, DDL) | CloudWatch (RDS export) | Not set in code (CloudWatch default: never expire) | None | No | TF-23, TF-29 (DEF-6) |
| GuardDuty findings | GuardDuty (AWS keeps findings 90 days) and the alert topic | — | None | — | TF-91, TF-92 |
| WAF, ALB, CloudFront access logs | Not enabled | — | — | — | TF-71, TF-83, TF-84 (DEF-9) |

**What meets the 7-year requirement today (in code):** CloudTrail and
Session Manager logs only, and only after the production lock flag is set.

**What does not:** the application audit trail has no archived copy (a
loss of the database beyond the 90-day backup horizon loses it), and
application, database, flow and edge logs are not archived (DEF-5).
`docs/security/data-inventory.md` row 14 and 15 overstate this ("7y archive
(IaC)").

**Seven years is not enforced in the database.** Nothing deletes
`AuditEvent` rows after seven years either. `TO CONFIRM (organisational)`:
whether audit rows must be deleted after 7 years or may be kept longer.

## 2. Target design (to build)

1. A CloudWatch Logs subscription (Firehose) from `/ecs/<name>`, the RDS
   log groups, and WAF logs into the log-archive bucket, compressed and
   partitioned by date (owner: Bhavish Nobeen; date `TO CONFIRM
   (organisational)`; depends on SEC-031).
2. A daily export of the previous day's `AuditEvent` and `AlertLog` rows
   (JSON Lines, per bank, per day) into the same bucket.
3. `enforce_backup_locks = true` in production after the restore drill
   (`infra/terraform/README.md`, apply order).
4. Move the archive to the lock account (gate A6).

## 3. Rehydration runbook

Goal (workbook acceptance): "a 13-month-old log line is retrievable via the
documented procedure". Objects older than 90 days are in the S3 GLACIER
(Flexible Retrieval) storage class (TF-46) and must be restored before they
can be read.

### 3.1 CloudTrail or Session Manager log (S3 archive)

1. Record the request: who asks, why, the time window (UTC), the event
   sought. Get approval from Bhavish Nobeen (or Rushal as deputy).
2. Identify the objects. CloudTrail keys follow
   `AWSLogs/<account-id>/CloudTrail/<region>/<yyyy>/<mm>/<dd>/`; session
   logs are under `ssm-sessions/<name>/`. List them with
   `aws s3api list-objects-v2 --bucket <log-archive-bucket> --prefix <prefix>`.
3. Restore a temporary readable copy:
   `aws s3api restore-object --bucket <bucket> --key <key> --restore-request '{"Days":7,"GlacierJobParameters":{"Tier":"Standard"}}'`.
   Standard tier takes hours; Expedited is faster at a higher cost; Bulk
   is cheapest and slowest. Object Lock is not affected: a restore creates
   a temporary copy and never changes the locked object.
4. Wait until `aws s3api head-object` shows `ongoing-request="false"`.
5. Check integrity: `aws cloudtrail validate-logs --trail-arn <trail-arn>
   --start-time <t1> --end-time <t2>` (log-file validation is on, TF-93).
6. Read: download the `.json.gz` files, or query them with Athena over the
   restored prefix.
7. File the result: the line found, the object key, the validation
   output, and the time the procedure took.

### 3.2 Application audit trail (database)

1. Within the database lifetime, query through the admin audit API
   (`GET /api/admin/audit`, `src/main/java/com/modocument/api/controller/AuditLogController.java`)
   or the bank SIEM feed (`GET /api/siem/events`), or read-only SQL as
   the schema owner.
2. After a database loss: only what the 90-day backups hold can be
   restored (see [storage-backup-recovery-map.md](storage-backup-recovery-map.md) R2).
   Older rows are lost until the daily export in §2 exists.

## 4. Rehydration test note (template)

| Field | Value |
|---|---|
| Test ID | SEC-033-T01 |
| Date | |
| Log line sought (age ≥ 13 months) | |
| Objects restored, tier | |
| Restore requested / available / line found (UTC) | |
| Integrity check output | |
| Result | Pass / Fail |
| Performed by / reviewed by | |

Last test: **none** (no archive exists yet; the first 13-month-old line
cannot exist before November 2027 even if the archive started today).
Interim proposal: run the procedure annually on the oldest available
object, from the first month after deployment.
