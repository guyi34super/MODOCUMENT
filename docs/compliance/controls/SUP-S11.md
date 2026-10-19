# SUP-S11 — Observability, SLO operations and status communication

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 04 IT Infrastructure | IT Infrastructure & Operations | P2 | Partially Implemented | SUP-S11-E01 | SUP-S11-T01 | R-SUP-S11 |

## Requirement
Turn the SLA (SEC-005) into an operated system with client-visible
transparency: external probes on the critical journeys, a hosted status
page, paging to on call. Acceptance: a synthetic-probe failure pages on
call within 5 minutes.

## How MoDocument meets it today
Fact IDs and defects in [A8-infra-checks.md](../evidence/A8-infra-checks.md).

| Element | State | Proof |
|---|---|---|
| Synthetic probe endpoint | Built: public, rate-limited, full path to the database; 3/3 tests passed 2026-10-19 | `src/main/java/com/modocument/api/controller/SyntheticProbeController.java`; `src/test/java/com/modocument/api/SyntheticProbeTest.java` |
| External probe / journey probes (login, report generation) | **None** — no Route 53 health check, Synthetics canary or external monitor | TF-96 |
| Metrics | ECS Container Insights on; AWS service metrics; WAF metrics per rule | `infra/terraform/modules/ecs-app/main.tf` (TF-63); TF-83 |
| Dashboards | **None** in code | TF-96 |
| Alarms | ALB 5xx, ECS CPU, RDS free storage, account-lockout burst → SNS topic; GuardDuty findings → same topic | `infra/terraform/modules/security-monitoring/main.tf` (TF-92, TF-95) |
| Burn-rate / SLO alerts | **None** — alarms are raw thresholds | TF-95, TF-96 |
| Alert delivery and paging | SNS topic with an optional e-mail subscription; no paging integration; DEF-2 blocks delivery | TF-90 |
| Application security alerts | Immediate and windowed rules over the audit trail, delivered to log, webhook and e-mail with a delivery record | `src/test/java/com/modocument/api/AlertingGateD1Test.java`; `docs/security/alerting-and-export-caps.md` |
| Logs and tracing | Request ID on every request in the log context; logs redacted; CloudWatch 90 days | `src/main/java/com/modocument/infrastructure/security/RequestIdFilter.java`; `src/test/java/com/modocument/infrastructure/logging/LogRedactionCanaryTest.java`; TF-67 |
| Status communication (in app) | Platform announcements (INFO, MAINTENANCE, RELEASE) shown as a banner to all or listed banks | `src/main/java/com/modocument/api/controller/AnnouncementController.java`; `frontend/src/components/AnnouncementBanner.jsx`; `src/test/java/com/modocument/api/AnnouncementApiTest.java`; `docs/release/runbook.md` §8 |
| External status page | **None**; `TO CONFIRM (organisational)` | `docs/security/sla-and-status.md` |
| On call | Bhavish Nobeen and Rushal weekly (design decision); rota and tool `TO CONFIRM (organisational)` | workbook |
| Post-incident review | Template written; no review yet (no production) | [post-incident-review-template.md](../policies/post-incident-review-template.md) |

## Evidence SUP-S11-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Alarm rules | `infra/terraform/modules/security-monitoring/main.tf` | repo |
| Probe endpoint and test run | `src/main/java/com/modocument/api/controller/SyntheticProbeController.java`; [A8-infra-checks.md](../evidence/A8-infra-checks.md) §2 | repo + run (2026-10-19) |
| In-app status announcements | `src/main/java/com/modocument/api/controller/AnnouncementController.java` | repo |
| Post-incident review template | [post-incident-review-template.md](../policies/post-incident-review-template.md) | repo |
| Dashboards, burn-rate alert rules | — | Missing (not built) |
| Status page URL | — | `TO CONFIRM (organisational)` |
| One post-incident review | — | `NOT IN REPO (live cloud)` — no production |

## Test SUP-S11-T01
- **Procedure:** (1) run `SyntheticProbeTest`; read the alarm and
  notification resources; (2) acceptance: make the external probe fail
  (for example stop the database in staging) and time the page to on
  call.
- **Expected result:** a synthetic-probe failure pages on call within
  5 minutes.
- **Actual result:** (1) probe tests passed 3/3; no external probe, no
  probe alarm, no paging integration, and alarm delivery is blocked by
  DEF-2; (2) not run — no AWS environment.
- **Result:** Partial.
- **Tester:** Agent A8 (re-performance); human sign-off required.
- **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| No external probes of login and report generation | Build | Synthetics canaries (login, report generation) plus the probe endpoint, from outside af-south-1 | IT Infrastructure — Bhavish Nobeen | Pre-go-live, date to set | Day 60 |
| No dashboards or burn-rate alerts | Build | CloudWatch dashboard (rate, errors, duration per service); fast-burn page and slow-burn ticket alarms on the 99.5 % objective | Bhavish Nobeen | Pre-go-live, date to set | Day 60 |
| No paging; delivery defect (DEF-2) | Build | Customer-managed key on the topic; subscribe a paging tool | Bhavish Nobeen | with the first apply | Day 60 |
| No status page | Build/Org | Hosted status page apart from the app | `TO CONFIRM (organisational)` | before go-live | Day 60 |
| Post-incident review template | Doc | Written | Agent A8 | 2026-10-19 | Done |

## Risk R-SUP-S11
- **Risk:** silent degradation; the bank learns of incidents before MO
  does.
- **Cause:** no external synthetic probing of user journeys; no paging.
- **Mitigation:** external probes, burn-rate alerts, paging within
  5 minutes, status page, post-incident reviews. Likelihood, impact and
  residual are set in MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: dashboards; probe-to-page timing; first post-incident review.
- `TO CONFIRM (organisational)`: status page service and URL; paging tool; on-call rota.
