# Release runbook

> **Status: Wave 4 (ready for v1.1.0).** Versioning, build identity, the
> version and release-notes API, the migration policy tests, the Terraform
> rollback settings, `release.yml`, `deploy.yml`, the deployment recorder
> and in-app announcements exist. Wave 4 adds the soak criteria (§3), the
> v1.1.0 checklist (§11), the rollback rehearsal (§12) and the contract
> migration due after v1.1.0 (§13). Nothing here has run against AWS yet:
> the accounts do not exist (README "implement later").

## 1. Versions and tags

- SemVer `X.Y.Z`. One version for the API, the SPA and the changelog:
  `pom.xml`, `frontend/package.json` (+ lock), and the newest dated
  `CHANGELOG.md` heading. CI fails when they differ.
- Cutting a release:
  1. Make sure `## [Unreleased]` in `CHANGELOG.md` describes the change.
  2. Run `scripts/bump-version.sh X.Y.Z` (or `X.Y.Z-rc.1`: the files get
     `X.Y.Z` either way; only the tag carries `-rc.N`).
  3. Commit `Release X.Y.Z` and merge to `main`.
  4. Tag `vX.Y.Z-rc.1` on that commit and push the tag.
  5. After the staging soak (§3), tag `vX.Y.Z` on the **same commit**.
- A later candidate (`vX.Y.Z-rc.2`, after a fix merged to `main`) needs no
  new bump: `scripts/bump-version.sh X.Y.Z-rc.2` only checks the tree and
  names the tag. The fix's notes go into the `[X.Y.Z]` section.
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

- **Minimum soak on staging: 48 hours** with the candidate that will be
  promoted (a new rc restarts the clock; a hotfix may shorten it to 4 hours
  with both approvers' consent, §5). The window must include one nightly
  run of the scheduled jobs (backups, retention purge, SoD scan). Promote
  only when all of these hold:
  - the release commit's full E2E run on `main` is green (`ci.yml` job
    `e2e`, with the bank simulator and the Wave-3 specs);
  - `GET /api/system/version` on staging reports `X.Y.Z` and the newest
    `schemaVersion` of the release (§11 for v1.1.0);
  - the staging smoke test passed, and no 5xx or latency alarm fired
    during the window;
  - no new HIGH or CRITICAL security alert is unexplained (the
    **Security alerts** page of the staging test banks and of the platform
    bank, or the alert webhook channel);
  - a staging Connector Agent of this version stayed `ONLINE` with no
    `agent-offline` alert, and an AGENT-mode test connection succeeds.
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
  `vX.Y.Z+1-rc.1`, then use the same pipeline with a shortened soak (at
  least 4 hours, §3). Merge the fix back to `main`.

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
(`ReleaseScriptsTest`), `scripts/bump-version.sh` on a scratch copy
(`1.1.0-rc.1`, `VersionScriptsTest`), Terraform `fmt`/`validate` (Waves 1,
3 and 4), the E2E compose files (`docker compose config`), and the local
mTLS listener's nginx configuration against a real nginx (verified leaf
forwarded; no certificate, a planted header or a foreign CA never reach the
app). Not yet exercised: OIDC role assumption, the ECR and ghcr.io pushes,
the ECS rollout and circuit-breaker rollback, the smoke test against a real
ALB, the ALB's mTLS listener, environment approvals, and the weekly issue
creation. The first `vX.Y.Z-rc.1` on staging is the end-to-end test; the
rehearsal (§12) deliberately fails one.

## 11. v1.1.0 (phase 2) release checklist

v1.1.0 is the first release with phase 2: editable settings, the platform
owner, the admin dashboard, the code extraction fallback, release notes and
announcements, and the bank integration hub with the Connector Agent.

### 11a. Database: V200–V261 (expand only)

Eleven migrations run when the first v1.1.0 task starts. They are all
expand-only (`MigrationPolicyTest`), so v1.0.0 still runs on the migrated
schema and a rollback (§4) stays safe.

| Migration | Adds |
|---|---|
| `V200__ws20_settings` | `BankSetting`, `ConfigRevision` (editable settings) |
| `V210__ws21_platform_bank` | `Bank.Kind`/`Status`, the single PLATFORM bank, the PLATFORM_ADMIN role trigger, `AppUsers.Role` CHECK widened |
| `V220__ws22_template_visibility` | `Template.Visibility` (PRIVATE/BANK) and dashboard indexes |
| `V230__ws23_extraction_mode` | `Template.ExtractionMode`, field extraction hints, `IngestionJob` parse provenance |
| `V240__ws24_deployed_version` | `DeployedVersion` (non-tenant; first task wins) |
| `V241__ws24_announcement` | `Announcement` (platform-bank owned, RLS) |
| `V250__ws25_integration_core` | `IntegrationConnection`, `IntegrationFlow`, `IntegrationRun`, `IntegrationRunEvent`, `OutboundDelivery` |
| `V251__ws25_egress_rules` | `BankEgressRule` |
| `V252__ws25_integration_events` | `IntegrationEvent`, `IngestionJob.Origin` |
| `V260__ws26_machine_identity` | `ApiClient`, `ApiClientSecret`, `IdempotencyKey` |
| `V261__ws26_integration_agents` | `IntegrationAgent`; `fk_integrationconnection_agent` **NOT VALID** (validated in the next release, §13) |

