# Storage, backup and recovery map (PRIV-006)

| Item | Value |
|---|---|
| Controls | PRIV-006 — Details of data storage, backup and recovery; used by PRIV-003, SEC-004, SEC-033 |
| Owner | IT Infrastructure — Bhavish Nobeen |
| Version | 1.0, written 2026-10-19 by agent A8 from the repository; human review required |
| Status | **Design in code, not deployed.** Backup settings below are Terraform settings (fact IDs TF-nn in [A8-infra-checks.md](../evidence/A8-infra-checks.md)). No backup has ever run and no restore has ever been performed: `NOT IN REPO (live cloud)`. |
| Review | Quarterly, with the restore drill |

## 1. Every data store

| # | Store | What it holds | Location | Encryption at rest | Backup method | Frequency | Retention | Copy location | Restore procedure | Last drill |
|---|---|---|---|---|---|---|---|---|---|---|
| S1 | RDS PostgreSQL 17 (`modocument` database) | All application data; table groups in §2 | af-south-1, private DB subnets, Multi-AZ in production (TF-20, TF-21, TF-24) | KMS CMK `alias/modocument-prod-rds`, rotation on (TF-22) | (a) RDS automated backups with point-in-time recovery; (b) AWS Backup daily snapshot, copied to a second region | (a) continuous transaction logs + daily automated snapshot in the 01:00–02:00 UTC window; (b) daily 00:30 UTC (TF-25, TF-42) | (a) 35 days PITR (TF-25); (b) 90 days in both vaults (TF-42); vault lock min 90 / max 366 days once enforced (TF-41) | (b) replica vault in ap-southeast-2, Sydney (TF-03, TF-40) | R1 or R2 below | Never (no production). `NOT IN REPO (live cloud)` |
| S2 | S3 file store `modocument-prod-files-<account>` | `uploads/` (customer uploads, doc-creation blueprints and image assets), `exports/` (export copies, generated documents, `exports/u<owner>/job<id>.<ext>`) — layout in `docs/optimization/contracts.md` §7 | af-south-1 (TF-50) | SSE-KMS, own CMK `alias/modocument-prod-files`, rotation on; TLS-only bucket policy (TF-51, TF-52) | S3 versioning only | On every write | Deleted or overwritten versions kept **7 days**; `exports/` current objects expire after 30 days; `uploads/` no storage expiry (TF-53) | **None** — not in AWS Backup, no replication (TF-54, DEF-4) | R3 below | Never |
| S3 | S3 log archive `modocument-prod-log-archive-<account>` | CloudTrail logs, Session Manager session logs (TF-93, TF-97) | af-south-1, same account until the lock account exists | SSE-KMS with the AWS managed key (TF-44) | The bucket **is** the archive: versioning on; Object Lock COMPLIANCE 7 years once `enforce_backup_locks = true` (TF-44, TF-45) | Continuous (CloudTrail delivery) | Glacier after 90 days; expiry after 2557 days = 7 years (TF-46) | None | R4 (rehydration) in [log-retention-and-rehydration.md](log-retention-and-rehydration.md) | Never |
| S4 | CloudWatch Logs | `/ecs/<name>` app and nginx logs (90 days, TF-67); `/vpc/<name>/flow-logs` (30 days, TF-18); `/cloudtrail/<name>` (400 days, TF-94); RDS `postgresql` and `upgrade` exports (retention not set in code, TF-29) | af-south-1 | CloudWatch default encryption; no CMK named | None (logs are evidence, not recovery data) | — | As listed | None | Not restored; CloudTrail copy is in S3 | — |
| S5 | AWS Secrets Manager | JWT signing key, MFA key, connector key, `modocument_app` DB credentials, RDS master secret, bootstrap/platform admin pairs (TF-08, TF-28) | af-south-1 | Secrets Manager default key | **None in code.** A deleted secret is recoverable during its recovery window (AWS default 30 days; `infra/terraform/README.md` apply-order step 5). No cross-region replica. | — | — | None | R5 below | Never |
| S6 | KMS customer-managed keys | RDS key, file-store key (TF-22, TF-51) | af-south-1 | — | Keys cannot be exported; deletion has a 30-day waiting window (`deletion_window_in_days = 30`) | — | — | Copies in ap-southeast-2 are re-encrypted with that vault's key by AWS Backup | Cancel key deletion during the window | Never |
| S7 | ECR repositories | Released app and frontend images (TF-101) | af-south-1 | AES256 | Rebuildable from the git tag (`.github/workflows/release.yml`); tags immutable | Per release | No lifecycle policy in code (images kept) | None | Re-run `release.yml` for the tag | — |
| S8 | Terraform state | Resource IDs and the CloudFront origin-verify value | Planned S3 bucket + DynamoDB lock table, not created (TF-05) | Planned `encrypt = true` | Planned: bucket versioning; Object Lock recommended | — | — | — | Restore the previous state object version | — |
| S9 | mTLS trust-store bucket | Banks' issuing CA certificates (public) (TF-75) | af-south-1, only when mTLS is on | AES256 | Versioning; the source of truth is `api_mtls_ca_bundle_pem` in tfvars | — | — | — | Re-apply Terraform | — |
| S10 | Connector Agent state (at a bank) | Agent id and client credentials (`credentials.json`), local secrets (`agent/README.md`) | Bank premises, `/var/lib/modocument-agent` volume (`agent/Dockerfile`) | Bank's host controls — `TO CONFIRM (organisational)` per bank | Bank's responsibility | — | — | — | Revoke the agent and enrol again (`docs/integration/agent.md`) | — |
| S11 | Source code and IaC | Application, Terraform, docs | GitHub repository | GitHub-managed | `TO CONFIRM (organisational)`: no repository backup outside GitHub is documented | — | — | — | — | — |

