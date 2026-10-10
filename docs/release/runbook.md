# Release runbook (outline)

> **Status: outline (Wave 1).** The full runbook lands in Wave 4 with the
> rollback rehearsal. What exists today: versioning
> (`scripts/bump-version.sh`, `scripts/check-version-sync.sh`), build
> identity, `GET /api/system/version` and `/release-notes`, the migration
> policy tests, and the Terraform rollback settings. Wave 2 adds
> `release.yml` and `deploy.yml`. Until then, sections marked *(Wave 2)*
> describe the target, not working automation.

## 1. Versions and tags

- SemVer `X.Y.Z`. One version for the API, the SPA and the changelog:
  `pom.xml`, `frontend/package.json` (+ lock), and the newest dated
  `CHANGELOG.md` heading. CI fails when they differ.
- Cutting a release:
  1. Make sure `## [Unreleased]` in `CHANGELOG.md` describes the change.
  2. Run `scripts/bump-version.sh X.Y.Z`.
  3. Commit `Release X.Y.Z` and merge to `main`.
  4. Tag `vX.Y.Z-rc.1` on that commit.
- Tags: `vX.Y.Z-rc.N` deploys to staging only. `vX.Y.Z` on the same commit
  promotes to production. Protect `v*` tags with a repository ruleset.
  *(Wave 2)*
- Patch releases (`X.Y.Z+1`) cover fixes, dependency and base-image
  updates. Dependabot PRs merge to `main` and ship in the next release.

## 2. Pipeline *(Wave 2: release.yml)*

1. Version check: `scripts/check-version-sync.sh <tag>`.
2. The reusable CI workflow (build, unit tests, PostgreSQL integration
   tests, frontend tests).
3. Build each image once, with build args `MODOCUMENT_VERSION`,
   `GIT_COMMIT` and `BUILD_TIME`. Then the SBOM and a Trivy scan.
4. Push immutable tags `X.Y.Z` and `X.Y.Z-<sha7>` to staging ECR.
5. Staging deploy:
   1. Register a task definition with the images pinned by digest.
   2. Update the service and wait until it is stable.
   3. Smoke test: `/api/probe/synthetic` reports the version, and
      `/api/system/version` reports the commit.
6. Create a GitHub Release with the changelog section and the SBOM.
7. Production (GitHub environment `production`, 2 reviewers, `v*` tags
   only): push the same digests, deploy, run the smoke test.

## 3. Staging soak and production approval

- Minimum soak on staging: TBD (Wave 4). Checks during the soak: the E2E
  smoke test, error-rate alarms, and that the migrations applied
  (`schemaVersion` in `/api/system/version`).
- Production window: Sunday maintenance window. Announce it in-app with a
  MAINTENANCE banner first *(Wave 2)*.
- Approvers: two reviewers on the `production` environment. The tagger
  cannot approve their own release.

## 4. Rollback

- **Automatic:** the ECS deployment circuit breaker (`rollback = true`,
  Terraform `ecs-app`) returns to the last task definition that reached a
  steady state.
- **Manual:** run `deploy.yml` with `version` set to the previous release
  *(Wave 2)*. The schema stays migrated, which is why
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
  within **72 hours**. `image-rebuild.yml` opens a "Security: base image
  refresh" issue when Trivy finds fixable HIGH or CRITICAL findings
  *(Wave 2)*.

## 7. Infrastructure contract (in place)

- The ECS service has `deployment_circuit_breaker { enable = true,
  rollback = true }`. Terraform ignores `task_definition` and
  `desired_count`: CI owns task-definition revisions, and autoscaling owns
  the count. See [infra/terraform/README.md](../../infra/terraform/README.md).
- The CI deploy roles trust the GitHub `environment:staging` and
  `environment:production` OIDC subjects. Production also keeps the
  `refs/heads/main` subject until one release has shipped through the
  environment-gated pipeline. Then delete that subject.
- The app container gets `MODOCUMENT_DEPLOY_ENVIRONMENT` (reported by
  `/api/system/version`) and `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true`.
