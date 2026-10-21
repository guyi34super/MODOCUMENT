# PRIV-011 — How you ensure confidentiality, integrity and availability (CIA)

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 02 Security | Security | P1 | Partially Implemented | PRIV-011-E01 | PRIV-011-T01 | R-PRIV-011 |

## Requirement
Present one enforced control set per CIA pillar, cross-referencing the
detailed rows. Acceptance: every cell names a verifiable artifact, not an
intention.

## How MoDocument meets it today
- **The one-page table** is `docs/security/cia-controls-table.md` (version
  1.1 draft, 2026-10-19). Rows: access, application, data/tenant,
  session/transport, secrets/crypto, network/egress, infrastructure,
  monitoring; columns C, I, A. Each cell names a test class, a file or a
  Terraform path, and the control IDs it supports.
- **Confidentiality** in code and tested: RBAC with 6 roles × 41 permissions
  pinned to `docs/security/role-permission-matrix.csv` (`RolePermissionMatrixTest`),
  admin MFA (`MfaFlowIntegrationTest`), masking (`MaskingGateC2Test`), RLS
  (`TenantIsolationGatePgIT`), export caps (`ExportCapGateD2Test`), AI gateway
  (`AiGatewayC9Test`), log redaction (`LogRedactionCanaryTest`).
- **Integrity** in code and tested: maker/checker (`MakerCheckerIntegrationTest`),
  upload gate and hashes (`UploadContentGateSec030Test`, `IntegrityGateC6Test`),
  append-only audit trail (`src/main/resources/db/migration/V14__audit_event.sql`,
  `AuditAppendOnlyPgIT`).
- **Encryption and key management** are documented in
  `docs/security/crypto-standard.md` and `docs/security/key-certificate-register.md`
  (the workbook's open SEC-029 item).
- **Availability** is designed, not proven: Multi-AZ RDS, 2-AZ ECS,
  autoscaling and locked backups exist only as Terraform
  (`infra/terraform/modules/rds`, `infra/terraform/modules/ecs-app`,
  `infra/terraform/modules/backups`), validated but not applied. The workbook
  planned a first restore drill on 3 Oct 2026; there is no drill record in
  the repository and the RTO is still "pending" in
  `docs/security/sla-and-status.md`.
- **Egress** (corrected in the table): application-layer controls are tested;
  the network allow-list is not enforced (see PRIV-002).

## Questionnaire answer (final)
Our CIA controls are listed on one page, each with the test, configuration
file or infrastructure code that proves it. Confidentiality: role-based access
with six roles and a test-pinned permission matrix, MFA for administrators,
role-based masking of personal identifiers, database row-level security per
bank, per-role export limits with alerts, a single AI gateway with a locally
hosted model by default, log redaction, and encryption in transit and at
rest. Integrity: server-side validation and upload content checks, SHA-256
file hashes, maker/checker approval for critical changes, separation of
author and releaser, and an append-only audit trail enforced by the database.
Availability: multi-zone database and application tiers, autoscaling, and
locked backups with a cross-region copy are defined as infrastructure code
for our pre-production AWS environment; the first timed restore drill will
run in staging before go-live. Evidence PRIV-011-E01.

## Evidence PRIV-011-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| CIA controls table v1.1 draft | `docs/security/cia-controls-table.md` | repo |
| Access matrix pinned to code | `docs/security/role-permission-matrix.csv`, `RolePermissionMatrixTest` (pass, 2026-10-19) | repo + run |
| Confidentiality/integrity tests re-run | `docs/compliance/evidence/A5-scans-and-tests.md` | run 2026-10-19 |
| Crypto standard and key register | `docs/security/crypto-standard.md`, `docs/security/key-certificate-register.md` | repo |
| Restore drill record | none in the repository | `NOT IN REPO (live cloud)` |
| Approval of the table | — | `TO CONFIRM (organisational)` (Reezvi Pydiah) |

## Test PRIV-011-T01
- **Procedure:** read every cell of `docs/security/cia-controls-table.md`;
  check that each names an artifact that exists (script check of every cited
  class and path on 2026-10-19) and re-run a sample of the cited tests.
- **Expected result:** every cell names a verifiable artifact, not an
  intention.
- **Actual result:** every cell names an existing test, file or Terraform
  path (0 missing citations). Sampled tests passed (`RolePermissionMatrixTest`,
  `ExportCapGateD2Test`, `AiGatewayC9Test`, `MakerCheckerIntegrationTest`,
  `AuthFlowIntegrationTest`, `SiemFeedE6Test`, `OutboundHostPolicyTest`,
  `BankEgressGuardTest`). Cells marked 🏗️ name configuration with no runtime
  evidence; two cells name a gap (⛔ network egress, ⛔ restore drill) instead
  of a control. The PostgreSQL tests (PgIT) were run on a scratch PostgreSQL 16 by the A4, A7 and A9 runs on 2026-10-19 and passed (`docs/compliance/evidence/A4-test-runs.md`, `docs/compliance/evidence/A7-runs.md`, `docs/compliance/evidence/A9-retest-runs.md`).
- **Result:** Pass for the table criterion; the control stays Partially
  Implemented because availability is untested · tester: Agent A5 (automated
  re-performance; human sign-off required) · date: 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| Table not approved (workbook date 2026-10-16 passed) | Org | Approve v1.1 | Reezvi Pydiah | 2026-11-02 (proposed) | Day 30 |
| Availability untested: no restore drill | Build | Timed restore drill in staging (gate D6), then record RTO/RPO | Rushal (drill), Security — Bhavish Nobeen | after the first `terraform apply` — date `TO CONFIRM (organisational)` | Pre-go-live |
| RTO disagreement: 4 h (`docs/security/sla-and-status.md`) vs 2 h (04 workbook SEC-005) | Doc | Settle one figure at approval | Security — Bhavish Nobeen | 2026-11-02 (proposed) | Day 30 |

## Risk R-PRIV-011
One pillar, usually availability, stays unmanaged because controls are
scattered. Cause: until now the CIA view was not consolidated and the
availability controls exist only as code. Mitigation: the one-page table with
artifacts; the restore drill before go-live. Likelihood, impact and residual
are set in MO-031.

## To confirm
- `TO CONFIRM (organisational)`: whether the 3 Oct 2026 restore drill took place; approval of the table.
- `NOT IN REPO (live cloud)`: every 🏗️ cell of the table; restore drill log.