Stores that do **not** exist in production: no cache service (rate-limit
counters and the announcement cache are in-process memory and hold no
durable data: `src/main/resources/application.yml` `modocument.ratelimit`
comment, `src/main/java/com/modocument/infrastructure/release/AnnouncementCache.java`);
no MongoDB (the optional extraction store runs only in `docker-compose.yml`);
no EFS. The container disk holds only temporary copies (S3 download cache in
`java.io.tmpdir`).

## 2. Table groups in S1 (PostgreSQL)

From `docs/security/data-inventory.md` and the migrations in
`src/main/resources/db/migration/`.

| Group | Examples | Retention in the database |
|---|---|---|
| Tenants and identity | `Bank`, `AppUsers`, MFA secrets (AES-256-GCM), refresh-token hashes | Account life |
| Configuration | Templates, rules, doc-creation blueprints, settings | Kept |
| Customer working data | `UploadedFile`, `IngestionJob` checkpoints, extraction records, doc-creation content | 30 days, then purged by `src/main/java/com/modocument/infrastructure/retention/RetentionPurgeJob.java` |
| Evidence | `AuditEvent` (append-only, `src/main/resources/db/migration/V14__audit_event.sql`), `AlertLog` | Never purged by the application (`docs/security/retention-and-dsr.md`) |
| Integration | Connectors, API clients (secrets hashed), sealed bank credentials (`src/main/java/com/modocument/infrastructure/integration/secrets/ConnectorSecretVault.java`), agents | Life of the integration |
| Governance | Maker/checker queue, announcements, deployed versions | Kept |

**Backups outlive the 30-day purge.** A purged record stays inside RDS
backups for up to 35 days (PITR) and inside vault snapshots for 90 days, in
both regions. This must be stated in the retention schedule and in the data
transfer basis for the Sydney copy. `TO CONFIRM (organisational)`: legal
basis for the cross-border copy (Go-Live Security Gate, section F).

**Key dependency.** MFA secrets and sealed bank credentials in a restored
database open only with the matching `MODOCUMENT_MFA_KEY` and
`MODOCUMENT_CONNECTOR_KEY` (and retired connector-key versions). A restore
needs the keys that were active when the backup was taken. Secrets have no
backup copy today (S5). `TO CONFIRM (organisational)`: offline escrow of
these keys.

## 3. Recovery objectives

