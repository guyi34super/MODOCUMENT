# SUP-S03 — DDoS and volumetric abuse protection

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 04 IT Infrastructure | IT Infrastructure & Operations | P2 | Partially Implemented | SUP-S03-E01 | SUP-S03-T01 | R-SUP-S03 |

## Requirement
Survive volumetric and application-layer floods without burning the SLA
(SEC-005). Acceptance: the origin is unreachable directly from the
internet, and a synthetic L7 flood is throttled in a test.

## How MoDocument meets it today
Pre-production; Terraform validated, not deployed. Fact IDs in
[A8-infra-checks.md](../evidence/A8-infra-checks.md).

- **Edge architecture** (production only): CloudFront in front of the ALB
  with AWS WAF attached; Shield Standard applies to CloudFront by AWS
  default (`infra/terraform/modules/edge/main.tf`, TF-84, TF-113).
- **WAF rules:** AWS common rule set, known bad inputs, Amazon IP
  reputation; per-IP rate rule 2000 requests / 5 min, and 100 / 5 min on
  `/api/auth/` (TF-80 to TF-82; `infra/terraform/modules/edge/variables.tf`).
- **Origin locked to CloudFront:** ALB SG admits 443 only from the
  CloudFront origin-facing prefix list; the listener forwards only
  requests with the secret origin header and answers 403 to the rest
  (TF-85). Port 80 only redirects (TF-72).
- **Application limits** under the edge: login per IP and per e-mail,
  per-user limits on expensive endpoints, LLM quota, MFA attempts, probe
  per IP (`src/main/java/com/modocument/infrastructure/ratelimit/AuthRateLimiter.java`;
  `src/main/java/com/modocument/infrastructure/ratelimit/ApiRateLimitInterceptor.java`;
  `src/main/java/com/modocument/infrastructure/ratelimit/LlmQuotaInterceptor.java`;
  `src/main/java/com/modocument/infrastructure/ratelimit/MfaRateLimiter.java`;
  `src/test/java/com/modocument/api/ApiRateLimitIntegrationTest.java`).
- **Bounded scale-out:** max 8 tasks on max 4 nodes (TF-62, TF-74).
- **Limits of the design:** the optional mTLS listener (8443) bypasses
  CloudFront/WAF and relies on its CIDR allow-list (TF-75); staging has no
  edge (TF-07); no WAF/ALB/CloudFront logs (DEF-9); alarm delivery defect
  (DEF-2).
- **DDoS runbook written:** [ddos-runbook.md](../policies/ddos-runbook.md).

## Evidence SUP-S03-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Edge architecture note | [security-architecture.md](../policies/security-architecture.md) §2–§3; [ddos-runbook.md](../policies/ddos-runbook.md) §1 | repo |
| WAF and rate configuration | `infra/terraform/modules/edge/main.tf`; `infra/terraform/modules/edge/variables.tf` | repo |
| App rate limits | `src/main/resources/application.yml` (`modocument.ratelimit`); `src/test/java/com/modocument/api/ApiRateLimitIntegrationTest.java` | repo |
| DDoS runbook | [ddos-runbook.md](../policies/ddos-runbook.md) | repo |
| WAF metrics, origin-bypass test, flood test | — | `NOT IN REPO (live cloud)` |

## Test SUP-S03-T01
- **Procedure:** (1) static: confirm in code the prefix-list ingress,
  origin-header rule, 403 default and WAF rate rules; validate Terraform;
  (2) acceptance: call the ALB directly (expect refused / 403) and run a
  single-IP flood above 2000 requests per 5 minutes through CloudFront
  (expect WAF 403).
- **Expected result:** origin unreachable directly from the internet;
  synthetic L7 flood throttled.
- **Actual result:** (1) all present; validate passed (2026-10-19);
  (2) not run — no AWS environment; the staging ALB has no WAF, so the k6
  load scenarios cannot test the WAF there.
- **Result:** Partial.
- **Tester:** Agent A8 (static re-performance); human sign-off required.
- **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| Edge not deployed; tests not run | Config | Deploy production edge; run the origin-bypass and flood tests | IT Infrastructure — Bhavish Nobeen | Pre-go-live, date to set | Day 60 |
| No WAF/ALB/CloudFront logs (DEF-9) | Config | Enable logging | Bhavish Nobeen | with the first apply | Day 60 |
| Runbook | Doc | Written; tabletop within 30 days of go-live | Bhavish Nobeen | 2026-10-19 (written) | Done |
| Shield Advanced / AWS support level | Org | Decide | `TO CONFIRM (organisational)` | — | Day 90 |

## Risk R-SUP-S03
- **Risk:** a rentable booter takes the service offline during business
  hours.
- **Cause:** origin directly reachable, bypassing the edge.
- **Mitigation:** origin lock (prefix list + secret header), WAF rate
  rules, app limits, runbook. Likelihood, impact and residual are set in
  MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: WAF metrics; origin-bypass and flood test results; Shield Standard coverage in the account.
- `TO CONFIRM (organisational)`: Shield Advanced and AWS support plan.
