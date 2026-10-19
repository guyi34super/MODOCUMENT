# Threat-intelligence review — 2026-W43 (2026-10-19)

First note made with `docs/compliance/policies/threat-intelligence-review-procedure.md`.

Reviewer: agent A5 for the owner, Security — Bhavish Nobeen (automated
re-performance; human review required) · Deputy check: none · Sources read
through the GitHub API and the repository; external advisory sites were not
reachable from the sandbox.

## Robots

| Feed | Last run | Result | Finding? |
|---|---|---|---|
| Security workflow (`.github/workflows/security.yml`) | 2026-10-19, scheduled (run 39241049111) | All jobs failed within 2–3 s; no scan ran. Last successful run on `main`: 2026-10-12 | **Yes** — no CI scanning since at least 2026-10-17 (P-01) |
| Weekly image rebuild + Trivy (`.github/workflows/image-rebuild.yml`) | 2026-10-19 (run 39236177050) | Failed in 4 s; no scan ran. Previous run 2026-10-12 opened issue #52 | **Yes** — see below |
| ZAP baseline (`.github/workflows/zap-baseline.yml`) | 2026-10-13 (run 38400815763) | FAIL 0, WARN 1 (COEP header), PASS 65 | Yes, Low (V-03) |
| Dependabot PRs open | 16 (#41–#51, #67–#71) | Oldest from 2026-10-12 | Several majors waiting (Spring AI 2.0.1, Spring Boot 4.1.1 for `connector-core`, Vite 8, Vitest 5, actions bumps) |

## New or changed items

| Source | Item | Affects us? | Severity | Action | Owner | Due |
|---|---|---|---|---|---|---|
| Trivy via issue #52 (2026-10-12) | 11 critical CVEs in `tomcat-embed-core` 10.1.42, `spring-security-web` 6.5.1, `spring-webmvc`/`spring-webflux` 6.2.8 | Yes — the current `pom.xml` still resolves these versions (`mvn dependency:list`, 2026-10-19) | Critical | Upgrade the Spring Boot parent from 3.5.3 to the latest 3.5.x; assess the CVEs Trivy lists as fixed only in Spring Framework 7 | Bhavish Nobeen | 2026-10-22 (proposed) |
| Trivy via issue #52 | 41 high CVEs (Jackson, PostgreSQL JDBC, mssql-jdbc, httpcore5, micrometer, Spring AI 1.0.1, Go stdlib in an image binary, more Tomcat/Spring) | Yes, same check | High | Same upgrade; Spring AI ≥ 1.0.7; rebuild image | Bhavish Nobeen | 2026-10-26 |
| `npm audit` (frontend, e2e), 2026-10-19 | none | — | — | — | — | — |
| CERT-MU advisories | not reviewed | `TO CONFIRM (organisational)`: subscription not evidenced | — | Subscribe and add to this review | Bhavish Nobeen | 2026-10-30 (proposed) |

## Overdue against SLA

| Item | Severity | First seen | Due | Why late | New date |
|---|---|---|---|---|---|
| V-01 (11 critical library CVEs) | Critical | 2026-10-12 | 2026-10-15 | Not recorded in the repository; CI not running since at least 2026-10-17 | 2026-10-22 (proposed) |

## Decisions and escalations

- Escalate V-01 to Reezvi Pydiah: the 72-hour SLA is breached; either the
  upgrade lands by the new date or a written risk acceptance is needed
  (proposed).
- Restore GitHub Actions runners before the next review (P-01).