After the deploy, `GET /api/system/version` must report `schemaVersion`
`261`. `afterMigrate` re-applies the RLS policies to every new `BankId`
table.

### 11b. New secrets and one-time values (per environment, before the deploy)

Every secret the task injects must have a value, or ECS cannot start the
task (infra/terraform/README.md, apply order).

| Secret | What | How |
|---|---|---|
| `MODOCUMENT_CONNECTOR_KEY` | AES-256-GCM key of the connector SecretVault (bank connection credentials, sealed enrolment tokens). Base64 of exactly 32 bytes; separate from the MFA key. | `openssl rand -base64 32` into `<env>/MODOCUMENT_CONNECTOR_KEY` (the shell exists since the Wave 3 apply). Record it as K16 in the key & certificate register. **Required**: staging and production refuse to start without it. |
| `MODOCUMENT_PLATFORM_ADMIN_EMAILS` / `_PASSWORDS` | The platform owner's two PLATFORM_ADMIN accounts, seeded once by `BootstrapPlatformRunner` (two comma-separated entries each, same order; passwords pass the password policy). | `platform_admin_bootstrap_enabled = true` in the env's tfvars, `terraform apply -target` the two secret shells, put the values (from a file, then shred it), full apply, then deploy. After both admins have changed their passwords and enrolled TOTP: set the flag to `false` and re-apply (infra/terraform/README.md step 6). |

Rotation of the connector key later: docs/integration/secrets.md.

### 11c. Mutual TLS for machine clients (optional, per environment)

The ALB's mTLS listener stays **off** until a bank needs certificate
authentication. AGENT-mode connections and OAuth2/API-key clients do not
need it. To switch it on:

