# Security alert on-call and escalation policy (SEC-032)

| Field | Value |
|---|---|
| Version | 0.1 draft, 2026-10-19 |
| Owner | Security — Bhavish Nobeen |
| Approver | Reezvi Pydiah (approver of security documents in the governance approval matrix, MO-001) — pending |
| Review | After every out-of-hours trip test, and at least every 6 months (proposed) |
| Related | Rule catalogue: [alerting-and-export-caps.md](../../security/alerting-and-export-caps.md); incident path: [incident-response-stub.md](../../security/incident-response-stub.md); roles: MO-001 Governance Structure, RACI rows 11 (incident handling) and 28 (security monitoring and alerts) |

Items marked **proposed** are recommendations of this pack that the approver
has not yet confirmed.

## 1. Where alerts come from

- **Application alerts.** 26 rules over the append-only audit trail, defined
  in `src/main/resources/application.yml` (`modocument.alerts.rules`),
  validated at startup by `src/main/java/com/modocument/infrastructure/alerting/AlertRuleCatalogue.java`.
  Each alert has a severity: INFO < LOW < MEDIUM < HIGH < CRITICAL
  (`src/main/java/com/modocument/domain/model/AlertSeverity.java`). Each alert
  is stored in `AlertLog` and listed under **Administration → Security
  alerts** (`GET /api/admin/alerts`).
- **Cloud alarms** (once applied): GuardDuty findings of severity ≥ 4, ALB
  5xx, ECS CPU, RDS storage and the account-lockout burst alarm publish to one
  SNS topic (`infra/terraform/modules/security-monitoring/main.tf`).
  `NOT IN REPO (live cloud)`.

## 2. Channels

| Channel | Configuration | Reaches | Status on 2026-10-19 |
|---|---|---|---|
| Log | always on (logger `modocument.security.alert`, tag `SECURITY_ALERT`) | Log store / SIEM | In code |
| Webhook — **the phone path** | `MODOCUMENT_ALERTS_WEBHOOK_URL`, `..._SECRET`, `..._MIN_SEVERITY` | On-call relay, WhatsApp/SMS bridge, PagerDuty/Opsgenie or chat | In code. **No receiver is configured**, and the ECS task definition (`infra/terraform/modules/ecs-app/`) does not pass these variables |
| E-mail | `MODOCUMENT_ALERTS_EMAIL_TO` + `SPRING_MAIL_HOST` | Mailboxes | In code; not configured in the ECS task definition |
| SNS e-mail | `alert_email` (`infra/terraform/envs/*/variables.tf`) | One mailbox | Terraform, not applied |

## 3. Who is on call

| Role | Person | Source |
|---|---|---|
| On-call engineer, weekly rotation | Bhavish Nobeen and Rushal, alternating weekly | 04 workbook SEC-005 and SUP-S11 comments (2 Oct 2026); MO-001 RACI row 28 |
| Incident owner (declares an incident) | Rushal | MO-001 approval matrix D14 (proposed — confirm); `docs/security/incident-response-stub.md` |
| Notification decision (banks, regulator, data subjects) | Reezvi Pydiah | `docs/security/incident-response-stub.md` step 5; MO-001 D15 |
| Personal-data impact assessment | The DPO | MO-001 D15 (proposed — confirm) |
| Bank-side receiver of the bank's own alerts | The bank's `ADMIN` | MO-001 RACI row 28 |

Phone numbers, the rotation calendar, the handover time and out-of-hours
contact details: `TO CONFIRM (organisational)`. They must not be written in
this repository; keep them in the on-call tool.

## 4. Response targets (proposed)

| Severity | Examples (rule names) | Delivery | Acknowledge | Act |
|---|---|---|---|---|
| CRITICAL | `refresh-token-reuse`, `sod-violation` | Webhook to the on-call phone, 24 × 7 | 15 minutes | Start the incident path at once; tell the incident owner |
| HIGH | `export-blocked`, `account-locked`, `login-failures-per-user`, `login-failures-per-ip`, `ai-prompt-refused`, `platform-change`, `ai-circuit-opened`, `api-client-auth-failures`, `integration-dead-letter` | Webhook to the on-call phone, 24 × 7 | 30 minutes | Triage within 1 hour; contain if confirmed |
| MEDIUM | `privileged-change`, `security-setting-changed`, `admin-action-burst`, `export-burst`, `authorization-denied-burst`, `access-denied-burst`, `sod-attempt-blocked`, `ai-output-blocked`, `announcement-change`, `parse-fallback-surge`, `integration-failures`, `agent-offline` | E-mail and chat webhook | Next business morning | Review; confirm the change was authorised |
| LOW / INFO | `admin-login`, `export-over-cap`, `version-deployed` | E-mail / log | Daily review | Spot-check the stated export reason |

Severity per rule is the code's (`src/main/resources/application.yml`). The
application has **one** webhook URL. Its payload carries `alert.severity`
(`src/main/java/com/modocument/infrastructure/alerting/WebhookAlertChannel.java`),
so the relay behind the URL must route HIGH and CRITICAL to the phone and the
rest to chat or e-mail (proposed). The webhook's minimum severity
(`alerts.webhook.minSeverity` platform setting, INFO/LOW/MEDIUM; HIGH and
CRITICAL always pass) should stay at INFO or LOW so the relay sees every
alert (proposed).

## 5. Escalation

1. The alert reaches the on-call engineer (phone path).
2. No acknowledgement within the target → the other engineer of the
   rotation (Bhavish Nobeen or Rushal) is called (proposed).
3. Confirmed or suspected incident → the incident owner (Rushal) declares it
   and follows [incident-response-stub.md](../../security/incident-response-stub.md).
4. Personal data or a bank's data affected → the DPO assesses; Reezvi
   Pydiah decides notifications. Nothing goes out without that decision.
5. Delivery failure: an alert whose channels keep failing is retried for 30
   minutes and then marked FAILED with an `ALERT_DELIVERY_FAILED` audit row
   (`modocument.alerts.delivery.retry-window`). The on-call engineer checks
   Administration → Security alerts for FAILED rows at the start of each
   shift (proposed).

## 6. Trip tests

| Test | When | Record |
|---|---|---|
| Automated: rules trip in the test suite | Every build | `AlertingGateD1Test`, `ExportCapGateD2Test`, `AccessDeniedBurstAlertTest`, `PlatformChangeAlertTest`, `AgentOfflineSweepTest`; run of 2026-10-19 in `docs/compliance/evidence/A5-scans-and-tests.md` |
| Live: trip one HIGH rule in staging, measure audit row → phone | After the webhook receiver is connected, then yearly (02 workbook SEC-032 frequency) | Date, rule, time to phone, recipient — `NOT IN REPO (live cloud)` until staging exists |
| Live, out of hours | Once after go-live, then yearly | Same record |

## 7. Open points

- No webhook receiver and no e-mail relay are configured; the ECS task
  definition does not pass the alert variables. Owner: Bhavish Nobeen.
  Target: 2026-10-30 (proposed; the workbook date of 2026-10-16 has passed).
- The owner statement of 2 Oct 2026 mentions a "root use" alert. The
  application alerts on admin sign-in (`admin-login`) and privileged changes;
  there is no AWS root-account-usage alarm in
  `infra/terraform/modules/security-monitoring/main.tf`.
- The rotation and contacts above are `TO CONFIRM (organisational)`.
