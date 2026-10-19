# Degradation runbook — peak load and overload

| Document | Control | Owner | Reviewer | Version | Date |
|---|---|---|---|---|---|
| Degradation runbook | SEC-002 (with SEC-003, SEC-008) | IT Infrastructure — Bhavish Nobeen | Rushal | 1.0 | 2026-10-19 |

This runbook says what MoDocument does by itself when load or a dependency goes wrong, which
signals show it, and what the on-call person does at each level. It is written from the code
and the Terraform in this repository. Every limit it names is measured in
[MO-017 — Stress Testing Report](../mo-017-stress-testing-report.md).

MoDocument is **pre-production**: nothing is deployed yet, so the cloud-side steps (scaling,
WAF, alarms) are the design in `infra/terraform`, `NOT IN REPO (live cloud)` as operated
evidence.

## 1. Built-in protections (no operator action needed)

| Layer | What happens under overload | Setting | Proof |
|---|---|---|---|
| Edge (WAF) | A source IP above 2,000 requests per 5 minutes is blocked; `/api/auth/*` above 100 per 5 minutes | `rate_limit_per_5min`, `auth_rate_limit_per_5min` | `infra/terraform/modules/edge/main.tf`, `infra/terraform/modules/edge/variables.tf` |
| Per-user API buckets | Expensive calls (uploads 20/h, ingestion 60/h, exports 60/h, document generation 30/h, extraction 30/h, DSR 10/h, …) answer **429** with `Retry-After`; other users are not affected | `modocument.ratelimit.*`, bank settings `ratelimit.<bucket>` | `src/main/java/com/modocument/infrastructure/ratelimit/ApiRateLimitInterceptor.java`, `src/main/resources/application.yml`, `docs/settings/catalogue.md`, `ApiRateLimitIntegrationTest` |
| Sign-in throttles | Per IP and per e-mail fixed windows, 429 with `Retry-After` before any password hashing | `ratelimit.auth.*`, `ratelimit.mfa.*` | `src/main/java/com/modocument/infrastructure/ratelimit/AuthRateLimiter.java` |
| LLM quota | 60 model-spending calls per user per hour | `ratelimit.llm.perUserMax` | `src/main/java/com/modocument/infrastructure/ratelimit/LlmRateLimiter.java` |
| AI circuit breaker | After 5 consecutive transport/5xx/timeout failures the breaker opens for 2 minutes; ingestion falls back to code (`CIRCUIT_OPEN`), the job still completes; one trial call then closes it | `ai.circuitBreaker.failureThreshold`, `ai.circuitBreaker.openDuration` | `src/main/java/com/modocument/infrastructure/ai/AiCircuitBreaker.java`, `docs/settings/catalogue.md` |
| AI timeouts and retries | Model read timeout 120 s, one retry on 5xx only, no retry on timeouts | `MODOCUMENT_AI_READ_TIMEOUT`, `MODOCUMENT_AI_RETRY_MAX_ATTEMPTS` | `src/main/resources/application.yml` |
| Database pool | At most 20 connections per instance; a request waits up to 10 s for one, then fails | `MODOCUMENT_DB_POOL_MAX`, `connection-timeout` | `src/main/resources/application.yml` |
| Ingestion pipeline | Stages run on a fixed pool of 2 background threads; further jobs queue in memory | — (code) | `src/main/java/com/modocument/infrastructure/ingestion/IngestionJobRunner.java` |
| Export caps | Exports above the role's row cap (READ_WRITE 5,000) are refused unless a reason is given | `MODOCUMENT_EXPORT_CAP_*` | `src/main/resources/application.yml`, `ExportCapGateD2Test` |
| Horizontal scaling | ECS service target tracking at 60 % average CPU, 2 to 8 tasks (hard cap), scale-out cooldown 60 s, scale-in 300 s; the ASG grows 2 to 4 nodes | `min_tasks`, `max_tasks`, `min_instances`, `max_instances` | `infra/terraform/modules/ecs-app/main.tf`, `infra/terraform/envs/production/variables.tf` |
| Process health | Out of memory → the JVM exits (`-XX:+ExitOnOutOfMemoryError`) and ECS replaces the task; a container failing `/actuator/health` 3 times is replaced | — | `docker-entrypoint.sh`, `infra/terraform/modules/ecs-app/main.tf` |
| Bad release | ECS deployment circuit breaker rolls back a release whose tasks do not become healthy | `deployment_circuit_breaker` | `infra/terraform/modules/ecs-app/main.tf` |

## 1b. Measured limits (test box, 2026-10-19, MO-017)

| Quantity | One CPU (≈ one task) | 4 shared CPUs | Use in operations |
|---|---|---|---|
| Assumed 1× / 2× peak (50 / 100 users) | 0 errors; JVM CPU 53 % / 73 % | 0 errors; JVM CPU 36 % / 81 % | One task crosses the 60 % scaling target between 1× and 2× |
| Request capacity within thresholds | ≈ 236 req/s (100 fast users) | ≈ 715 req/s (300 fast users) | Above this, expect latency (L2), not errors |
| Latency breaking point | 150 fast users | 400 fast users | — |
| Document generation | saturates at 15–17 flows/s | 24 flows/s at 40 users, not saturated | Generation is CPU-bound: watch CPU, not the pool |
| DB pool | at its 20-connection maximum from 2× peak; no connection timeouts, no errors | same | Pool timeouts in the log mean L2 |
| JVM resident memory | up to 2,442 MiB | up to 2,656 MiB | Container limit 2,816 MiB: memory alarms matter (MO-017 F-04) |