| Objective | Value | Source | Status |
|---|---|---|---|
| RPO (database, in-region) | ≤ 5 minutes within the 35-day PITR window | `docs/security/sla-and-status.md`; TF-25 | Design value; never measured |
| RPO (region loss) | ≤ 24 hours (daily copy to Sydney) | `docs/security/sla-and-status.md`; TF-42 | Design value; never measured |
| RPO (file store) | Last write, while the object version is within 7 days; **no protection against region loss** | TF-53, TF-54 | Gap DEF-4 |
| RTO | 4 hours in `docs/security/sla-and-status.md`; 2 hours in the workbook (SEC-005 comment, 2 Oct 2026) | — | `TO CONFIRM (organisational)`: one value |

## 4. Restore procedures

These are written from the Terraform design. They have never been run.
Gate D6 requires them to be run, timed and signed on the deployed system.
Rehearse in staging first (`infra/terraform/envs/staging/main.tf`: backups
present, locks off).

### R1 — Database point-in-time restore (in region)

1. Declare the incident and the target time T (UTC) in the incident record
   (`docs/security/incident-response-stub.md`).
2. Stop writes: scale the ECS service to 0, or publish a MAINTENANCE
   announcement first (`docs/release/runbook.md` §8).
3. Restore: `aws rds restore-db-instance-to-point-in-time` from the
   production instance to a **new** identifier at time T, with the same DB
   subnet group, security group, parameter group and KMS key (TF-22 to TF-24).
4. Verify on the new instance: Flyway schema version, row counts of the
   main tables against the last known counts, `AuditEvent` count, a sample
   login on an isolated task.
5. Switch: bring the new instance under Terraform (state move/import, then
   rename) so `db_jdbc_url` points at it, re-apply, and redeploy the
   service. The exact switch is to be fixed during the staging rehearsal.
6. Record start/end times, achieved RPO and RTO, and counts in the drill
   record (§5).

### R2 — Restore from the backup vault (in region or Sydney)

1. List recovery points in the vault (`aws backup list-recovery-points-by-backup-vault`).
2. Start a restore job of the chosen point into the region needed, with
   the restore role from `infra/terraform/modules/backups/main.tf`
   (`AWSBackupServiceRolePolicyForRestores`).
3. For **region loss**: no network, ECS or edge exists in ap-southeast-2.
   The stack must first be built there from Terraform (a new env root with
   the region changed); then restore the database, set secrets, deploy
   images, switch DNS. This is a full rebuild, not a failover (SEC-004).
4. Continue as R1 steps 4–6.

### R3 — File store object recovery

1. Within 7 days of a delete or overwrite: list object versions under the
   affected prefix and copy the wanted noncurrent version back as current.
2. After 7 days, or if the bucket is lost: **no recovery** (DEF-4).
   Regenerable exports can be recreated from their jobs; uploads cannot.

### R5 — Secret recovery

1. A deleted secret: `aws secretsmanager restore-secret` within its
   recovery window.
2. A lost or changed key value: restore the value from the key owner's
   escrow (`TO CONFIRM (organisational)`); without it, MFA enrolments must
   be reset and bank credentials re-entered.

## 5. Restore drill record (template)

| Field | Value |
|---|---|
| Drill ID | DRILL-YYYY-Qn |
| Date and environment | |
| Procedure run | R1 / R2 / R3 |
| Recovery point / target time | |
| Start, data available, service available (UTC) | |
| Achieved RPO / RTO | |
| Record counts before / after (per main table) | |
| Problems found | |
| Run by / independent reviewer | Rushal (gate D6 owner) / Pack 07 reviewer |
| Sign-off | |

Last drill: **none**. The first drill (gate D6) is due after deployment;
`TO CONFIRM (organisational)`: date.

## 6. Backup policy configuration export

When AWS exists, file with this map the output of `aws backup
get-backup-plan`, `aws backup describe-backup-vault` (it shows the vault
lock and its minimum and maximum retention) and `aws rds
describe-db-instances` (retention, windows, Multi-AZ). Until then, the configuration is the
Terraform code cited above: `NOT IN REPO (live cloud)`.
