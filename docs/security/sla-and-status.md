# SLA & status page — SEC-005 / SUP-S11

Last reviewed: 2026-10-19 (agent A8, technology-risk pack; previous review
2026-10-09). **MoDocument is pre-production: nothing below has been
measured.** Every availability figure is a commitment or a target, not a
result. Fact IDs TF-nn and defects DEF-n refer to
[A8-infra-checks.md](../compliance/evidence/A8-infra-checks.md).

## Commitment

| Term | Value |
|---|---|
| Availability | **99.5 %** monthly, measured at the public endpoint (CloudFront/ALB), excluding the announced maintenance window (design decision of 2 Oct 2026) |
| Allowed downtime at 99.5 % | ~3 h 39 m per month (0.5 % of a 30.44-day month) |
| Maintenance window | **Sundays 07:00–09:00 Mauritius time (UTC+4)** — wraps the IaC's UTC windows (RDS backup 01:00–02:00 UTC daily, RDS maintenance Sun 03:00–04:00 UTC: `infra/terraform/modules/rds/main.tf`); announce to banks in local time, in the app as a MAINTENANCE announcement (`docs/release/runbook.md` §8) |
| Measurement | External HTTP probe on the public synthetic probe `GET /api/probe/synthetic` (build version + one database round trip; 200 = up, 503 = database down) through the public entry point, 1-minute interval; a month's availability = successful probes / total probes outside the window. (`/actuator/health` is the container liveness check: nginx does not route it, so through the public entry point it would only ever return the SPA.) |
| RPO | ≤ 24 h from vault recovery points (daily copy); ≤ 5 min via RDS PITR within the 35-day window (TF-25, TF-42) |
| RTO | Target **4 h** here; the workbook (2 Oct 2026) says **2 h**. `TO CONFIRM (organisational)`: one value. Either is validated only by the gate D6 restore drill, which is pending. |
| Service credits | `TO CONFIRM (organisational)` |
| On call | Bhavish Nobeen and Rushal, rotating weekly (design decision, 2 Oct 2026). Rota, tooling and out-of-hours paging: `TO CONFIRM (organisational)` |

### SLA clause (draft for the contract)

> MO Intelligence will make MoDocument available 99.5 % of each calendar
> month, measured by MO Intelligence's external monitoring of the public
> service endpoint at one-minute intervals. Planned maintenance announced
> at least [N] days in advance and performed on Sundays between 07:00 and
> 09:00 Mauritius time is excluded. If availability falls below the
> commitment, [service credit terms]. MO Intelligence will publish the
> monthly availability figure to the client by the [N]th working day of the
> following month.

The clause is a draft written from the design decision. The bracketed
terms and the final wording are `TO CONFIRM (organisational)` and need
legal review.

## What makes 99.5 % credible (artifacts — IaC written, not applied)

* Multi-AZ RDS with automatic failover in production —
  `infra/terraform/modules/rds/main.tf`, `infra/terraform/envs/production/main.tf`
  (SEC-004, TF-21)
* At least 2 tasks on at least 2 nodes in 2 AZs, target-tracking
  autoscaling with a hard max cap, ALB + container health checks,
  deployment circuit breaker with rollback —
  `infra/terraform/modules/ecs-app/main.tf` (TF-62, TF-70, TF-73, TF-74)
* One NAT gateway per AZ in production (TF-12)
* Resumable, checkpointed ingestion jobs (a crash loses no completed stage)
  — `IngestionJobUseCaseTest`, README "Resumable ingestion jobs"
* Graceful LLM degradation (an LLM outage never takes the app down) —
  `CompositeDocumentParserTest` et al.
* Edge rate caps absorb volumetric abuse — `infra/terraform/modules/edge/main.tf`
  ([ddos-runbook.md](../compliance/policies/ddos-runbook.md))
