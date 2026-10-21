# PRIV-002 — Mechanism to detect data loss / breach prevention

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 02 Security | Security | P1 | Partially Implemented | PRIV-002-E01 | PRIV-002-T01 | R-PRIV-002 |

## Requirement
Detect and block abnormal extraction of data (DLP) and alert on breach
indicators. Acceptance: a simulated 10,000-row export by a normal role is
blocked and alerted within 5 minutes.

## How MoDocument meets it today
The owner statement of 2 Oct 2026 said "no export cap". That is out of date:
the cap, the reason field and the alert are in the code and tested.

- **Per-role export caps.** `src/main/java/com/modocument/infrastructure/export/ExportGuard.java`
  applies a row cap per role to both export paths (file download and database
  push) before anything is produced. Defaults in `src/main/resources/application.yml`
  (`modocument.export.caps`): READ_WRITE 5,000, ADMIN 50,000, API_CLIENT
  50,000; a role without an entry gets 0 (fail closed). READ_WRITE and ADMIN
  caps are also per-bank settings `export.cap.readWrite` / `export.cap.admin`.
- **Reason above the cap.** Without a reason the export is refused (403
  `EXPORT_CAP_EXCEEDED`); with a reason of at most 500 characters it proceeds
  and the reason is stored verbatim in the audit row. Control characters are
  stripped; an over-long reason is a 400 (`ExportGuard.normalizeReason`).
- **Export log.** Every export writes `EXPORT_RUN` (type, format, row count,
  cap); refusals write `EXPORT_BLOCKED` (DENIED) and over-cap exports
  `EXPORT_OVER_CAP_WITH_REASON`, all in the append-only audit trail
  (`src/main/resources/db/migration/V14__audit_event.sql`), all in the SIEM
  feed. The delivered file adds `EXPORT_DOWNLOADED`
  (`src/main/java/com/modocument/api/controller/IngestionExportController.java`).
- **Alerts.** Rules `export-blocked` (HIGH, immediate), `export-over-cap`
  (INFO, immediate) and `export-burst` (≥ 5 exports in 10 min, MEDIUM) in
  `src/main/resources/application.yml`; catalogue in
  `docs/security/alerting-and-export-caps.md`.
- **Other bulk paths.** Audit-log CSV export: hard cap 50,000 rows, masked,
  audited `AUDIT_LOG_EXPORTED` (no alert rule); user-list and DSR exports:
  audited and alerted by `privileged-change`.
- **Egress, application layer.** User-supplied URLs that resolve to internal
  or metadata addresses are refused
  (`src/main/java/com/modocument/infrastructure/net/OutboundHostPolicy.java`);
  private bank destinations are reachable only through `BankEgressRule` rows
  approved by platform admins
  (`src/main/java/com/modocument/infrastructure/integration/egress/BankEgressGuard.java`,
  `src/main/resources/db/migration/V251__ws25_egress_rules.sql`); approvals
  alert (`platform-change`).
- **Egress, network layer — not enforced.** The owner statement says
  outbound traffic is restricted to an allow-list. In the Terraform the app
  security group allows HTTPS to `0.0.0.0/0` by default
  (`infra/terraform/modules/network/variables.tf`, `app_egress_https_cidrs`)
  and the allow-list NACL is off (`enforce_egress_nacl = false`,
  `infra/terraform/modules/edge/main.tf`) until gate D3 sign-off. Other ports
  are closed: DB egress only to the DB security group, bank DB/SFTP ports only
  to `bank_egress_cidrs` (empty by default). No alert fires on a new egress
  destination. VPC flow logs and GuardDuty are written
  (`infra/terraform/modules/network/main.tf`,
  `infra/terraform/modules/security-monitoring/main.tf`), not applied.
- **Local AI.** The model endpoint defaults to a local endpoint
  (`aifiles.openai.base-url`, `src/main/resources/application.yml`); every model call
  goes through the AI gateway (`docs/security/ai-controls.md`).

