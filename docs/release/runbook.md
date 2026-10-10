# Release runbook

> **Status: Wave 2.** Versioning, build identity, the version and
> release-notes API, the migration policy tests, the Terraform rollback
> settings, `release.yml`, `deploy.yml`, the deployment recorder and in-app
> announcements exist. Wave 4 adds the soak criteria and the rollback
> rehearsal (a deliberately failing rc on staging). Nothing here has run
> against AWS yet: the accounts do not exist (README "implement later").

## 1. Versions and tags

- SemVer `X.Y.Z`. One version for the API, the SPA and the changelog:
  `pom.xml`, `frontend/package.json` (+ lock), and the newest dated
  `CHANGELOG.md` heading. CI fails when they differ.
- Cutting a release:
  1. Make sure `## [Unreleased]` in `CHANGELOG.md` describes the change.
  2. Run `scripts/bump-version.sh X.Y.Z`.
  3. Commit `Release X.Y.Z` and merge to `main`.
  4. Tag `vX.Y.Z-rc.1` on that commit and push the tag.
  5. After the staging soak (§3), tag `vX.Y.Z` on the **same commit**.
- `vX.Y.Z-rc.N` deploys to staging only and creates a GitHub pre-release.
  `vX.Y.Z` deploys to staging, creates the GitHub Release, then waits for
  the production approval.
- Patch releases (`X.Y.Z+1`) cover fixes, dependency and base-image
  updates. Dependabot PRs merge to `main` and ship in the next release.

## 2. Pipeline (`.github/workflows/release.yml`)

Triggered by pushing a `v*.*.*` / `v*.*.*-rc.*` tag. Actions are pinned by
commit SHA; every job has only the permissions it uses; one run per tag
(concurrency), one deployment per environment at a time (shared with
`deploy.yml`).

| Job | What it does | Permissions |
|---|---|---|
| `version` | `scripts/check-version-sync.sh <tag>`; the changelog has a `[X.Y.Z]` section; computes `X.Y.Z`, the image version (`X.Y.Z` or `X.Y.Z-rc.N`), `sha7` and the build time (commit time) | `contents: read` |
| `tests` | calls `ci.yml` (`workflow_call`): Maven verify, PostgreSQL ITs, frontend tests and build (the E2E job is skipped on tags) | `contents: read` |
| `build` | builds each image **once** with `docker buildx` (pinned BuildKit) as an OCI archive, build args `MODOCUMENT_VERSION`, `GIT_COMMIT`, `BUILD_TIME`; Trivy fails on **fixable HIGH/CRITICAL**; CycloneDX SBOM per image; uploads the archives (30 days) and SBOMs (90 days). ws26: the same for the Connector Agent image (`agent/Dockerfile`), plus the standalone agent jar | `contents: read` |
| `staging` | environment `staging`: OIDC role; pushes immutable tags `<image version>` and `<image version>-<sha7>` with `skopeo --preserve-digests` (`scripts/release/push-oci.sh` verifies the digest); registers a task-definition revision with both images **pinned by digest** and rolls the service (`scripts/release/ecs-deploy.sh`, fails when the circuit breaker rolls back); smoke test: `/api/probe/synthetic` reports `X.Y.Z` with the database up, 3 times in a row (`scripts/release/smoke-test.sh`) | `contents: read`, `id-token: write` |
| `github-release` | GitHub (pre-)release named after the tag, body = the changelog section (`scripts/release/changelog-section.sh`), assets = the three SBOMs and `modocument-agent-<image version>.jar`; pushes the scanned agent image unchanged to `ghcr.io/<owner>/modocument-agent:<image version>` (digest verified). **rc tags stop here.** | `contents: write`, `packages: write` |
| `production` | environment `production` (required reviewers): pushes the **same digests** as `X.Y.Z` and `X.Y.Z-<sha7>` to the production ECR (digest verified against the build job), deploys, smoke test | `contents: read`, `id-token: write` |

Accepted vulnerability risk goes into a reviewed `.trivyignore` entry with
an expiry date and a ticket, never into the workflow.

## 3. Staging soak and production approval

- Minimum soak on staging: TBD (Wave 4). During the soak check the E2E smoke
  run, the error-rate alarms, and that the migrations applied
  (`schemaVersion` on the Releases page or `GET /api/system/version`).
