# SUP-S05 — CI/CD pipeline and software supply-chain security

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 03 Software Engineering | Software Engineering | P2 | Partially Implemented | SUP-S05-E01 | SUP-S05-T01 | R-SUP-S05 |

## Requirement
Protect the pipeline itself, because it can push code to production: short-lived OIDC credentials per environment, protected branches and workflows, actions pinned by SHA, separate build and deploy with an approval gate, provenance. Acceptance (gate A8): a deploy attempted with a leaked CI token fails because there are no static credentials to leak.

## How MoDocument meets it today

| Part | What exists | Proof | State |
|---|---|---|---|
| No static cloud keys | Deploy jobs assume an AWS role through GitHub OIDC (`id-token: write` only on the `staging`, `production` and `deploy` jobs); no AWS key appears in any workflow; the only secrets referenced are `GITHUB_TOKEN` and `GITLEAKS_LICENSE` | `.github/workflows/release.yml`, `.github/workflows/deploy.yml`, `.github/workflows/security.yml` | Built |
| OIDC role per environment | GitHub OIDC provider and one deploy role per environment, `aud = sts.amazonaws.com`, 1-hour sessions. Production trusts `repo:<repo>:environment:production` plus, for one release, `ref:refs/heads/main`. **Staging trusts `repo:<repo>:*`** (any branch, tag, pull request or environment of the repository) | `infra/terraform/modules/ci-oidc/main.tf`, `infra/terraform/envs/production/main.tf`, `infra/terraform/envs/staging/main.tf` | Terraform only 🏗️; staging trust too broad |
| Least-privilege deploy role | ECR push/describe on the two repositories, ECS register/update in this environment's cluster, `iam:PassRole` only for the task roles to ECS; no IAM, KMS, backup, log or CloudTrail rights | `infra/terraform/modules/ci-oidc/main.tf` | Terraform only 🏗️ |
| Immutable artifacts | ECR tags immutable, scan on push; images built once as OCI archives, pushed with `--preserve-digests`, digests verified, task definitions pinned by digest; BuildKit image pinned by digest | `infra/terraform/modules/ci-oidc/main.tf`, `.github/workflows/release.yml`, `scripts/release/push-oci.sh` | Built (never run) |
| Separate build and deploy, approval gate | `build` job (no cloud access) → `staging` → `github-release` → `production` (environment with 2 required reviewers and "prevent self-review", per runbook) | `.github/workflows/release.yml`, `docs/release/runbook.md` §3 and §7 | Built; reviewers NOT IN REPO (live cloud) |
| Actions pinned by SHA | 58 of 58 external `uses:` references pinned to a full commit SHA (run R10); Dependabot keeps the pins fresh | `.github/workflows/`, `.github/dependabot.yml`, `docs/compliance/evidence/A7-runs.md` | Built |
| Token permissions | Top-level `permissions: contents: read` or `{}` in every workflow; each job widens only what it uses | `.github/workflows/` | Built |
| Untrusted PR code | Only `pull_request` (not `pull_request_target`), so fork PRs get no secrets; GitHub-hosted ephemeral `ubuntu-latest` runners | `.github/workflows/ci.yml`, `.github/workflows/security.yml` | Built |
| Scans in the pipeline | gitleaks (full history), Semgrep, dependency review, npm audit, Trivy release gate, SBOMs | `.github/workflows/security.yml`, `.github/workflows/release.yml`, `.github/workflows/sbom.yml` | Built; not running (below) |
| Branch and workflow protection | CODEOWNERS (`*` → one account); required settings documented (1 approval, Code Owner review, required checks, no bypass, no force push) | `.github/CODEOWNERS`, `docs/security/branch-protection.md` | Documented; **not enforced in practice** |
| Provenance attestation | **None.** Release images are built with `docker buildx --provenance=false --sbom=false`; no `attest-build-provenance`, cosign or SLSA step | `.github/workflows/release.yml` | Gap |
| Dependabot | Weekly for Maven, npm, GitHub Actions, Docker and Terraform; cooldown 7 days; security updates immediate | `.github/dependabot.yml` | Built (PRs #67–#71 opened 2026-10-19) |

**Pipeline state (2026-10-19, `docs/compliance/evidence/A7-github-change-trail.md`):** GitHub Actions has started no job since PR #63 (2026-10-16). PRs #63–#66 merged with all checks failed and no review. So today the pipeline protects nothing, and the documented branch protection is either not applied or bypassed.

## Evidence SUP-S05-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| OIDC role config | `infra/terraform/modules/ci-oidc/main.tf`, `infra/terraform/envs/production/main.tf`, `infra/terraform/envs/staging/main.tf` | repo (not applied) |
| Workflow pinning and permissions audit (R10) | `docs/compliance/evidence/A7-runs.md` | run |
| Branch and workflow protections (documented) | `docs/security/branch-protection.md`, `.github/CODEOWNERS` | repo |
| Branch protection effectiveness, CI state | `docs/compliance/evidence/A7-github-change-trail.md` | run (GitHub API) |
| A provenance attestation | does not exist | Missing |
| Applied rulesets and environment reviewers | — | NOT IN REPO (live cloud) |
| Leaked-token deploy attempt (gate A8) | — | NOT IN REPO (live cloud) |

## Test SUP-S05-T01
- **Procedure:** (1) Static audit of all workflows: pinning, permissions, `id-token`, provenance (R10). (2) Read the OIDC trust conditions per environment. (3) Check via the GitHub API whether recent merges passed the required checks and review (R9). (4) Gate A8 drill: try a deploy with a copied `GITHUB_TOKEN` (needs AWS).
- **Expected result:** all actions SHA-pinned; least-privilege tokens; per-environment trust limited to the protected environment; merges gated; a provenance attestation per release; the drill fails to deploy.
- **Actual result:** 58/58 actions SHA-pinned; least-privilege permissions in all 7 workflows; `id-token: write` only in the three deploy jobs; no static AWS credentials. Production trust limited to the `production` environment (plus a transitional `main` subject); **staging trust is `repo:<repo>:*`**. No provenance step; buildx runs with `--provenance=false`. Merges #63–#66 passed no check and had no review. Drill not run (no AWS).
- **Result:** Partial
- **Tester:** Agent A7 (automated re-performance); human sign-off required. **Date:** 2026-10-19

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| CI outage; protections not enforced | Config | Restore Actions; apply the ruleset of `docs/security/branch-protection.md` without bypass, including `.github/workflows/**` under Code Owner review; second code owner | Software Engineering — Bhavish Nobeen | 2026-10-26 (proposed) | Day 30 |
| Staging OIDC trust `repo:<repo>:*` | Config | Limit to `repo:<repo>:environment:staging`; remove production's transitional `ref:refs/heads/main` after the first release | Bhavish Nobeen | 2026-11-13 (proposed) | Day 30 |
| No provenance attestation | Build | `actions/attest-build-provenance` (pinned) for the three OCI images and the agent jar with `attestations: write`; verify before deploy (`gh attestation verify`) | Bhavish Nobeen | 2026-12-18 (proposed) | Day 60 |
| Gate A8 drill not run | Config | After the first apply: attempt `sts:AssumeRoleWithWebIdentity` from a non-environment job and with a copied token; record the refusal | Bhavish Nobeen | at go-live (date to set) | Day 90 |
| `persist-credentials` left on in several checkouts (3 in `.github/workflows/ci.yml`, also `security.yml`, `sbom.yml`) | Config | Set `persist-credentials: false` on every checkout | Bhavish Nobeen | 2026-11-13 (proposed) | Day 30 |

## Risk R-SUP-S05
- **Risk:** a compromised branch, action or CI token deploys attacker code to an environment.
- **Cause:** checks and reviews not enforced; staging role trusts every ref; no provenance to prove what was built.
- **Mitigation:** OIDC without static keys, environment-scoped trust, SHA-pinned actions, enforced branch protection, provenance attestation verified before deploy.
- Likelihood, impact and residual: set in MO-031.

## To confirm
- Applied branch and tag rulesets and `production` environment reviewers: NOT IN REPO (live cloud).
- Cause of the GitHub Actions outage: TO CONFIRM (organisational).
- Gate A8 leaked-token drill: NOT IN REPO (live cloud).