## Questionnaire answer (final)
Every export is checked against a per-role row limit before any data leaves
the system (5,000 rows for normal users, 50,000 for administrators, both
configurable per bank). An export above the limit is refused unless the user
states a business reason; the refusal raises a high-severity security alert
immediately, and an allowed over-limit export raises an informational alert.
Every export, refusal and stated reason is recorded in the append-only audit
trail with user, job and row count, and is available to the bank's SIEM. In
our automated test of 19 October 2026, a 10,000-row export by a normal user
was refused and the alert was raised within 13 milliseconds. Outbound
connections to internal networks are blocked in the application, bank
network destinations need approval by two platform administrators, and AI
processing uses a locally hosted model by default. The network-level egress
allow-list will be enforced before go-live (target 13 November 2026).
Evidence PRIV-002-E01.

## Evidence PRIV-002-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Export-limit configuration | `src/main/resources/application.yml` (`modocument.export.caps`), `src/main/java/com/modocument/infrastructure/export/ExportCapProperties.java` | repo |
| Cap enforcement and reason | `src/main/java/com/modocument/infrastructure/export/ExportGuard.java` | repo |
| One triggered test alert (`export-blocked`, audit row → alert 13 ms, channels 43 ms) | `docs/compliance/evidence/A5-scans-and-tests.md` §1a | run 2026-10-19 |
| Egress allow-list (application) | `src/main/java/com/modocument/infrastructure/net/OutboundHostPolicy.java`, `src/main/java/com/modocument/domain/entity/BankEgressRule.java`; tests `OutboundHostPolicyTest`, `BankEgressGuardTest`, `EgressRuleIntegrationTest` (pass, 2026-10-19) | repo + run |
| Egress allow-list (network) | `infra/terraform/modules/network/main.tf`, `infra/terraform/modules/edge/main.tf` — seam only, not enforced | repo; live state `NOT IN REPO (live cloud)` |
| Alert rules | `docs/security/alerting-and-export-caps.md` | repo |

## Test PRIV-002-T01
- **Procedure:** re-perform the acceptance with `ExportCapGateD2Test`
  (READ_WRITE user, validated job of 10,000 rows, file and database export,
  with and without reason) and measure the alert latency from the run log.
- **Expected result:** a simulated 10k-row export by a normal role is blocked
  and alerted within 5 minutes.
- **Actual result:** 5 tests, 0 failures. Export refused with 403
  `EXPORT_CAP_EXCEEDED` (cap 5,000, rows 10,000); `EXPORT_BLOCKED` audited;
  `export-blocked` alert raised 9–13 ms after the audit row and accepted by
  the log and webhook channels within 43 ms; nothing exported. Limit: the
  webhook is a test receiver; no person's phone is in the loop (see SEC-032).
- **Result:** Pass (application acceptance) · tester: Agent A5 (automated
  re-performance; human sign-off required) · date: 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| Network egress allow-list not enforced; no alert on a new egress destination | Config | Narrow `app_egress_https_cidrs`, set `enforce_egress_nacl = true` after sign-off (gate D3); add a GuardDuty/flow-log based alert on new destinations | Security — Bhavish Nobeen (define), Reezvi Pydiah (sign-off) | 2026-11-13 | Day 60 |
| Alerts reach no person yet | Config | See SEC-032 (phone path) | Security — Bhavish Nobeen | 2026-10-30 (proposed) | Day 30 |
| Audit-log CSV export has no alert rule | Build | Add `AUDIT_LOG_EXPORTED` to an alert rule (e.g. `privileged-change`) | Security — Bhavish Nobeen | 2026-11-13 (proposed) | Day 60 |

## Risk R-PRIV-002
Bulk data theft proceeds unnoticed, or data leaves through an unrestricted
outbound path; regulatory notification deadlines are missed. Cause: the
in-app cap now exists, but the network egress allow-list is not enforced and
alerts are not yet routed to a person. Mitigation: the actions above; the
in-app cap blocks the main exfiltration path today. Likelihood, impact and
residual are set in MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: the egress allow-list in production, VPC flow logs, GuardDuty.
- `TO CONFIRM (organisational)`: the signed-off list of allowed outbound destinations (gate D3).