These are test-box figures (shared machine, database on the same host); the staging run
replaces them before go-live.

## 2. Signals

| Signal | Source | Meaning |
|---|---|---|
| `alb-5xx` alarm (> 25 target 5xx in 5 minutes) | `infra/terraform/modules/security-monitoring/main.tf` | Users are getting errors |
| `ecs-cpu-high` alarm (average CPU > 85 % for 15 minutes) | `infra/terraform/modules/security-monitoring/main.tf` | Scaling is at its cap or not keeping up |
| `ai-circuit-opened` alert (HIGH, immediate) | `src/main/resources/application.yml` (alert rules) | The model endpoint is failing; code fallbacks in use |
| Synthetic probe `GET /api/probe/synthetic` not 200 | `docs/security/sla-and-status.md` | App or database unreachable from outside |
| Hikari leak-detection WARN (connection held > 30 s) or "Connection is not available, request timed out" in the app log | `src/main/resources/application.yml` (`leak-detection-threshold`) | Database pool saturated or a slow query |
| Many 429 answers to one user or IP | App log, WAF metrics `*-rate` | Abuse, a scripted client, or a limit too low for real work |
| p95 latency per route above the MO-016 thresholds | Load-test reports; production APM is `NOT IN REPO (live cloud)` | Degradation before errors |

## 3. Levels and actions

| Level | Condition | Automatic behaviour | Operator action |
|---|---|---|---|
| **L0 Normal** | CPU < 60 %, no alarms | — | None |
| **L1 Elevated** | CPU ≥ 60 %; tasks scaling out | Target tracking adds tasks (≤ 8) | Watch the ECS service events; confirm new tasks become healthy within the 180 s grace period |
| **L2 Saturated** | `ecs-cpu-high` at `max_tasks`, or p95 above threshold, or pool timeouts | Rate limits keep shedding the expensive calls; requests queue | 1. Confirm it is real demand, not abuse (WAF metrics, 429 counts by user). 2. If real: raise `max_tasks` / `max_instances` through Terraform (precondition checks the cap is placeable) — this is a change under the release process (`docs/release/runbook.md`). 3. If one bank's users drive it: lower that bank's expensive buckets (`ratelimit.<bucket>`, maker/checker). 4. Tell users through an announcement that report generation and large uploads may be slow. |
| **L3 Dependency down** | `ai-circuit-opened`; or RDS failover | AI calls refused at once; ingestion completes by code (`parsedBy CODE`); RDS Multi-AZ fails over | AI: nothing to do for availability; check the model endpoint; jobs with low confidence are flagged for review. Database: wait for failover, then check the probe and pool errors clear (Hikari reconnects). |
| **L4 Overload or abuse** | 5xx burst; one IP or user dominates | WAF blocks the IP above its rate; per-user buckets answer 429 | Block the source in WAF if needed; disable the account through the JML process (`docs/security/jml-runbook.md`); raise an incident (`docs/security/incident-response-stub.md`). |

## 4. Recovery check

After any L2–L4 event:

1. The probe answers 200 for 10 minutes in a row.
2. The `alb-5xx` alarm returns to OK (its OK action posts recovery).
3. No ingestion job is left `RUNNING` without progress; resume stuck jobs from the job page
   (`POST /api/ingestion/jobs/{id}/resume`), which picks up from the last checkpoint.
4. Write the incident record with the timeline and the peak figures.

## 5. Known limits (from MO-017) and planned changes

| Limit | Effect | Planned change | Owner | Target |
|---|---|---|---|---|
| Report generation is synchronous (no queue) | Under heavy concurrent generation every request holds a server thread and a CPU for its full duration | Queue generation (bounded executor + 429/202 when full), as the workbook asks ("queue report generation under load") | Software Engineering — Bhavish Nobeen | 2026-12-15 (proposed) |
| Ingestion runner: 2 threads, unbounded in-memory queue | Concurrent uploads wait in line; jobs queued at a restart are lost from memory (they stay resumable) | Bounded queue with a 429 when full, pool size as a setting | Software Engineering — Bhavish Nobeen | 2026-12-15 (proposed) |
| Rate-limit counters are in memory per instance | N tasks allow N × the limit | Shared counter store before scaling past the minimum task count | Software Engineering — Bhavish Nobeen | 2027-01-31 (proposed) |
| `?wait=true` long polls are not metered | Each holds a server thread up to 900 s | Cap concurrent waits per user | Software Engineering — Bhavish Nobeen | 2026-12-15 (proposed) |

The measured breaking points, the bottleneck analysis and the full list of findings are in
[MO-017](../mo-017-stress-testing-report.md) §6 and §7.

## To confirm

- `NOT IN REPO (live cloud)`: applied WAF rules, scaling policy and alarms; an observed scale-out event.
- `TO CONFIRM (organisational)`: the on-call rota and the channel subscribed to the SNS alert topic.
