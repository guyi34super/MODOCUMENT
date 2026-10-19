# Patch report — October 2026 (1–19 Oct), repository layers (SEC-026-E01)

First report in the format of
[patch-management-policy.md](../policies/patch-management-policy.md) §5.
Prepared by agent A8 on 2026-10-19; reviewer: Bhavish Nobeen (pending).
September 2026 had no patch process yet: Dependabot and the weekly image
rebuild were both added on 2026-10-04 (commit `e43719c`, from the history
of `.github/dependabot.yml` and `.github/workflows/image-rebuild.yml`).

## Sources and commands (run 2026-10-19)

| Data | Command |
|---|---|
| Weekly rebuild runs | `gh run list -R guyi34super/MODOCUMENT --workflow image-rebuild.yml --limit 10` |
| Why the 19 Oct run failed | `gh api repos/guyi34super/MODOCUMENT/check-runs/118005661631/annotations` |
| Tracking issue | `gh api "repos/guyi34super/MODOCUMENT/issues?state=all&per_page=100"` (title "Security: base image refresh"), then `gh api repos/guyi34super/MODOCUMENT/issues/52` |
| Dependabot PRs | `gh api "repos/guyi34super/MODOCUMENT/pulls?state=open&per_page=50"` and `state=closed&per_page=60`, filtered on author `dependabot[bot]` |
| Merged updates | `git log --since=2026-10-01 -i --grep=dependabot` |
| Current Spring Boot | `pom.xml` (`spring-boot-starter-parent`) |

## Report

| Field | Content |
|---|---|
| Month | 2026-10 (to the 19th) |
| Releases shipped | 1.0.0 and 1.1.0 on 2026-10-10, 1.2.0 on 2026-10-11 (`CHANGELOG.md`); no production exists, so nothing was deployed |
| Dependabot | Merged 2026-10-12: PR #27 (actions/checkout 7.0.1), #22 (actions/setup-java 6.0.1), #21 (npm minor/patch group, 4 updates), #18 (trivy-action 0.36.0); the Maven minor/patch group #34 was applied by hand except tess4j (commit `e1b0fb8`, `724c62f`). Open at report time: 16 Dependabot PRs (#41–#51 from 12 Oct, #67–#71 from 19 Oct), including major bumps (Spring Boot 4.1.1, Terraform AWS provider 6.x, Vite 8). Closed without merge: 21 PRs from 10–12 Oct, mostly superseded or majors. |
| Weekly image rebuilds | 2026-10-12: rebuilt and scanned; **app image 52 fixable HIGH/CRITICAL, of which 11 CRITICAL; frontend image 0**. Issue #52 "Security: base image refresh" opened, still **open**. 2026-10-19: **did not run** — GitHub annotation: "The job was not started because recent account payments have failed or your spending limit needs to be increased." |
| Critical findings (12 Oct) | Tomcat embed core 10.1.42 (6 CVEs), spring-security-web 6.5.1 (1), spring-webflux 6.2.8 (2), spring-webmvc 6.2.8 (2). These come with `spring-boot-starter-parent` 3.5.3, which `pom.xml` still declares on 2026-10-19. |
| Within the 72-hour limit? | **No.** 7 days have passed since issue #52 with no patch release. There is no production, so no live exposure, but the process limit in `docs/release/runbook.md` §6 was not met. |
| Release-gate exceptions | None found in the repository |
| Node AMI | `NOT IN REPO (live cloud)` — no nodes exist |
| RDS | `NOT IN REPO (live cloud)` — no instance exists |
| Emergency patches | None opened |
| Open exceptions | Issue #52 (owner: Bhavish Nobeen; action: Spring Boot upgrade carrying the fixed versions — Trivy lists Spring Framework fixes only in 7.0.9 — and a patch release); CI billing problem stopping scheduled scans (owner: `TO CONFIRM (organisational)`) |
