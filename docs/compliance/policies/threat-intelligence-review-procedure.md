# Weekly threat-intelligence review — procedure and note template (SUP-S12)

| Field | Value |
|---|---|
| Version | 0.1 draft, 2026-10-19 |
| Owner | Security — Bhavish Nobeen; deputy Rushal (MO-001 RACI, deputy per item) |
| Approver | Reezvi Pydiah — pending |
| Frequency | Weekly, Monday (02 workbook SUP-S12 Evidence Register: "weekly intel review") |
| Output | One review note per week, filed as `docs/compliance/evidence/threat-intel/<YYYY>-W<ww>.md` (proposed location) |
| First note | `docs/compliance/evidence/A5-threat-intel-review-note-2026-W43.md` |

## 1. Why Monday

The automated feeds already run on Monday morning (UTC):

| Feed | When | Where it lands | Proof |
|---|---|---|---|
| Dependabot version updates (Maven `/`, `/connector-core`, `/agent`; npm `/frontend`; GitHub Actions; Docker; Terraform) | weekly, Monday | Pull requests | `.github/dependabot.yml` |
| Weekly image rebuild + Trivy (fixable HIGH/CRITICAL) | Monday 04:41 | Issue "Security: base image refresh" | `.github/workflows/image-rebuild.yml` |
| Security workflow (gitleaks, Semgrep, npm audit) on `main` | Monday 05:23 | Actions run | `.github/workflows/security.yml` |
| ZAP baseline | Tuesday 05:37 | Actions run artifact | `.github/workflows/zap-baseline.yml` |

## 2. Steps (about 30 minutes)

1. **Check the robots ran.** Open the latest run of each workflow above. A
   run that did not start or ended in seconds is itself a finding (it means
   no scanning happened that week).
2. **Image findings.** Read the "Security: base image refresh" issue. Every
   CRITICAL older than 72 hours and every HIGH older than 14 days is
   overdue; record it.
3. **Dependabot.** List open Dependabot PRs. Merge or schedule security
   updates first; note any PR older than 14 days and why.
4. **GitHub security advisories / Dependabot alerts** for the repository
   (Security tab). Whether alerts are enabled for this private repository
   is `TO CONFIRM (organisational)`.
5. **External advisories** for the stack named in `pom.xml`,
   `frontend/package.json`, `Dockerfile` and `infra/terraform/`: Spring
   (Boot, Framework, Security, AI), Apache Tomcat, Jackson, PostgreSQL and its
   JDBC driver, nginx, Node/Vite/React, Eclipse Temurin, AWS. Sources:
   vendor security pages, GitHub Advisory Database, CISA Known Exploited
   Vulnerabilities, and CERT-MU advisories (the workbook action is "subscribe
   to CERT-MU advisories"; subscription status `TO CONFIRM
   (organisational)`).
6. **Decide.** For each relevant item: affected yes/no, severity, action,
   owner, due date (SLAs: Critical ≤ 72 h, High ≤ 14 d, Medium ≤ 30 d). An
   item that will not be fixed in time needs an accepted-risk entry
   (`.trivyignore` with expiry, approver Reezvi Pydiah).
7. **File the note** using the template below and link it from the next
   monthly vulnerability assessment report.

## 3. Note template

```markdown
# Threat-intelligence review — <YYYY>-W<ww> (<date>)

Reviewer: <name> · Deputy check: <name or "none"> · Time spent: <minutes>

## Robots
| Feed | Last run | Result | Finding? |
|---|---|---|---|
| Security workflow | | | |
| Weekly image rebuild + Trivy | | | |
| ZAP baseline | | | |
| Dependabot PRs open | <count> | | |

## New or changed items
| Source | Item (CVE / advisory) | Affects us? | Severity | Action | Owner | Due |
|---|---|---|---|---|---|---|

## Overdue against SLA
| Item | Severity | First seen | Due | Why late | New date |
|---|---|---|---|---|---|

## Decisions and escalations
- <none / item → Reezvi Pydiah for risk acceptance / incident opened>
```
