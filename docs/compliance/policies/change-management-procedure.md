# Change management procedure (SEC-025)

| Field | Value |
|---|---|
| Control | SEC-025 Formal change management with change history (workbook 03) |
| Owner | Software Engineering — Bhavish Nobeen |
| Approver | Reezvi Pydiah (security documents, decision D2 in docs/compliance/mo-001-governance-structure.md §4). Proposed — confirm. |
| Status | Draft 1.0, 2026-10-19. Not yet approved. |
| Review | Every 12 months, after any emergency change (within 48 hours), and when the pipeline changes. |

This procedure says how every change to MoDocument production is requested, approved, tested, deployed, reversed and recorded. It covers code, configuration (in-app settings), infrastructure (Terraform) and database schema. It describes the mechanisms that exist in the repository and names the parts that are GitHub or AWS settings, which the repository cannot prove.

Roles follow the governance RACI and approval matrix in docs/compliance/mo-001-governance-structure.md (written in parallel by agent A1; decisions D6 production release, D7 code merge, D8 security-setting change, D12 accepted vulnerability, D19 egress allow-list).

## 1. Change types

| Type | What it covers | Approval | Testing | Record |
|---|---|---|---|---|
| **Normal** | Any code, Terraform, migration or workflow change; a new release | Code: a second person approves the pull request (D7). Release: a reviewer of the GitHub `production` environment who did not push the tag (D6). | All CI jobs green (`.github/workflows/ci.yml`, `.github/workflows/security.yml`); release pipeline tests, Trivy and smoke tests (`.github/workflows/release.yml`); 48-hour staging soak (`docs/release/runbook.md` §3) | Pull request (template `.github/pull_request_template.md`), merge commit, `CHANGELOG.md`, GitHub Release, deployment run, `VERSION_DEPLOYED` audit event |
| **Standard** (pre-approved, low risk) | (a) Dependabot minor and patch updates (`.github/dependabot.yml`), (b) OPERATIONAL-tier settings, (c) documentation-only changes | (a) and (c): a normal PR review, no separate change approval. (b): none beyond the `settings:write` or `platform:settings` permission; the change applies at once. | (a) and (c): CI. (b): the setting's hard limits (`docs/settings/catalogue.md`, `SettingsGuardrailTest`). | (a), (c): PR. (b): audit `SETTING_UPDATED` with old and new value. |
| **Security configuration** | SECURITY-tier settings, bank bounds, user/role changes, integration connections, API clients, agents, egress rules | Maker/checker in the product: a different user with `change:approve` approves the pending change; self-approval returns 409 (D8, D10, D19) | Server-side validation and hard limits | `PendingChange` row plus audit `CHANGE_REQUESTED` / `CHANGE_APPROVED` / `SECURITY_SETTING_UPDATED` / `PLATFORM_SETTING_UPDATED` |
| **Emergency** | A fix that cannot wait for the normal path: an actively exploited vulnerability, a production outage, a data-exposure stop | Verbal or chat approval by one approver before the change (D6 reviewer); full review **within 48 hours** after it | At least the hotfix pipeline (`docs/release/runbook.md` §5: hotfix branch, `vX.Y.Z+1-rc.1`, soak shortened to at least 4 hours with both approvers' consent) | Incident log entry, the hotfix PR marked "EMERGENCY", the retrospective review record |

## 2. Normal change: the path from request to production

1. **Request.** An issue or the PR description states what changes and why. The PR template asks for the change summary, the security checklist (authorization, tenant scoping, audit, PII, migrations, secrets) and the privacy checklist (`.github/pull_request_template.md`).
2. **Build and test.** On every push and PR to `main`: Maven build and tests, PostgreSQL integration tests, S3 store contract, agent and connector-core tests, frontend tests and build, nginx header check, version sync (`.github/workflows/ci.yml`); gitleaks, Semgrep, dependency review and `npm audit` (`.github/workflows/security.yml`).
3. **Review and merge.** A second person reviews. `.github/CODEOWNERS` names the owner who must approve. Required checks must be green. The branch-protection settings are listed in `docs/security/branch-protection.md`; the applied ruleset is `NOT IN REPO (live cloud)`.
4. **Database changes** follow the expand/contract rules (`docs/release/migration-policy.md`): a release may only add; a destructive step ships one release later. `MigrationPolicyTest` and `FlywayVersionRangeTest` enforce this in CI. Flyway undo is never used.
5. **Infrastructure changes** are Terraform in the same PR flow (`infra/terraform/`). The reviewer reads the `terraform plan` output. The CI deploy role cannot change IAM, KMS, backup vaults, the log archive or CloudTrail (`infra/terraform/modules/ci-oidc/main.tf`); those are applied by a person after review. Console changes in production are not allowed. Drift detection is not built yet (gap, SEC-025 page).
6. **Release.** `scripts/bump-version.sh X.Y.Z`, a "Release X.Y.Z" commit, then a tag. `vX.Y.Z-rc.N` deploys to staging only; `vX.Y.Z` deploys to staging, creates the GitHub Release (notes from `CHANGELOG.md`, SBOMs attached) and waits for the production approval (`.github/workflows/release.yml`, `docs/release/runbook.md` §1–§3). Images are built once and deployed by digest.
7. **Approval for production.** Two reviewers on the `production` environment, "prevent self-review" on (`docs/release/runbook.md` §3, §7). Who they are: `TO CONFIRM (organisational)`; proposed in docs/compliance/mo-001-governance-structure.md D6.
8. **Post-deploy checks.** The smoke test checks `/api/probe/synthetic` three times (`scripts/release/smoke-test.sh`). The app records `VERSION_DEPLOYED` once per version (`src/main/java/com/modocument/infrastructure/release/DeployedVersionRecorder.java`).

## 3. Rollback

- **Automatic:** the ECS deployment circuit breaker rolls back a failed rollout; the release job then fails (`scripts/release/ecs-deploy.sh`, `infra/terraform/modules/ecs-app/main.tf`).
- **Manual:** `.github/workflows/deploy.yml` redeploys a version already in that environment's registry, by digest, through the same smoke test and the same production reviewers (`docs/release/runbook.md` §4).
- The schema is never rolled back; expand-only migrations keep the previous version working.
- The rehearsal steps are in `docs/release/runbook.md` §12. They have not been run: no AWS environment exists (`NOT IN REPO (live cloud)`).

## 4. Configuration changes inside the product

- Every setting change carries the version the caller read; a stale write gets 409 (`docs/settings/catalogue.md`).
- SECURITY-tier settings and all bank bounds go through maker/checker (`SettingsMakerCheckerIntegrationTest`).
- Hard limits from the go-live gate cannot be left by any value (`SettingsGuardrailTest`); with `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true` (staging, production) a shipped default outside its limits stops the boot.
- **History:** every change is in the append-only audit trail (`SETTING_UPDATED`, `SECURITY_SETTING_UPDATED`, `PLATFORM_SETTING_UPDATED`, with old and new values). The audit viewer shows them under the category "Settings": `GET /api/admin/audit` and the page `/admin/audit` (`src/main/java/com/modocument/api/controller/AuditLogController.java`, `frontend/src/pages/AuditLogPage.jsx`, category list in `src/main/java/com/modocument/application/audit/AuditActions.java`).

## 5. When CI is not available

The pipeline is the control. If GitHub Actions cannot run (as from 2026-10-16 to at least 2026-10-19, `docs/compliance/evidence/A7-github-change-trail.md`):

1. Do not merge normal changes. Only emergency changes may proceed.
2. For an emergency change, run the same commands locally, attach the output to the PR, get the approver's written OK in the PR, and label the PR "EMERGENCY — local gate".
3. Re-run the full CI on `main` as soon as Actions works again, and review every change merged during the outage within 48 hours.

## 6. Records and retention

| Record | Where | Kept |
|---|---|---|
| Code change, author, time | git history, PR | Life of the repository |
| Review and approval | PR review, `production` environment approval | GitHub retention (`NOT IN REPO (live cloud)`) |
| Test evidence | CI run logs; release artifacts (images 30 days, SBOMs 90 days in `.github/workflows/release.yml`); SBOMs attached to the GitHub Release permanently | as stated |
| Release notes | `CHANGELOG.md`, served at `GET /api/system/release-notes` | Permanent |
| Deployed version | `VERSION_DEPLOYED` audit event | 7 years (audit retention, `docs/security/retention-and-dsr.md`) |
| In-app configuration changes | Audit trail | 7 years |

## 7. Review of this procedure

The owner checks every quarter that a sample of production changes can be traced from request to deployment in minutes (the SEC-025 test, `docs/compliance/controls/SEC-025.md`).
