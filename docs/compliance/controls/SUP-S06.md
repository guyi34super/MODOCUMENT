# SUP-S06 — Database network and account hardening

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 04 IT Infrastructure | IT Infrastructure & Operations | P2 | Partially Implemented | SUP-S06-E01 | SUP-S06-T01 | R-SUP-S06 |

## Requirement
Harden the database beyond encryption and grants. Acceptance: a
connection attempt from outside the app subnet and a plaintext (non-TLS)
connection both fail.

## How MoDocument meets it today
Pre-production; Terraform validated, not deployed. Fact IDs in
[A8-infra-checks.md](../evidence/A8-infra-checks.md).

**Network**
- RDS in the private DB subnets of both AZs; those subnets have no
  internet route; `publicly_accessible = false`
  (`infra/terraform/modules/network/main.tf`, `infra/terraform/modules/rds/main.tf`;
  TF-10, TF-13, TF-24).
- DB security group: ingress 5432 from the app security group only, no
  egress rules (TF-14). No bastion or human path exists in Terraform.

**TLS**
- Server side: `rds.force_ssl = 1` refuses non-TLS connections (TF-23).
- Client side: the app connects with `sslmode=verify-full` against the RDS
  CA bundle baked into the image (`infra/terraform/envs/production/main.tf`
  `db_jdbc_url`; `Dockerfile` `rds-ca` stage; key register K14).

**Parameters and logging** (`aws_db_parameter_group.this`, TF-23,
TF-29): `log_statement = ddl`, `log_connections = 1`,
`log_disconnections = 1`, exported to CloudWatch (retention unset, DEF-6).
Performance Insights off to avoid capturing PII in sampled SQL (TF-30).
`pg_hba.conf` is not editable on RDS; `rds.force_ssl` is the RDS
equivalent of hostssl-only rules. Password hashing method: not set in
Terraform, so the engine default applies — `NOT IN REPO (live cloud)`.

**Accounts**
| Account | Rights | Proof |
|---|---|---|
| RDS master (schema owner, Flyway) | Owner of the schema; password generated and held by RDS in Secrets Manager, never in tfvars or state | TF-28 |
| `modocument_app` (runtime) | `LOGIN NOBYPASSRLS`; `SELECT, INSERT, UPDATE, DELETE` on tables and `USAGE, SELECT` on sequences only — no DDL | `src/main/resources/db/migration/V12__app_role_grants.sql`; `docker/postgres-init/01-app-role.sh` |
| `modocument_app` on `AuditEvent` | `UPDATE, DELETE, TRUNCATE` revoked; triggers block them for every role | `src/main/resources/db/migration/V14__audit_event.sql`; `src/test/java/com/modocument/pgit/AuditAppendOnlyPgIT.java` |
| Row-level security | Enabled with a tenant policy on every table with a `BankId` column after each migration run | `src/main/resources/db/migration/afterMigrate__rls_enforce.sql`; `src/test/java/com/modocument/pgit/RlsPolicyCoveragePgIT.java` |
| Humans | No personal database accounts or audited access path are defined | `TO CONFIRM (organisational)` |

**Separation gap:** the ECS app task receives the master (Flyway)
credentials as well as the runtime credentials, and runs migrations at
start-up (`infra/terraform/modules/ecs-app/main.tf`, TF-69;
`src/main/resources/application.yml` `spring.flyway.user`). The workbook
wants the DDL account used only by the pipeline (DEF-11).

**Backup and archive bucket policies:** the log-archive bucket policy
only grants CloudTrail write access; it has no deny-non-TLS statement
(unlike the file store, TF-52), and no backup-vault access policy exists
(`infra/terraform/modules/backups/main.tf`,
`infra/terraform/modules/security-monitoring/main.tf`).

## Evidence SUP-S06-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Network and parameter configuration | `infra/terraform/modules/network/main.tf`; `infra/terraform/modules/rds/main.tf` | repo |
| Grants per account | `src/main/resources/db/migration/V12__app_role_grants.sql`; `src/main/resources/db/migration/V14__audit_event.sql`; `src/main/resources/db/migration/afterMigrate__rls_enforce.sql` | repo |
| Grant and RLS tests | `src/test/java/com/modocument/pgit/AuditAppendOnlyPgIT.java`; `src/test/java/com/modocument/pgit/RlsPolicyCoveragePgIT.java` | repo (not re-run by A8: needs a PostgreSQL container) |
| Backup/archive bucket policy | `infra/terraform/modules/security-monitoring/main.tf` (`aws_s3_bucket_policy.trail`) | repo |
| Parameter export, grants listing from the live instance | `aws rds describe-db-parameters`; `\dp` on the instance | `NOT IN REPO (live cloud)` |

## Test SUP-S06-T01
- **Procedure:** (1) static: read SG rules, route tables,
  `publicly_accessible`, `rds.force_ssl`, grants and task secrets;
  validate Terraform; (2) acceptance: from a host outside the app subnet,
  connect to 5432 (expect timeout); from an app task, connect with
  `sslmode=disable` (expect refusal).
- **Expected result:** both connection attempts fail.
- **Actual result:** (1) configuration matches the requirement except the
  master credentials in the app task and the archive bucket's missing TLS
  deny; validate passed (2026-10-19); (2) not run — no AWS environment.
- **Result:** Partial.
- **Tester:** Agent A8 (static re-performance); human sign-off required.
- **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| Not deployed; connection tests not run | Config | Deploy; run both negative connection tests | IT Infrastructure — Bhavish Nobeen | Pre-go-live, date to set | Day 60 |
| Master credentials in the app task (DEF-11) | Build | Run Flyway as a one-off ECS task or pipeline step; remove the master secret from the service task | Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 60 |
| No human access path | Org/Build | Define personal DB accounts and an audited path (Session Manager port forwarding) | Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 60 |
| Archive bucket lacks a TLS-only deny; no vault access policy | Config | Add `aws:SecureTransport` deny and a vault policy denying deletes | Bhavish Nobeen | with the first apply | Day 60 |

## Risk R-SUP-S06
- **Risk:** a reachable database endpoint or a shared account defeats the
  application-layer controls.
- **Cause:** the running app holds the DDL (master) credentials.
- **Mitigation:** private network, TLS forced, DML-only RLS-bound runtime
  role, migrations moved out of the service. Likelihood, impact and
  residual are set in MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: parameter export; live grants listing; password hashing method; negative connection tests.
- `TO CONFIRM (organisational)`: human database access path and accounts.
