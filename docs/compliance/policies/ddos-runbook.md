# DDoS and volumetric abuse runbook (SUP-S03)

| Item | Value |
|---|---|
| Control | SUP-S03 — DDoS and volumetric abuse protection (depends on SEC-001) |
| Owner | IT Infrastructure — Bhavish Nobeen; deputy Rushal (on call) |
| Version | 1.0, written 2026-10-19 by agent A8; human review required |
| Status | Edge design in code, **not deployed**; never exercised. Tabletop: annual (workbook) |

Fact IDs TF-nn refer to [A8-infra-checks.md](../evidence/A8-infra-checks.md).

## 1. Protection layers (production)

| Layer | What it does | Proof |
|---|---|---|
| L3/L4 | AWS Shield Standard protects CloudFront automatically (no configuration; no Shield Advanced, no AWS DDoS Response Team access) | TF-113; live state `NOT IN REPO (live cloud)` |
| Edge distribution | CloudFront absorbs traffic across all edge locations (`PriceClass_All`); static `/assets/*` served from cache | TF-84 |
| L7 managed rules | WAF: AWS common rule set, known bad inputs, Amazon IP reputation list | TF-80 |
| L7 rate rules | Block an IP above 2000 requests per 5 minutes overall, and above 100 per 5 minutes on `/api/auth/` | TF-81, TF-82; variables `rate_limit_per_5min`, `auth_rate_limit_per_5min` in `infra/terraform/modules/edge/variables.tf` |
| Origin hiding | ALB accepts 443 only from CloudFront origin-facing ranges and only with the secret origin header; others get 403 | TF-85 |
| Application | Per-IP and per-e-mail login limits (`src/main/java/com/modocument/infrastructure/ratelimit/AuthRateLimiter.java`), per-user limits on expensive endpoints (`src/main/java/com/modocument/infrastructure/ratelimit/ApiRateLimitInterceptor.java`, `src/test/java/com/modocument/api/ApiRateLimitIntegrationTest.java`), per-user LLM quota (`src/main/java/com/modocument/infrastructure/ratelimit/LlmQuotaInterceptor.java`), MFA attempt limits (`src/main/java/com/modocument/infrastructure/ratelimit/MfaRateLimiter.java`) | settings under `modocument.ratelimit` in `src/main/resources/application.yml` |
| Capacity cap | Autoscaling stops at 8 tasks / 4 nodes, so an attack cannot scale cost without limit | TF-74, TF-62 |

**Not covered by the edge:**

- the mTLS API listener (port 8443, off by default) is reached directly,
  not through CloudFront/WAF; only allow-listed bank CIDRs may connect
  (TF-75);
- staging has no CloudFront or WAF (TF-07);
- app-level counters are per task in memory, so N tasks allow up to N
  times each limit (`src/main/resources/application.yml` comment).

**Signals available:** WAF CloudWatch metrics per rule (`<name>-rate`,
`<name>-rate-auth`, ...) and sampled requests (TF-83); ALB 5xx alarm and
ECS CPU alarm to the SNS topic (TF-95). There are no WAF, ALB or CloudFront
access logs (DEF-9), and the alert topic has a delivery defect (DEF-2).

## 2. Detection triggers

Start this runbook when any of these happens:

1. ALB 5xx alarm or ECS CPU alarm fires (TF-95), or the external synthetic
   probe fails (see `docs/security/sla-and-status.md`).
2. WAF blocked-request metrics for `rate-cap` rise sharply.
3. Users or a bank report slowness or timeouts.
4. AWS notifies an event on the account (Personal Health Dashboard).

## 3. Response steps

| Step | Action | Who | Time target (proposed) |
|---|---|---|---|
| 1 | Acknowledge the alert; open an incident record (`docs/security/incident-response-stub.md`) | On call (Bhavish Nobeen or Rushal) | 15 min |
| 2 | Classify: volumetric (CloudFront request spike, Shield absorbs), application-layer (WAF rate metrics, specific paths), or origin bypass (traffic at the ALB without CloudFront) | On call | 30 min |
| 3 | Application layer: in the WAF console, lower the rate limit temporarily, add a rate rule scoped to the hot path, or add an IP set / geo block for the attacking sources. Then copy the change into `infra/terraform/modules/edge/main.tf` so the next apply does not undo it | Bhavish Nobeen | 1 h |
| 4 | Expensive endpoints: lower the matching app limit by environment variable (for example `MODOCUMENT_RATELIMIT_UPLOADS`) and redeploy with `deploy.yml` | Bhavish Nobeen | 2 h |
| 5 | Origin bypass: confirm the ALB security group still lists only the CloudFront prefix list and that the origin-verify rule is in place; if the header value leaked, replace `random_password.origin_verify` and apply | Bhavish Nobeen | 1 h |
| 6 | Capacity: if real users are affected and the attack is filtered, the max task cap can be raised temporarily (`max_tasks`, `max_instances`) — mind the cost | Bhavish Nobeen with Reezvi Pydiah's approval (cost) | as needed |
| 7 | Communicate: MAINTENANCE or INFO announcement in the app (`docs/release/runbook.md` §8); status page when it exists; tell affected banks | Rushal | 1 h, then hourly |
| 8 | After the event: remove temporary rules that are no longer needed; post-incident review ([post-incident-review-template.md](post-incident-review-template.md)) | Owner | 5 working days |

AWS support plan level, and whether a Shield Advanced subscription is
needed for the bank contract: `TO CONFIRM (organisational)`.

## 4. Tests

| Test | Status |
|---|---|
| Origin unreachable directly from the internet (call the ALB DNS name without the header → 403; from a non-CloudFront IP → connection refused) | Not run — `NOT IN REPO (live cloud)` |
| Synthetic L7 flood throttled (load generator above 2000 requests / 5 min from one IP → HTTP 403 from WAF) | Not run — `NOT IN REPO (live cloud)`; the k6 scenarios in `loadtest/` target the staging ALB, which has no WAF |
| App limits answer 429 | Covered by `src/test/java/com/modocument/api/ApiRateLimitIntegrationTest.java` (repository test) |
| Tabletop of this runbook | Not run; proposed within 30 days of go-live |
