# PRIV-003 — In-built ransomware detection capability

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 04 IT Infrastructure | IT Infrastructure & Operations | P1 | Partially Implemented | PRIV-003-E01 | PRIV-003-T01 | R-PRIV-003 |

## Requirement
Detect mass-encryption behaviour and guarantee recoverability even if
production is fully encrypted by an attacker (Go-Live Security Gate items
D4 and D5).

## How MoDocument meets it today
MoDocument is **pre-production**: the controls below are written in
Terraform, validated on 2026-10-19, and **not deployed**. Fact IDs (TF-nn)
and defects (DEF-n) are in
[A8-infra-checks.md](../evidence/A8-infra-checks.md).

**Detection (designed)**
- GuardDuty detector enabled, findings every 15 minutes; findings of
  severity ≥ 4 routed by EventBridge to the SNS alert topic
  (`infra/terraform/modules/security-monitoring/main.tf`, TF-91, TF-92).
- VPC flow logs of all traffic feed GuardDuty and investigations
  (`infra/terraform/modules/network/main.tf`, TF-18).
- CloudTrail, multi-region with log-file validation, records API calls
  such as mass deletion or KMS key changes (TF-93).
- **Not declared:** GuardDuty Runtime Monitoring and Malware Protection,
  chosen on 2 Oct 2026 (DEF-3). The alert topic's AWS-managed key would
  block delivery of findings (DEF-2).
- Endpoint detection (EDR) on staff devices: no tool chosen,
  `TO CONFIRM (organisational)` (see SUP-S09 and
  [endpoint-device-policy.md](../policies/endpoint-device-policy.md)). The
  ECS nodes run no EDR agent; GuardDuty Runtime Monitoring is the planned
  server-side equivalent.

**Recoverability (designed)**
- Database: daily AWS Backup recovery points kept 90 days, copied daily to
  a replica vault in ap-southeast-2 (Sydney) (`infra/terraform/modules/backups/main.tf`,
  TF-42, TF-03); RDS point-in-time recovery 35 days (TF-25); deletion
  protection and a final snapshot in production (TF-25, TF-27).
- Immutability: vault lock (compliance mode after a 3-day cooling-off,
  minimum retention 90 days) on both vaults, and Object Lock COMPLIANCE
  7 years on the log archive (TF-41, TF-45) — **only when
  `enforce_backup_locks = true`**, which is off by default and is meant to
  be switched on after the first restore drill
  (`infra/terraform/README.md`, apply order).
- Separate account: the vaults and the archive sit in the production
  account until the planned lock account exists; production credentials
  could then still change unlocked vaults (`infra/terraform/envs/production/providers.tf`, TF-03).
- The CI deploy role cannot touch backups, KMS or the log archive
  (`infra/terraform/modules/ci-oidc/main.tf`, TF-102).
- **Gap:** the S3 file store (uploads, exports) has versioning with a
  7-day window only, no backup copy and no lock; the task role may delete
  objects (TF-53, TF-54, TF-66, DEF-4).
- Database-level: the audit trail cannot be altered by the application
  role (`src/main/resources/db/migration/V14__audit_event.sql`,
  `src/test/java/com/modocument/pgit/AuditAppendOnlyPgIT.java`).

Restore procedures and the drill template:
[storage-backup-recovery-map.md](../policies/storage-backup-recovery-map.md).

## Questionnaire answer (final)
MoDocument is not yet in production and holds no bank data. Its AWS
environment is defined in code and will be deployed before go-live with:
Amazon GuardDuty threat detection on the account, with findings sent to the
on-call team; database backups taken daily, kept 90 days and copied to a
second AWS region; and an AWS Backup vault lock that, once enabled, stops
anyone, including our own administrators, from deleting or shortening
recovery points. Two items are still open before go-live: enabling
GuardDuty runtime and malware protection, and adding a locked backup copy
for the document file store. Endpoint protection for staff laptops will be
in place before go-live. We will prove recoverability with a timed full
restore to an isolated environment before bank data is accepted. Evidence:
PRIV-003-E01.

## Evidence PRIV-003-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| GuardDuty, CloudTrail, finding routing | `infra/terraform/modules/security-monitoring/main.tf` | repo |
| Backup vaults, vault lock, plan, cross-region copy | `infra/terraform/modules/backups/main.tf`; `infra/terraform/envs/production/providers.tf` | repo |
| Terraform validate (both envs) and defect list | [A8-infra-checks.md](../evidence/A8-infra-checks.md) | run (2026-10-19) |
| Restore procedures and drill template | [storage-backup-recovery-map.md](../policies/storage-backup-recovery-map.md) | repo |
| Backup immutability configuration export (vault lock state) | AWS console / `aws backup describe-backup-vault` | `NOT IN REPO (live cloud)` |
| EDR deployment coverage report | MDM / EDR console | `TO CONFIRM (organisational)` |
| Last restore-drill report | Drill record (gate D6) | `NOT IN REPO (live cloud)` — never run |

## Test PRIV-003-T01
- **Procedure:** (1) static re-performance: `terraform fmt -check` and
  `terraform validate` on both envs; read the backups and
  security-monitoring modules for the lock, copy and detection settings;
  (2) acceptance (workbook): a deletion attempt with production admin
  credentials fails (D5) and a full restore to a clean environment
  completes within the declared RTO.
- **Expected result:** full restore to a clean environment within the
  declared RTO.
- **Actual result:** (1) validate passed for both envs; lock, copy and
  GuardDuty are present in code, but the lock is off by default,
  GuardDuty malware/runtime features are absent and the file store is not
  backed up; (2) not run — no AWS environment exists.
- **Result:** Partial (static checks only).
- **Tester:** Agent A8 (static re-performance); human sign-off required.
- **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| Not deployed; no restore drill | Build | Apply production; run the D6 restore drill; then set `enforce_backup_locks = true` and run the D5 deletion test | IT Infrastructure — Bhavish Nobeen | Pre-go-live, date to set (`TO CONFIRM (organisational)`) | Day 60 (proposed mapping of the workbook's "pre-go-live") |
| GuardDuty runtime and malware protection not declared (DEF-3) | Build | Add `aws_guardduty_detector_feature` resources | Bhavish Nobeen | with the first apply | Day 60 |
| Alert topic cannot receive findings (DEF-2) | Build | Customer-managed KMS key for the topic | Bhavish Nobeen | with the first apply | Day 60 |
| File store has no locked copy (DEF-4) | Build | Add the bucket to AWS Backup or replicate to a locked bucket | Bhavish Nobeen | with the first apply | Day 60 |
| Vaults in the production account | Org/Build | Create the lock account (gate A6) and point `aws.backup_replica` at it | Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 90 |
| No EDR on staff devices | Org | Choose and deploy EDR with MDM (SUP-S09) | Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 60 |

## Risk R-PRIV-003
- **Risk:** a single compromised admin credential encrypts production and
  all reachable backups; total service loss.
- **Cause:** backups exist but are deletable with the same credentials
  that run production (locks off until enforced; same account; file store
  without a copy).
- **Mitigation:** vault lock and Object Lock in compliance mode, separate
  lock account, GuardDuty runtime/malware protection, timed restore drill
  (PRIV-003-T01). Likelihood, impact and residual are set in MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: vault lock state, GuardDuty feature state, restore-drill results, deletion test.
- `TO CONFIRM (organisational)`: EDR product and coverage for staff devices; lock-account creation date; go-live date for the gate items.
