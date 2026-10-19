# SUP-S07 — Environment segregation and test-data governance

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 03 Software Engineering | Software Engineering | P2 | Partially Implemented | SUP-S07-E01 | SUP-S07-T01 | R-SUP-S07 |

## Requirement
Make development and staging incapable of touching production. Acceptance (gate A2): a development-environment credential replayed against production is rejected.

## How MoDocument meets it today

The Terraform design separates staging and production into **two AWS accounts** with separate state, secrets, networks and deploy roles. Nothing has been applied, so the separation is a design in code, not a running fact (`infra/terraform/README.md`). The workbook's statement of 2 Oct 2026 ("staging in the same account as production") describes an earlier plan; the repository now says separate accounts (`infra/terraform/envs/staging/providers.tf`).

### Account and network layout

| | Staging | Production | Proof |
|---|---|---|---|
| AWS account | Own account (design) | Own account; a third "lock" account planned for trail and backup copies | `infra/terraform/envs/staging/providers.tf`, `infra/terraform/envs/production/providers.tf`, `infra/terraform/README.md` |
| Region | af-south-1 (+ ap-southeast-2 backup copy) | af-south-1 (+ ap-southeast-2 backup copy) | same |
| Terraform state | Own backend (placeholder) | Own backend (placeholder) | `infra/terraform/envs/staging/backend.tf`, `infra/terraform/envs/production/backend.tf` |
| VPC CIDR | 10.41.0.0/16 | 10.40.0.0/16 | `infra/terraform/envs/staging/variables.tf`, `infra/terraform/envs/production/variables.tf` |
| Network path between them | None defined (no peering, transit gateway or VPN in the Terraform) | — | `infra/terraform/modules/network/main.tf` |
| Database reachability | DB security group accepts 5432 only from that environment's app security group | same | `infra/terraform/modules/network/main.tf` |
| Ingress | ALB direct, only from `ingress_cidrs` (default: none) | CloudFront + WAF, ALB origin locked to CloudFront | `infra/terraform/envs/staging/main.tf`, `infra/terraform/modules/edge/main.tf` |
| Secrets | Own Secrets Manager entries (JWT, MFA, connector key, DB, bootstrap, platform admins) | Own entries | `infra/terraform/envs/staging/main.tf`, `infra/terraform/envs/production/main.tf` |
| CI deploy role | `modocument-staging-github-deploy`, trust `environment:staging` **and `repo:<repo>:*`** | trust `environment:production` (+ transitional `main`) | `infra/terraform/envs/staging/main.tf`, `infra/terraform/envs/production/main.tf` |
| Locks | Backup/Object locks off (disposable data) | Vault lock and Object Lock (flag) | `infra/terraform/envs/staging/main.tf`, `infra/terraform/envs/production/main.tf` |
| Guardrails | `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS` — boot refused with out-of-limit defaults | same | `docs/settings/catalogue.md` |

### Access matrix per environment

| Principal | Local / CI | Staging | Production | Proof / basis |
|---|---|---|---|---|
| Developer (engineer) | Full (own machine; CI via PRs) | AWS access `TO CONFIRM (organisational)`; app test accounts only | No standing access; break-glass through the privileged-access process (SEC-017) `TO CONFIRM (organisational)` | docs/compliance/mo-001-governance-structure.md (D9) |
| GitHub Actions — CI jobs (`ci.yml`, `security.yml`) | Run tests with generated, per-run secrets | none (no `id-token`) | none | `.github/workflows/ci.yml` |
| GitHub Actions — `staging` job / `deploy.yml` to staging | — | Staging deploy role: push to staging ECR, roll staging ECS | none (trust is per account and environment) | `.github/workflows/release.yml`, `infra/terraform/modules/ci-oidc/main.tf` |
| GitHub Actions — `production` job / `deploy.yml` to production | — | — | Production deploy role, only after 2 environment reviewers approve | `.github/workflows/release.yml`, `docs/release/runbook.md` §7 |
| Terraform operator (human apply) | — | Staging account admin `TO CONFIRM (organisational)` | Production account admin `TO CONFIRM (organisational)` | `infra/terraform/README.md` |
| ECS task role | — | Only staging secrets and the staging file-store key | Only production secrets and key | `infra/terraform/modules/ecs-app/main.tf` |
| Platform admins (`PLATFORM_ADMIN`) | Generated test accounts | Staging bootstrap accounts (staging secrets) | Production bootstrap accounts (production secrets) | `infra/terraform/envs/staging/main.tf`, `docs/platform/platform-owner.md` |
| Bank users | Synthetic | Bank test users, synthetic or bank-confirmed test data only | Real users | `docs/compliance/policies/test-data-policy.md` |
| Node shell access | Docker locally | Session Manager only, no SSH | Session Manager only, logged | `infra/terraform/modules/ecs-app/main.tf`, `infra/terraform/modules/security-monitoring/main.tf` |