1. Collect each bank's issuing CA (PEM). Append them to one bundle.
2. Request an ACM certificate for `api.<domain>`.
3. In the env's tfvars, set `api_mtls_enabled = true`, `api_domain`,
   `api_certificate_arn`, `api_mtls_ca_bundle_pem` (the bundle) and
   `api_mtls_client_cidrs` (the banks' egress addresses). Then
   `terraform plan` (a precondition fails if one is missing) and apply.
   The listener runs in **verify** mode on `api_mtls_listener_port`
   (default 8443) against the trust store in the private S3 bucket.
4. The app's `MODOCUMENT_MTLS_TRUSTED_PROXY_CIDRS` becomes `127.0.0.1/32`
   (its only peer is the nginx sidecar, which trusts only the ALB). Run
   `deploy.yml` with the **current** version so the service picks up the
   Terraform-only task-definition change (§4).
5. Point `api.<domain>` at the ALB directly, not at CloudFront (CloudFront
   cannot carry the client certificate).
6. Check: with a test certificate signed by a bundled CA, `curl --cert …
   https://api.<domain>/api/integration/oauth/token -d
   grant_type=client_credentials` returns 401 `invalid_client` for an
   unregistered certificate (it reached the app). Without a certificate,
   the TLS handshake fails.
7. Banks then register MTLS API clients (upload the certificate) or
   Connector Agents with `MODOCUMENT_AGENT_AUTH_METHOD=MTLS`.

To add a bank's CA later, update the bundle and apply. The trust store is
versioned, so the previous bundle stays recoverable. Developers test the
same flow locally with `docker-compose.mtls-dev.yml`
(docs/integration/developer-guide.md §8).

### 11d. Release artifacts

`release.yml` publishes, per tag:

- the API and frontend images: staging ECR at `X.Y.Z[-rc.N]` and
  `…-<sha7>`; production ECR for `vX.Y.Z` (same digests);
- the **Connector Agent**: `ghcr.io/<owner>/modocument-agent:X.Y.Z[-rc.N]`
  (the scanned image, digest verified), `modocument-agent-X.Y.Z.jar` on the
  GitHub Release, and `sbom-agent.cdx.json` next to the app and frontend
  SBOMs. rc tags publish to ghcr.io and create a pre-release; banks install
  only `X.Y.Z`.

After the production deploy: the RELEASE announcement shows "What's new in
1.1.0". Every enrolled agent older than the server shows **Update
available** in the dashboard. Banks upgrade by pulling the new agent
image (agent/README.md "Upgrade").

### 11e. Settings, alerts and the first hour

- `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true` (ecs-app) refuses to start
  when a shipped default lies outside its hard limits. A startup failure
  that names a setting is a configuration bug: roll back (§4) and fix it.
- New alert rules in the catalogue, among them `platform-change`,
  `ai-circuit-opened`, `integration-failures`, `integration-dead-letter`,
  `api-client-auth-failures` and `agent-offline` (an agent silent for
  longer than the bank's `integration.agentHeartbeatTimeout`)
  (docs/security/alerting-and-export-caps.md). Expect the first
  `version-deployed` INFO alert in every bank.
- Sign in as each platform admin (forced password change, TOTP), and check
  that the platform console lists every bank.

## 12. Rollback rehearsal (run it when AWS exists)

**Status: not run yet.** The staging account does not exist. The steps
below are the plan. Run them once on staging before the first production
release, and again after any change to `ecs-deploy.sh`, `deploy.yml` or the
ecs-app module. Record the outcome (timings, run links) in the incident log
as an exercise.

**Precondition:** staging runs a known-good candidate, for example
`1.1.0-rc.1`, healthy and smoke-tested. Note its task-definition revision:

```bash
aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" \
  --query 'services[0].taskDefinition'
```

### 12a. A deliberately failing rc (automatic rollback)

1. Branch `rehearsal/failing-rc` from the good candidate's commit. Never
   merge it.
2. Add one commit that makes the container fail **only on staging**, at
   start, before it is healthy. Change nothing else. For example, at the
   top of `docker-entrypoint.sh`:

   ```sh
   if [ "${MODOCUMENT_DEPLOY_ENVIRONMENT:-}" = staging ]; then
       echo "modocument: ROLLBACK REHEARSAL - this image fails on purpose" >&2
       exit 1
   fi
   ```

   The guard keeps the image harmless anywhere else. It is never promoted
   anyway: a failing rc stops at the staging job.
3. Tag the rehearsal with a reserved candidate number (rc **90–99** are for
   rehearsals): `git tag v1.1.0-rc.90 && git push origin v1.1.0-rc.90`.
4. **Expected**, in the run of that tag:
   - `version`, `tests` and `build` pass (the code is unchanged);
   - `staging` pushes `1.1.0-rc.90`, registers a revision pinned by digest
     and rolls the service. New tasks exit at start, and ECS replaces them.
     After the circuit breaker's failure threshold (with 2 tasks, 3 failed
     launches; typically 10–15 minutes including the 120 s health-check
     grace period), ECS rolls back to the last steady revision;
   - `ecs-deploy.sh` reports *"the deployment circuit breaker rolled
     back"*, and the job fails. `github-release` and `production` do not
     run. No pre-release and no ghcr.io image are created for rc.90;
   - throughout, the previous tasks keep serving
     (`deployment_minimum_healthy_percent = 100`): the smoke endpoint and
     `GET /api/system/version` still report the good candidate;
   - ECS service events show the rollback, and `describe-services` shows the
     noted revision as PRIMARY again;
   - the schema is unchanged (rc.90 adds no migration); `schemaVersion` is
     the same.
5. If the circuit breaker does **not** roll back within `DEPLOY_TIMEOUT_SECONDS`
   (30 min), that is a finding. Continue with 12b to restore staging, and
   open a ticket against the ecs-app module.

### 12b. Manual rollback with `deploy.yml`

1. Actions → **Deploy** → `version` = the good candidate (for example
   `1.1.0-rc.1`), `environment` = `staging`. Run it from `main`.
2. **Expected:** the job resolves the tag to its digests in the staging ECR,
   registers a new pinned revision, waits for a steady state and passes the
   smoke test (3 times `X.Y.Z` with the database up). The job page links the
   environment URL.
3. Run it once more with a version that does not exist in the ECR (for
   example `1.1.0-rc.89`). It must fail before touching the service, at
   the digest lookup.
4. For production the same run waits for the two required reviewers. The
   rehearsal does not deploy production.

### 12c. Afterwards

- Delete the `rehearsal/failing-rc` branch. The tag stays (the tag ruleset
  blocks deletion); rc numbers 90–99 are never reused for real candidates.
- Write down the measured time to automatic rollback and to the manual
  redeploy. They bound the outage window in the incident response stub.

## 13. Contract migration due in the release after v1.1.0

V261 added `fk_integrationconnection_agent` (`IntegrationConnection.AgentId`
→ `IntegrationAgent`) **NOT VALID**. New and updated rows are checked
already; existing rows are checked only when the constraint is validated,
which is a contract step (migration-policy.md). Once v1.1.0 is released,
the next release (v1.2.0 or the first v1.1.x that carries migrations) must
add it. **Do not add it earlier**: before v1.1.0 is a dated release, the
`expanded-in` header fails `MigrationPolicyTest`.

- **Owner:** ws26 (range V262–V269 in `RANGES.md`; SE5 owns the agent table).
- **File:** for example `V262__ws26_validate_connection_agent_fk.sql`:

  ```sql
  -- contract: expanded-in v1.1.0
  -- V262__ws26_validate_connection_agent_fk.sql
  ALTER TABLE IntegrationConnection VALIDATE CONSTRAINT fk_integrationconnection_agent;
  ```

- **Before merging**, check staging and production for orphans. The query
  must return 0; fix any row it finds with a forward migration first:

  ```sql
  SELECT count(*) FROM IntegrationConnection c
  WHERE c.AgentId IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM IntegrationAgent a
                    WHERE a.IntegrationAgentId = c.AgentId);
  ```

- `VALIDATE CONSTRAINT` takes only a SHARE UPDATE EXCLUSIVE lock, so writes
  continue while it scans. The table is small (connections per bank).
- Also add the item to that release's changelog ("Changed: the connection →
  agent foreign key is validated").
