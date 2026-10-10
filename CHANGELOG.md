# Changelog

All notable changes to MoDocument are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

- Entries go under `## [Unreleased]`, in the sections Added, Changed,
  Deprecated, Removed, Fixed and Security.
- `scripts/bump-version.sh X.Y.Z` turns `[Unreleased]` into the release
  heading. It also sets the same version in `pom.xml` and
  `frontend/package.json` (+ lock).
- CI runs `scripts/check-version-sync.sh` and fails when the three disagree.
- The app serves the released sections at `GET /api/system/release-notes`.
- Release process: [docs/release/runbook.md](docs/release/runbook.md).
  Migration rules: [docs/release/migration-policy.md](docs/release/migration-policy.md).

## [Unreleased]

### Added
- Release versioning: one version for `pom.xml`, `frontend/package.json` and
  this changelog, kept in sync by `scripts/bump-version.sh` and checked in CI
  by `scripts/check-version-sync.sh`.
- Build identity: commit id and build time in every build
  (`git.properties`, Spring Boot build info). Docker build arguments
  `MODOCUMENT_VERSION`, `GIT_COMMIT` and `BUILD_TIME` also set the OCI image
  labels.
- `GET /api/system/version` shows the running version, commit, build time,
  database schema version and environment. `GET /api/system/release-notes`
  shows the release notes from this file. Every signed-in bank user can call
  both (permission `SYSTEM_INFO`).
- Migration safety checks: `MigrationPolicyTest` enforces the
  expand/contract rules for migrations from V200 on. `FlywayVersionRangeTest`
  checks every migration against the version ranges in
  `db/migration/RANGES.md`. Rules: `docs/release/migration-policy.md`.
- Phase 2 contracts: roles `PLATFORM_ADMIN` and `API_CLIENT`, the new
  permission and maker/checker change-type vocabulary, and the platform bank
  lookup.
- In progress (phase 2): editable settings with guardrails, a platform owner
  console, the admin dashboard, code-based extraction fallback, in-app release
  notices and the bank integration hub.

### Changed
- Terraform (not yet applied): the ECS service rolls back a failed deployment
  automatically (deployment circuit breaker). CI now owns task-definition
  revisions, so Terraform ignores them.
- Terraform: the app container gets `MODOCUMENT_DEPLOY_ENVIRONMENT` and
  `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true`.
- Terraform: the CI deploy roles trust GitHub `environment:staging` and
  `environment:production` subjects. Production also keeps
  `refs/heads/main` for one release, during the transition.

## [1.0.0] - 2026-10-10

First production-ready release: the Go-Live Security Gate program (20
workstreams) merged into `main`.

### Added
- Banks as tenants, the roles ADMIN, APPROVER, READ_WRITE and READ_ONLY, and
  revocable JWT sessions. Only an admin can create users.
- TOTP multi-factor authentication. Admins must use it.
- OIDC sign-in through Entra ID or Keycloak, with just-in-time provisioning.
- Bank-scoped user administration with a joiner/mover/leaver lifecycle and a
  user-list export.
- Maker/checker approval for user and template changes. Report release is
  split from report preparation, and AI-generated content needs a review.
- A 30-day retention purge, plus data-subject export and erasure.
- Security alerts (webhook, e-mail), a bank-scoped SIEM feed and a public
  synthetic uptime probe.
- An OpenAPI contract generated from the code, with the route list pinned in
  CI. k6 load-test scenarios.
- Terraform for AWS af-south-1: ECS on EC2, Multi-AZ RDS PostgreSQL,
  CloudFront and WAF, immutable backups and GitHub OIDC deploy roles. Not yet
  applied.
- A security evidence pack under `docs/security/`.

### Changed
- Every route now requires a named permission. A test fails the build if a
  route has none.
- Uploads, ingestion, exports, document generation and authentication are
  rate-limited. Responses say when to retry (`Retry-After`).
- The AI gateway is local-first. Prompts are pseudonymised, a prompt never
  mixes data from two banks, and model output is scanned before use.

### Fixed
- ECS go-live blockers: the trusted proxy chain behind the ALB and
  CloudFront, the first-boot admin, and the RDS CA bundle in the image.
- Extraction counters no longer lose increments when two writers create the
  first row at the same time.

### Security
- Tenant isolation in the database: PostgreSQL row-level security, a
  separate runtime role without BYPASSRLS, and a PostgreSQL integration test
  suite.
- An append-only audit trail with request IDs, and structured logs.
- Passwords hashed with Argon2id, a password policy, and account lockout with
  backoff. Session lifetimes are bounded.
- Request validation, database integrity constraints and upload content
  checks.
- PII classification with masking by role and channel. Logs redact PII and
  credentials.
- Security headers. Containers run as non-root with all capabilities dropped.
- nginx drops client-sent `Forwarded` and `X-Forwarded-*` headers.
- Supply chain: GitHub Actions pinned by SHA, gitleaks, semgrep, npm audit,
  CycloneDX SBOMs, Dependabot, CODEOWNERS review and documented
  branch-protection settings.

[Unreleased]: https://github.com/guyi34super/MODOCUMENT/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/guyi34super/MODOCUMENT/releases/tag/v1.0.0