* Alarms on 5xx burst, CPU saturation, storage exhaustion → SNS —
  `infra/terraform/modules/security-monitoring/main.tf` (TF-95; see DEF-2:
  the topic's AWS managed key would stop alarm delivery); application
  security alerts (immediate + windowed rules over the audit trail)
  delivered to the log, a webhook and e-mail with an `AlertLog` delivery
  record — ws18, `AlertingGateD1Test`,
  [alerting-and-export-caps.md](alerting-and-export-caps.md)

## Status page & probes — plan and current state

1. **Probe (app side: done).** `GET /api/probe/synthetic` is public by
   design (a monitor holds no credentials), rate-limited per client IP
   (60/min, `MODOCUMENT_PROBE_RATE_LIMIT`), answers only the build version
   and a database boolean, and travels the whole CloudFront → ALB → nginx →
   app → PostgreSQL path
   (`src/main/java/com/modocument/api/controller/SyntheticProbeController.java`;
   `SyntheticProbeTest` — 3 tests passed on 2026-10-19,
   [A8-infra-checks.md](../compliance/evidence/A8-infra-checks.md) §2;
   `ProductionBootPgIT` proves it on real PostgreSQL). Per-IP limits see the
   real client: nginx runs in trusted-proxy mode behind the ALB and, in
   production, takes the client address from `CloudFront-Viewer-Address`
   (`infra/terraform/README.md`, "Proxy chain"), so probes from different
   monitor locations get separate buckets.
2. **Probe (monitor side: not built).** No Route 53 health check,
   CloudWatch Synthetics canary or external monitoring service exists in
   Terraform or in the repository (TF-96). Choice of monitor and probe
   locations: `TO CONFIRM (organisational)`. A Route 53 health check or a
   Synthetics canary from outside af-south-1 would also give CloudWatch
   the probe metric the monthly figure is computed from.
3. **Status page (not built).** A public static page, hosted separately
   from the app so it survives app outages, showing current state and
   incident history, updated by Rushal during incidents
   ([incident-response-stub.md](incident-response-stub.md)). Candidate: an
   S3+CloudFront static site or a hosted status service. URL and service:
   `TO CONFIRM (organisational)`.
4. **In-app status communication (exists).** Platform admins publish
   INFO, MAINTENANCE (with its outage window, not dismissible) or RELEASE
   announcements to every bank or a list of banks; users see them as a
   banner (`docs/release/runbook.md` §8;
   `src/main/java/com/modocument/api/controller/AnnouncementController.java`;
   `frontend/src/components/AnnouncementBanner.jsx`;
   `src/test/java/com/modocument/api/AnnouncementApiTest.java`). This works
   only while the app is up, which is why the external status page is
   still needed.
5. **Internal alerting.** The SNS alert topic
   (`security_monitoring.alert_topic_arn` output) is the single seam for
   the infrastructure alarms; subscribing the on-call channel is an
   apply-time step (pending). Fix DEF-2 before relying on it. Application
   security alerts already have their own channels (ws18:
   `MODOCUMENT_ALERTS_WEBHOOK_URL` / `MODOCUMENT_ALERTS_EMAIL_TO`), which
   can point at the same on-call destination.
6. **Reporting.** A monthly availability report (template below), filed
   with the evidence pack (certification input, SEC-038) and sent to each
   bank.

## Monthly uptime report (template)

| Field | Value |
|---|---|
| Month | YYYY-MM |
| Probe source(s) and interval | |
| Probes total / successful / failed (outside maintenance) | |
| Measured availability | % (commitment 99.5 %) |
| Downtime minutes outside maintenance | |
| Error budget left (of ~219 minutes) | |
| Maintenance windows used (dates, minutes, announced on) | |
| Incidents (ID, minutes, link to the post-incident review) | |
| p95 latency of the probe | |
| Alarms fired / pages sent | |
| Commitment met? Service credit due? | |
| Prepared by / reviewed by / sent to banks on | |

Post-incident reviews use
[post-incident-review-template.md](../compliance/policies/post-incident-review-template.md).
No monthly report exists yet: there is no production and no monitor
(`NOT IN REPO (live cloud)`). The workbook asks for a 12-month measured
history; it can start only when production and the external monitor exist.

## Load validation

The autoscaling caps and the SLA's p95 latency assumptions are validated by
the gate **E7 load test**: the k6 scenarios exist ([`loadtest/`](../../loadtest/README.md),
ws17 — login/refresh, upload+ingest, job polling, export, mixed) but have
**not been executed** against AWS yet; the run targets the staging ALB
(`envs/staging` output `alb_dns_name`). Until that run, the capacity numbers
in the tfvars are engineering estimates. Stress testing is covered by the
SEC-002/SEC-003 pages of the compliance pack.
