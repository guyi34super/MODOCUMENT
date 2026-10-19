# Patch, database and OS update policy (SEC-026)

| Item | Value |
|---|---|
| Control | SEC-026 — Patch, database and OS update process |
| Owner | IT Infrastructure — Bhavish Nobeen (process); Software Engineering for application dependencies |
| Version | 1.0 **proposed**, written 2026-10-19 by agent A8; requires management approval |
| Review | Annually, and after any emergency patch |
| Depends on | SEC-025 change pipeline (dependency D003 in the workbook) |

## 1. Scope: who patches each layer

| Layer | Patched by | How | Proof in the repo |
|---|---|---|---|
| Application dependencies (Maven: app, `connector-core`, `agent`; npm: `frontend`) | MO | Dependabot weekly PRs (Monday), minor+patch grouped, majors separate, 7-day cooldown on version updates; **security updates arrive immediately**, not subject to the cooldown | `.github/dependabot.yml` |
| CI actions | MO | Dependabot weekly; actions pinned by commit SHA | `.github/dependabot.yml`; `.github/workflows/image-rebuild.yml` |
| Container base images (`maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jre`, `node:22-alpine`, `nginxinc/nginx-unprivileged:alpine`, agent `gcr.io/distroless/java21-debian12:nonroot`) | MO | Dependabot `docker` ecosystem weekly; **weekly rebuild** with `--pull` and Trivy scan every Monday 04:41 UTC; an issue "Security: base image refresh" opens on fixable HIGH/CRITICAL; the run stays red while a fixable CRITICAL remains | `Dockerfile`; `frontend/Dockerfile`; `agent/Dockerfile`; `.github/workflows/image-rebuild.yml` |
| Release gate | MO | Every release image is Trivy-scanned; a fixable HIGH or CRITICAL fails the release | `.github/workflows/release.yml` (`exit-code: '1'`) |
| Terraform providers | MO | Dependabot `terraform` ecosystem weekly; each bump needs a reviewed `terraform plan` | `.github/dependabot.yml` |
| ECS node OS and ECS agent | MO applies, AWS publishes | Nodes use the AWS ECS-optimised Amazon Linux 2023 AMI, read from the AWS "recommended" SSM parameter at plan time. A new AMI reaches the launch template on the next `terraform apply`; running nodes are replaced only by an instance refresh (manual today) | `infra/terraform/modules/ecs-app/main.tf` (`data.aws_ssm_parameter.ecs_ami`, `aws_launch_template.this`, `aws_autoscaling_group.this`) — DEF-10 |
| PostgreSQL minor versions | AWS, in MO's window | `auto_minor_version_upgrade = true`, `maintenance_window = "sun:03:00-sun:04:00"` UTC, `apply_immediately = false` | `infra/terraform/modules/rds/main.tf` — see DEF-13 |
| PostgreSQL major versions | MO | Change `engine_version` and the parameter-group family in Terraform, rehearse in staging, apply in the maintenance window | `infra/terraform/modules/rds/main.tf`, `infra/terraform/modules/rds/variables.tf` |
| Managed services (ALB, CloudFront, WAF managed rules, Secrets Manager, KMS, S3) | AWS | AWS shared-responsibility model | — |
| Staff laptops | MO | See [endpoint-device-policy.md](endpoint-device-policy.md) | `TO CONFIRM (organisational)` |

DEF-10 and DEF-13 are in [A8-infra-checks.md](../evidence/A8-infra-checks.md) §4.

## 2. Remediation time limits

| Severity (CVSS / vendor) | Limit from fix availability | Status |
|---|---|---|
| Critical | **72 hours** to production, as a patch release | In force in `docs/release/runbook.md` §6. The 2 Oct 2026 design statement says 24 hours: `TO CONFIRM (organisational)` which value is committed to the bank. |
| High | 30 days | **proposed** |
| Medium | Next monthly cycle (≤ 30 days) | **proposed** |
| Low | Next monthly cycle or accepted with a reason | **proposed** |

A finding with no fix available is tracked in the monthly report with a
compensating control; a release-gate exception needs a reviewed
`.trivyignore` entry with an expiry (`.github/workflows/release.yml`
comment).

## 3. Routine monthly cycle (proposed)

| When | What |
|---|---|
| Every Monday | Dependabot PRs; weekly image rebuild and Trivy scan |
| First week of the month | Merge the grouped updates after CI (`ci.yml`, `security.yml`); cut a patch release; staging soak per `docs/release/runbook.md` §3 |
| Second Sunday, 07:00–09:00 Mauritius time (UTC+4) | Production patch release; node instance refresh to the newest ECS AMI; RDS minor upgrades run in their own window (Sunday 03:00–04:00 UTC, same block). Announce with a MAINTENANCE announcement (`docs/release/runbook.md` §8) |
| By the 5th working day of the next month | Monthly patch report (§5) |

The maintenance window is the one in `docs/security/sla-and-status.md`.

## 4. Emergency-patch runbook

Trigger: a Critical vulnerability with a fix, in any layer of §1, or a
known-exploited vulnerability of any severity.

1. **Record** (hour 0): open an issue titled `EMERGENCY PATCH <CVE>` with
   the affected layer, version and fix version. Owner: Bhavish Nobeen;
   deputy: Rushal.
2. **Assess** (≤ 4 h): confirm the vulnerable code is present (Trivy JSON,
   SBOM from `.github/workflows/sbom.yml`, dependency tree). If it is
   exploitable from the internet, also consider a WAF rule or a feature
   switch-off as an interim block.
3. **Fix**:
   - application or base image: hotfix branch from the release tag,
     `scripts/bump-version.sh X.Y.Z+1`, tag `vX.Y.Z+1-rc.1`
     (`docs/release/runbook.md` §5);
   - node OS: apply Terraform to pick up the newest AMI, then start an
     Auto Scaling instance refresh;
   - database engine: apply the minor version through Terraform with
     `apply_immediately` for that one change, outside business hours.
4. **Test**: CI green; shortened staging soak of at least 4 hours
   (`docs/release/runbook.md` §5).
5. **Deploy**: production approval by two reviewers once the GitHub
   environment is configured (`docs/release/runbook.md` §7; the setting
   itself is `TO CONFIRM (organisational)`); the circuit breaker rolls back a failing
   deployment (`infra/terraform/modules/ecs-app/main.tf`).
6. **Verify**: re-scan the deployed image digest; check the version on
   `GET /api/system/version`.
7. **Close** (≤ 72 h from step 1): record times in the issue and in the
   monthly report. If the limit is missed, record why and who accepted the
   delay.

## 5. Monthly patch report (template)

| Field | Content |
|---|---|
| Month | YYYY-MM |
| Releases shipped | version, date, reason |
| Dependabot | PRs opened / merged / open > 30 days |
| Weekly image rebuilds | runs, red runs, fixable CRITICAL / HIGH at month end |
| Release-gate exceptions | `.trivyignore` entries and expiry dates |
| Node AMI | AMI id in use, date of last instance refresh |
| RDS | engine version, last minor upgrade, pending maintenance |
| Emergency patches | CVE, opened, deployed, hours taken, within limit? |
| Open exceptions | finding, compensating control, owner, review date |
| Prepared by / reviewed by | |

The first report, covering the repository layers only, is
[A8-patch-report-2026-10.md](../evidence/A8-patch-report-2026-10.md).
Node and database rows stay empty until AWS is deployed:
`NOT IN REPO (live cloud)`.