- Approve the `production` job in the run of the `vX.Y.Z` tag within 30
  days (the image artifact's retention); after that, re-run the workflow.
- Production window: Sunday maintenance window. Announce it first with a
  **MAINTENANCE** announcement (platform console → Announcements): users
  see it on every page, in their own time zone, and cannot dismiss it.
- Approvers: two reviewers on the `production` environment, "prevent
  self-review" on — the person who pushed the tag cannot approve.

## 4. Rollback and roll-forward (`.github/workflows/deploy.yml`)

- **Automatic:** the ECS deployment circuit breaker (`rollback = true`,
  Terraform `ecs-app`) returns to the last task definition that reached a
  steady state. The release job then fails ("circuit breaker rolled back").
- **Manual:** Actions → *Deploy* → `version` = the previous release (an
  `X.Y.Z` already in that environment's ECR), `environment` = `production`
  (reviewers approve as for a release). It resolves the tag to its digests,
  registers a pinned revision, waits for steady state and runs the smoke
  test. The same run with the **current** version re-applies a
  Terraform-only task-definition change (new env var or secret).
- The schema stays migrated, which is why
  [migration-policy.md](migration-policy.md) requires expand-only
  migrations. **Never use Flyway undo.**
- After a rollback: record it in the incident log, fix forward, and cut a
  patch release.

## 5. Hotfix

- Branch from the release tag. Fix, bump the patch version, tag
  `vX.Y.Z+1-rc.1`, then use the same pipeline with a shortened soak. Merge
  the fix back to `main`.

## 6. Security patch SLA

- Critical CVEs (base image, dependencies): patch release to production
  within **72 hours**.
- `image-rebuild.yml` (weekly) rebuilds both images from the current base
  tags and scans them. Fixable HIGH/CRITICAL findings open — or update — the
  issue **"Security: base image refresh"** (its only write permission is
  `issues: write`, in that one job); the run stays red while a fixable
  CRITICAL remains. It never pushes images: the fix ships as a patch release.

## 7. Repository settings (one-time, by a repository admin)

Nothing below is a secret: AWS access is GitHub OIDC only.

**Environments** (Settings → Environments):

| Setting | `staging` | `production` |
|---|---|---|
| Required reviewers | — | 2 people, "Prevent self-review" |
| Deployment branches and tags | selected: tags `v*`, branch `main` | selected: tags `v*` (release.yml), branch `main` (deploy.yml) |
| Wait timer | — | optional |

**Environment variables** (same names in both environments, values from
`terraform output` of that environment):

| Variable | Example | Source |
|---|---|---|
| `AWS_REGION` | `eu-west-1` | the env's provider region |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::<account>:role/modocument-staging-github-deploy` | `deploy_role_arn` |
| `ECR_REGISTRY` | `<account>.dkr.ecr.eu-west-1.amazonaws.com` | host part of `app_repository_url` |
| `ECR_APP_REPOSITORY` | `modocument-staging/app` | path part of `app_repository_url` |
| `ECR_FRONTEND_REPOSITORY` | `modocument-staging/frontend` | path part of `frontend_repository_url` |
| `ECS_CLUSTER` | `modocument-staging` | the env's `name` |
| `ECS_SERVICE` | `modocument-staging` | the env's `name` |
| `ECS_TASK_FAMILY` | `modocument-staging` | the env's `name` |
| `PUBLIC_BASE_URL` | `https://staging.modocument.example` | the env's public host (https, no path) |

**Tag protection:** a repository ruleset on tags `v*` — only release
managers may create them; deletion and update (force-push) blocked.

**Branch protection:** keep `ci.yml`'s `build-and-test`, `backend-postgres`
and `frontend` jobs required. The `e2e` job is **non-blocking**
(`continue-on-error`) until it has been green on `main` for a week; then
remove `continue-on-error` and make it required.

## 8. In-app: version, release notes, announcements

- `GET /api/system/version` (every signed-in user): version, commit, build
  time, schema version, `deployedAt` (first start of this version here) and
  environment. The SPA compares it with its own bundle version every 5
  minutes and offers **"A new version is available — Reload"**. The
  periodic check only runs while the session token is fresh, so an idle tab
  never keeps its session alive.
- **Deployment recorder:** at startup, the first task of a new version
  inserts `DeployedVersion` (V240; the primary key makes exactly one task
  win), writes `VERSION_DEPLOYED` in every bank's audit trail and publishes a
  **RELEASE** announcement for 7 days. Admins see "What's new in X.Y.Z"
  with the release notes; everyone may dismiss it. Later tasks and restarts
  do nothing.
- **Announcements** (platform console → Announcements, `PLATFORM_ANNOUNCE`):
  INFO, MAINTENANCE (with its outage window, never dismissible) or RELEASE;
  severity INFO / WARNING / CRITICAL; every bank or a list of bank codes;
  a display window of at most 366 days. Plain text only — the SPA renders
  it escaped. Withdrawing archives (the record stays). Every change is
  audited (`ANNOUNCEMENT_PUBLISHED`, `ANNOUNCEMENT_ARCHIVED`) in the
  platform bank.
- Rows are owned by the platform bank (row level security); banks receive
  them only through `GET /api/announcements/active`, served from memory.
- Configuration (defaults; no `application.yml` entry needed):

| Key | Default | Meaning |
|---|---|---|
| `modocument.release.record-deployment` | `true` | run the deployment recorder at startup |
| `modocument.release.banner-days` | `7` | how long the RELEASE announcement shows (1–90) |
| `modocument.announcements.cache-ttl` | `30s` | how soon another instance's announcement change is served |

## 9. Infrastructure contract (in place)

- The ECS service has `deployment_circuit_breaker { enable = true,
  rollback = true }`. Terraform ignores `task_definition` and
  `desired_count`: CI owns task-definition revisions, and autoscaling owns
  the count. See [infra/terraform/README.md](../../infra/terraform/README.md).
- The CI deploy roles trust the GitHub `environment:staging` and
  `environment:production` OIDC subjects. Production also keeps the
  `refs/heads/main` subject until one release has shipped through the
  environment-gated pipeline. Then delete that subject.
- The deploy role may push to the two ECR repositories (incl.
  `ecr:DescribeImages` for tag → digest), describe/register task
  definitions, update and describe the one service, and pass the task and
  execution roles to ECS — nothing else.
- The app container gets `MODOCUMENT_DEPLOY_ENVIRONMENT` (reported by
  `/api/system/version`) and `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true`.

## 10. What cannot be verified without AWS / GitHub settings

Checked locally: `actionlint` (with shellcheck) on every workflow, the
release scripts against stand-ins for the AWS CLI and skopeo
(`ReleaseScriptsTest`), Terraform `fmt`/`validate` (Wave 1; Wave 2 needed
no Terraform change). Not yet exercised:
OIDC role assumption, the ECR pushes, the ECS rollout and circuit-breaker
rollback, the smoke test against a real ALB, environment approvals, and the
weekly issue creation. The first `vX.Y.Z-rc.1` on staging is the
end-to-end test; the Wave 4 rehearsal deliberately fails one.
