# SLA & status page — SEC-005 / SUP-S11

Last reviewed: 2026-10-04 (workstream 20).

## Commitment

| Term | Value |
|---|---|
| Availability | **99.5 %** monthly, measured at the public endpoint (CloudFront/ALB), excluding the maintenance window |
| Allowed downtime at 99.5 % | ~3 h 39 m per month |
| Maintenance window | **Sundays 07:00–09:00 Mauritius time (UTC+4)** — wraps the IaC's UTC windows (RDS backup 01:00–02:00 UTC, RDS maintenance Sun 03:00–04:00 UTC); announce to banks in local time |
| Measurement | External HTTP probe on `GET /actuator/health` through the public entry point, 1-minute interval; a month's availability = successful probes / total probes outside the window |
| RPO | ≤ 24 h from vault recovery points; ≤ 5 min via RDS PITR (within the 35-day window) |
| RTO | Target 4 h (validated by the gate D6 restore drill — pending) |

## What makes 99.5 % credible (artifacts)

* Multi-AZ RDS with automatic failover — `infra/terraform/modules/rds` (SEC-004)
* 2 tasks across 2 AZs minimum, target-tracking autoscaling with a hard max
  cap, ALB + container health checks — `infra/terraform/modules/ecs-app`
* Resumable, checkpointed ingestion jobs (a crash loses no completed stage)
  — `IngestionJobUseCaseTest`, README "Resumable ingestion jobs"
* Graceful LLM degradation (an LLM outage never takes the app down) —
  `CompositeDocumentParserTest` et al.
* Edge rate caps absorb volumetric abuse — `infra/terraform/modules/edge`
* Alarms on 5xx burst, CPU saturation, storage exhaustion → SNS —
  `infra/terraform/modules/security-monitoring` (channels: ws18)

## Status page & probes — plan

1. **Probe**: Route 53 health check (or an external probe service) against
   `https://<app domain>/actuator/health` — the endpoint is deliberately
   unauthenticated (README production checklist) and already used by the
   container healthcheck. CloudWatch stores the probe metric; the monthly
   SLA number is computed from it.
2. **Status page**: a public static page (separate hosting from the app, so
   it survives app outages) showing current state + incident history,
   updated by Rushal Seeruthun during incidents
   ([incident-response-stub.md](incident-response-stub.md)); candidate:
   S3+CloudFront static site or a hosted status service — decide with the
   E5 mailbox setup.
3. **Internal**: the SNS alert topic
   (`security_monitoring.alert_topic_arn` output) is the single seam — ws18
   subscribes the real channels; the ALB 5xx alarm's OK action posts
   recovery.
4. **Reporting**: monthly availability figure archived with the evidence
   pack (certification input, SEC-038).

## Load validation

The autoscaling caps and the SLA's p95 latency assumptions are validated by
the gate **E7 load test** (k6 scripts from ws17, run against the staging ALB
— `envs/staging` output `alb_dns_name`). Until that run, the capacity
numbers in the tfvars are engineering estimates.
