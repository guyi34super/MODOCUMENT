# PRIV-006 — Details of data storage, backup and recovery

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 04 IT Infrastructure | IT Infrastructure & Operations | P1 | Partially Implemented | PRIV-006-E01 | PRIV-006-T01 | R-PRIV-006 |

## Requirement
Document exactly where data lives and prove point-in-time recoverability
with a defined RPO and RTO (Go-Live Security Gate items D4 and D6).

## How MoDocument meets it today
The storage map is now written:
[storage-backup-recovery-map.md](../policies/storage-backup-recovery-map.md).
It lists eleven stores with location, encryption, backup method,
frequency, retention, copy location, restore procedure and last drill.
The backup settings are Terraform settings (pre-production, not deployed;
fact IDs in [A8-infra-checks.md](../evidence/A8-infra-checks.md)):

- **Database** (RDS PostgreSQL 17, af-south-1, Multi-AZ in production,
  KMS CMK): RDS automated backups with point-in-time recovery for
  **35 days** (`backup_retention_period`, the RDS maximum), backup window
  01:00–02:00 UTC (`infra/terraform/modules/rds/main.tf`, TF-25); plus an
  AWS Backup daily recovery point at 00:30 UTC kept **90 days**, copied
  daily to ap-southeast-2 (Sydney) and kept 90 days there
  (`infra/terraform/modules/backups/main.tf`, TF-42).
- **File store** (S3, SSE-KMS CMK, TLS-only): versioning with deleted or
  overwritten versions kept 7 days; **no backup copy and no second
  region** (`infra/terraform/modules/file-store/main.tf`, TF-53, TF-54;
  DEF-4).
- **Logs and audit**: see SEC-033.
- **Secrets** (Secrets Manager): no backup copy; recovery window on
  deletion only. Restored databases need the matching MFA and connector
  keys (map §2).
- **Restore procedures** R1 (PITR), R2 (vault, including region loss),
  R3 (file versions), R5 (secrets), with a drill record template (map §4,
  §5).
- **RPO/RTO** (map §3): RPO ≤ 5 minutes in region and ≤ 24 hours for
  region loss (`docs/security/sla-and-status.md`); RTO 4 hours there,
  2 hours in the workbook — `TO CONFIRM (organisational)`.

The workbook text "PITR 90 days via AWS Backup" does not match the code:
PITR is 35 days, and the 90-day copies are daily snapshots (DOC-6).

## Questionnaire answer (final)
MoDocument is not yet in production and holds no bank data. At go-live,
all data will be stored in AWS Cape Town (af-south-1): the database in
Amazon RDS for PostgreSQL across two availability zones, and documents in
Amazon S3, both encrypted with dedicated KMS keys. The database has
continuous backups with point-in-time recovery for 35 days, and daily
backups kept 90 days in AWS Backup with an encrypted copy in a second AWS
region (Sydney). Document files are versioned; an off-site backup copy for
them is being added before go-live. Our recovery targets are an RPO of
5 minutes within the region and 24 hours for loss of the region. Restores
have not been tested yet because nothing is deployed; a timed restore
drill on the deployed system is a go-live condition and will then run
quarterly. The full storage map is provided. Evidence: PRIV-006-E01.

## Evidence PRIV-006-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Storage map, restore procedures, drill template | [storage-backup-recovery-map.md](../policies/storage-backup-recovery-map.md) | repo |
| Backup configuration (code) | `infra/terraform/modules/rds/main.tf`; `infra/terraform/modules/backups/main.tf`; `infra/terraform/modules/file-store/main.tf` | repo |
| Terraform validate and fact register | [A8-infra-checks.md](../evidence/A8-infra-checks.md) | run (2026-10-19) |
| Backup policy configuration export | `aws backup get-backup-plan`, `aws rds describe-db-instances` | `NOT IN REPO (live cloud)` |
| Last restore-drill timing | Drill record (gate D6) | `NOT IN REPO (live cloud)` — never run |

## Test PRIV-006-T01
- **Procedure:** (1) static: compare every data store in the code and
  `docs/security/data-inventory.md` with the storage map; check each
  backup setting against the Terraform code; validate both envs;
  (2) acceptance: a timed PITR restore meeting the declared RPO/RTO.
- **Expected result:** a timed PITR restore meeting the declared RPO/RTO.
- **Actual result:** (1) the map covers every store found; settings match
  the code; validate passed; found that the file store has no backup copy
  and that the workbook overstates the PITR window; (2) not run — no AWS
  environment.
- **Result:** Partial.
- **Tester:** Agent A8 (static re-performance); human sign-off required.
- **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| No backups exist; restore never timed | Build | Deploy; rehearse R1/R2 in staging; run the timed drill in production (D6); then quarterly | IT Infrastructure — Bhavish Nobeen (drill: Rushal, per gate D6) | Pre-go-live, date to set (`TO CONFIRM (organisational)`) | Day 60 |
| File store not backed up (DEF-4) | Build | AWS Backup for S3 or replication to a locked bucket in a second region | Bhavish Nobeen | with the first apply | Day 60 |
| RTO not agreed (2 h vs 4 h) | Org | Decide one RTO | Bhavish Nobeen; approver `TO CONFIRM (organisational)` | before the drill | Day 30 |
| Secrets have no recovery copy | Org/Build | Key escrow procedure for MFA and connector keys | Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 60 |
| Storage map | Doc | Written | Agent A8 | 2026-10-19 | Done |

## Risk R-PRIV-006
- **Risk:** backup corruption or misconfiguration is discovered only
  during a real incident; extended outage and data loss.
- **Cause:** backups run but restores have never been timed end to end;
  the file store has no copy.
- **Mitigation:** timed quarterly restore drills (PRIV-006-T01), file-store
  backup, key escrow. Likelihood, impact and residual are set in MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: backup plan export, recovery-point listing, restore-drill timing.
- `TO CONFIRM (organisational)`: one RTO value; legal basis for the Sydney copy (Go-Live Security Gate section F); key escrow; repository backup outside GitHub; drill dates.