Because each environment has its own JWT secret, a staging user or client token cannot validate in production; because the deploy roles live in different accounts with environment-bound trust, a staging pipeline credential cannot assume the production role. Both statements follow from the configuration and are **not yet tested** (no AWS).

### Test-data governance
Written in `docs/compliance/policies/test-data-policy.md` (new, this pack): synthetic data only below production; production data never leaves production; no staging refresh from production (no refresh pipeline exists, and none may be built without DPO-approved masking); per-environment secrets; reserved test domains. The repository follows it: E2E secrets are generated per run and masked (`.github/workflows/ci.yml`), the bank simulator uses `.test` identities (`e2e/bank-simulator/sftp/seed/inbound/customers-2024-06-01.csv`), tests use a fake model (`e2e/stubs/fake-llm.mjs`), and gitleaks scans the full history (`.github/workflows/security.yml`).

## Evidence SUP-S07-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Account/VPC layout | `infra/terraform/envs/staging/`, `infra/terraform/envs/production/`, `infra/terraform/README.md` | repo (not applied) |
| Access matrix per environment | this page | repo + to confirm |
| Test-data policy | `docs/compliance/policies/test-data-policy.md` | repo |
| Staging-refresh pipeline | none exists, by policy | repo |
| Credential replay test (gate A2) and staging→production reachability test | — | NOT IN REPO (live cloud) |

## Test SUP-S07-T01
- **Procedure:** (1) Compare the two environment roots: accounts, state, CIDRs, peering, secrets, deploy-role trust. (2) Check that no lower environment holds a production secret or path. (3) Check test data in the repository is synthetic. (4) Gate A2: replay a staging token and the staging deploy role against production; test network reachability (workbook action, due 9 Oct 2026).
- **Expected result:** full separation; replay and reachability refused.
- **Actual result:** (1) Separate account design, separate state, non-overlapping CIDRs, no peering, separate secrets. Provider blocks do not pin the account (`allowed_account_ids` absent), so a staging plan run with production credentials would apply into production. Staging deploy role trusts any ref of the repository. (2) No shared secret or cross-environment reference found. (3) Synthetic data only (`.test` identities, generated secrets, fake model). (4) Not run: no AWS account exists — the 9 Oct 2026 workbook date is **overdue**.
- **Result:** Partial
- **Tester:** Agent A7 (automated re-performance); human sign-off required. **Date:** 2026-10-19

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| Accounts not created; separation unproven | Org | Create the staging, production and lock accounts (AWS Organization) | Bhavish Nobeen; approval Reezvi Pydiah | 2026-11-30 (proposed) | Day 60 |
| No account pin in Terraform | Config | Add `allowed_account_ids` to every provider block of both environments | Software Engineering — Bhavish Nobeen | 2026-11-13 (proposed) | Day 30 |
| Staging deploy role trusts any ref | Config | Restrict to `environment:staging` (same item as SUP-S05) | Bhavish Nobeen | 2026-11-13 (proposed) | Day 30 |
| Replay and reachability tests (overdue since 2026-10-09) | Config | After the first apply: replay a staging JWT and API-client token against production (expect 401), try the production role from the staging pipeline (expect AccessDenied), test network reachability from staging to production endpoints | Bhavish Nobeen | 2026-12-18 (proposed) | Day 60 |
| Policy not approved; human access to accounts not defined | Org / Doc | Approve `docs/compliance/policies/test-data-policy.md`; fill the developer and operator rows of the access matrix | Reezvi Pydiah; the DPO consulted | 2026-11-02 (proposed) | Day 30 |

## Risk R-SUP-S07
- **Risk:** a staging mistake or compromise reaches production data or infrastructure.
- **Cause:** separation exists only on paper; no account pin in Terraform; a broad staging deploy trust.
- **Mitigation:** separate accounts and secrets, account pins, environment-scoped OIDC trust, replay and reachability tests, synthetic-data policy.
- Likelihood, impact and residual: set in MO-031.

## To confirm
- Creation of the separate AWS accounts and who administers each: TO CONFIRM (organisational).
- Developer and operator access to staging and production: TO CONFIRM (organisational).
- Credential replay and reachability results: NOT IN REPO (live cloud).
- Azure subscription split named in the workbook action: TO CONFIRM (organisational) — the repository contains no Azure infrastructure.
