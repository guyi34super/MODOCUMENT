# MO-009 Part 1 — Development environment, build, test and CI

Part of the [MO-009 Developer & Technical Documentation set](../mo-009-developer-technical-documentation.md). Commands marked **verified** were run on 2026-10-19 in the development container; results are in [A2-verified-commands.md](../evidence/A2-verified-commands.md) (command IDs C1–C12).

## 1. Prerequisites

| Tool | Version the repository pins or uses | Proof | Needed for |
|---|---|---|---|
| JDK | 21 (Temurin in CI and in the image) | `pom.xml` `<java.version>21`; `.github/workflows/ci.yml` (`distribution: temurin`, `java-version: '21'`); `Dockerfile` (`maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jre`) | backend, agent, connector-core |
| Maven | 3.9 | `Dockerfile` build stage `maven:3.9-eclipse-temurin-21` | all Java builds |
| Node.js / npm | Node 22 in CI and in the frontend image; the E2E and perf READMEs ask for Node 20+ | `.github/workflows/ci.yml` (`node-version: '22'`); `frontend/Dockerfile` (`node:22-alpine`); `e2e/README.md`; `perf/README.md` | frontend, E2E, perf |
| PostgreSQL | 17 in compose, Testcontainers and Terraform; any reachable server works for local runs | `docker-compose.yml` (`postgres:17`); `pom.xml` `postgres-it` comment; `infra/terraform/modules/rds/variables.tf` (`17.4`) | runtime database |
| Docker + Compose | any current version | `docker-compose.yml` | the compose stack, Testcontainers ITs |
| `psql`, `pg_isready`, `openssl`, `curl` | any | `e2e/run-local.sh` (checks for them) | Docker-free E2E runner, secret generation |
| Ollama (optional) | image `ollama/ollama:0.12.3` in compose; models `qwen2.5` (default) | `docker-compose.yml` (`ollama` service), `src/main/resources/application.yml` | AI paths; without it the app falls back to code |

There is no `.nvmrc` or `.tool-versions` file in the repository; the versions above are the ones CI and the Dockerfiles use.

Library versions that matter when reading code: Spring Boot 3.5.3 (`pom.xml`, `agent/pom.xml`, `connector-core/pom.xml`), Spring AI 1.0.1 (`pom.xml`), React 19, React Router 7, Vite 6, Vitest 4 (`frontend/package.json`), Playwright 1.56.1 (`e2e/package.json`).

## 2. Running the platform locally

There are three ways. All of them run the same PostgreSQL + Flyway schema as production (`src/main/resources/application-local.yml` header).

### 2a. Docker Compose (whole stack)

```bash
cp .env.example .env              # then replace every placeholder (README "Quick start")
docker compose up --build -d      # frontend (nginx), app, postgres
docker compose ps                 # app becomes healthy via /actuator/health
# web + API: http://localhost:3000   (the app's 8080 is not published)
```

- Compose **refuses to start** without `POSTGRES_PASSWORD`, `MODOCUMENT_APP_DB_PASSWORD`, `MODOCUMENT_JWT_SECRET` and `MODOCUMENT_MFA_KEY` (`docker-compose.yml` `${VAR:?…}`). **Verified (C6)**: `docker compose config` fails with `required variable MODOCUMENT_JWT_SECRET is missing a value` and passes once the four are set.
- `docker compose up --build -d` itself was **not verified**: this container has the Docker CLI but no Docker daemon (C6).
- Optional profiles: `--profile ai-local` (Ollama; then `docker compose exec ollama ollama pull qwen2.5`), `--profile docling`, `--profile keycloak`, `--profile extraction` with `APP_PROFILES=scale APP_MAVEN_PROFILES=scale` (`docker-compose.yml`; `README.md` "Run with Docker").
- `APP_PROFILES=local docker compose up --build` runs the `local` profile (Swagger UI on, admin MFA not forced, template changes without maker/checker) against the same database (`src/main/resources/application-local.yml`).
- Data persists in the named volumes `modocument-postgres` and `modocument-files` (`docker-compose.yml`). Keep `.env` after the first boot: the database passwords are fixed on the volume (`.env.example`).
- For direct API tooling, uncomment the loopback-only `127.0.0.1:8080:8080` mapping on `app`; never in production (`docker-compose.yml` comment).

### 2b. Backend and frontend on the host (day-to-day development)

```bash
# 1. once: shared connector library
mvn -B -f connector-core/pom.xml install

# 2. a database: the compose postgres, or any PostgreSQL you own
docker compose up -d postgres
#    or point MODOCUMENT_DB_URL / _USERNAME / _PASSWORD at your own server

# 3. secrets for this shell (never committed)
export MODOCUMENT_JWT_SECRET="$(openssl rand -base64 48)"
export MODOCUMENT_MFA_KEY="$(openssl rand -base64 48)"
export MODOCUMENT_DB_PASSWORD="$POSTGRES_PASSWORD"     # outside Docker: the schema owner
export MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
export MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD='<strong temporary password>'
export MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL=admin2@example.com
export MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD='<another strong password>'

# 4. backend on :8080
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run

# 5. frontend on :3000 (proxies /api and the legacy prefixes to :8080)
cd frontend && npm ci && npm run dev
```

- **Verified (C7)**: steps 3–4 against the sandbox PostgreSQL 16 on `127.0.0.1:55432` with a throwaway database and owner role. Flyway applied 58 migrations up to V334; `/actuator/health` answered `{"status":"UP"}` 64 s after `mvn -o spring-boot:run` started; the two bootstrap administrators were seeded; login returned a token with `mustChangePassword: true`; until the password is changed, business routes answer 403 (`src/main/java/com/modocument/infrastructure/security/PasswordChangeRequiredGate.java`).
- The JWT secret can also live in the gitignored `application-local-secrets.yml` (copy `application-local-secrets.yml.example`); the `local` profile imports it (`src/main/resources/application-local.yml`).
- The Vite dev server listens on 3000 and proxies `/api`, `/oauth2`, `/login/oauth2`, `/Templates`, `/TemplateFields`, `/TemplateDataTypes`, `/ValidationRules`, `/Validation`, `/ImportHistory`, `/ImportDetails`, `/ProcessStep` to `VITE_BACKEND_URL` (default `http://localhost:8080`) — the same list as `frontend/nginx.conf` (`frontend/vite.config.js`).
- Swagger UI: `http://localhost:8080/swagger-ui/index.html` with the `local` profile only (verified, C7).

### 2c. Seeded and bootstrapped data

| What | How | Proof |
|---|---|---|
| Schema and lookup data | Flyway on every start; lookup rows by `V2__lookup_seed.sql` | `src/main/resources/db/migration/` |
| First bank administrator(s) | Created once, when the user table is empty, from `MODOCUMENT_BOOTSTRAP_ADMIN_*` (and optionally `MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_*`); passwords are temporary; MFA enrolment at first sign-in when required | `src/main/java/com/modocument/infrastructure/bootstrap/BootstrapAdminRunner.java`; `README.md` "First run: two administrators" |
| Platform owner bank and its two PLATFORM_ADMINs | Created once from `MODOCUMENT_PLATFORM_ADMIN_EMAILS` / `_PASSWORDS` (two comma-separated entries each) | `src/main/java/com/modocument/infrastructure/bootstrap/BootstrapPlatformRunner.java`; `docs/platform/platform-owner.md` "Bootstrap" |
| Templates, rules, users beyond the above | None: created through the app | `README.md` "Quick start" |

Two administrators are needed because every user change is maker/checker (`README.md` "First run: two administrators"). `MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE=true` keeps the configured passwords on throwaway stacks only, and is refused unless `modocument.security.permit-dev-paths=true` (`README.md`).

### 2d. The fake LLM stub

`e2e/stubs/fake-llm.mjs` is an OpenAI-compatible stub (`POST /v1/chat/completions` plus a control plane) used by the E2E suite instead of a model (`e2e/README.md`). To use it by hand:

```bash
FAKE_LLM_PORT=11500 FAKE_LLM_HOST=127.0.0.1 node e2e/stubs/fake-llm.mjs &
export MODOCUMENT_AI_BASE_URL=http://127.0.0.1:11500
export MODOCUMENT_AI_API_KEY=dev-fake      # any non-empty value = "AI configured"
```

(`e2e/run-local.sh` section "4. Fake LLM" uses exactly these variables.) Without any AI endpoint the app still runs: ingestion, mapping, document creation and OCR fall back to code; chat edit, schema inference and run summaries answer 503 (`README.md` "AI integration").

### 2e. Docker-free E2E runner

`e2e/run-local.sh` builds the jar and the SPA, creates a fresh database on a local PostgreSQL (default `127.0.0.1:55432`, superuser `postgres`), starts the fake LLM, the backend with the **production** profile as `modocument_app` (RLS on), the SPA behind `vite preview`, runs Playwright and tears everything down (`e2e/run-local.sh` header).

```bash
e2e/run-local.sh                    # full suite
e2e/run-local.sh --grep @smoke      # a subset
SKIP_BUILD=1 E2E_KEEP=1 e2e/run-local.sh   # reuse the build, keep the DB for debugging
E2E_BANK_SIM=1 e2e/run-local.sh     # also the bank simulator and the agent jar
```

Not run for this set (it needs Playwright's Chromium and takes the scratch database for a full run); its shell syntax was checked with `bash -n` (C10).

## 3. Build, test and quality gates

### 3a. Backend

| Command | What it does | Proof | Verified |
|---|---|---|---|
| `mvn -B -f connector-core/pom.xml install` | Builds and installs the shared connector library (needed first) | `README.md` "Build & test" | Yes, offline with `-DskipTests` (C1) |
| `mvn -B -DskipTests compile` | Compiles the backend | — | Yes, offline (C2) |
| `mvn -B -f agent/pom.xml compile` | Compiles the agent | `.github/workflows/ci.yml` job `agent` | Yes, offline (C3) |
| `mvn -B verify` | Unit and slice tests on H2 (surefire), `OpenApiContractTest`, migration lint | `pom.xml`; `docs/api/versioning-policy.md` §1 | Focused subset only (C5) |
| `mvn -B verify -Pcoverage` | Same, with JaCoCo; report in `target/site/jacoco/`; no threshold gate yet | `pom.xml` profile `coverage` | No |
| `mvn -B verify -Ppostgres-it -DskipUTs=true` | `*PgIT` suites (Flyway chain, RLS, tenant isolation) via failsafe on Testcontainers `postgres:17`, or on `MODOCUMENT_IT_JDBC_URL` | `pom.xml` profile `postgres-it` | No (needs Docker or a dedicated server) |
| `mvn -B verify -Ps3-it -DskipUTs=true` | `FileStore` contract suite against S3Mock (Testcontainers) | `pom.xml` profile `s3-it` | No (needs Docker) |
| `mvn -B verify -Pconnectors-it` | DATABASE connector against Oracle Free, SQL Server and MariaDB containers | `pom.xml` profile `connectors-it` | No (needs Docker) |
| `mvn -B -Pscale …` | Compiles the Mongo/Kafka extraction adapters in `src/scale/java` | `pom.xml` profile `scale` | No |
| `mvn -B -DskipTests package -Psbom` | CycloneDX SBOM in `target/bom.json` | `pom.xml` profile `sbom` | No |
| `mvn -Dtest=OpenApiContractTest -Dmodocument.contract.update=true test` | Regenerates `docs/api/openapi-routes.txt` after a deliberate route change | `docs/api/versioning-policy.md` §1 | Pinned test run, without update (C5) |

`-DskipUTs=true` skips only surefire, so the IT jobs do not repeat the unit run (`pom.xml` surefire comment). `*PgIT` and `*ConnIT` classes are excluded from the default run (`pom.xml`).

Tests use H2 with the schema built from the entities and seeded from `src/main/resources/data.sql`; Flyway is off there (`README.md` "Database"; `src/main/resources/application-test.yml`).

### 3b. Frontend

```bash
cd frontend
npm ci          # verified (C4): exit 0
npm test        # vitest run
npm run build   # verified (C4): exit 0, bundle in frontend/dist
```

`npm test` runs Vitest with React Testing Library (`frontend/package.json`); not run for this set.

### 3c. E2E

- CI: `docker-compose.e2e.yml` (+ `docker-compose.e2e-bank-sim.yml` on pushes to `main`), Playwright in a container, `@smoke` on pull requests, the full suite on `main`; **non-blocking** (`continue-on-error: true`) until it has been green for a week (`.github/workflows/ci.yml` job `e2e`).
- Locally: `e2e/run-local.sh` (§2e). Test plan and traceability: `docs/phase2/test-plan.md`.

### 3d. Static and supply-chain checks

| Check | Where it runs | Proof |
|---|---|---|
| gitleaks over the full history, allow-lists in `.gitleaks.toml` / `.gitleaksignore` | `security.yml` job `gitleaks` (push, PR, weekly) | `.github/workflows/security.yml` |
| Semgrep 1.179.0 with `p/java`, `p/owasp-top-ten` and `.semgrep/modocument.yml`, `--error` | `security.yml` job `semgrep` | `.github/workflows/security.yml` |
| Dependency review (PRs) | `security.yml` job `dependency-review` | `.github/workflows/security.yml` |
| `npm audit --audit-level=high` | `security.yml` job `npm-audit` | `.github/workflows/security.yml` |
| nginx security headers | `ci.yml` job `frontend`: `scripts/check-nginx-headers.sh` on `nginx.conf` and `nginx.mtls-dev.conf` | `.github/workflows/ci.yml`; **verified locally (C9)** |
| Version sync (pom, agent, connector-core, package.json, lock, CHANGELOG) | `ci.yml` job `build-and-test`; `release.yml` job `version` | `scripts/check-version-sync.sh`; **verified locally (C8)** |
| Trivy image scan (fixable HIGH/CRITICAL) | `release.yml` job `build`; `image-rebuild.yml` weekly | `.github/workflows/release.yml`, `.github/workflows/image-rebuild.yml` |
| ZAP baseline against the compose stack | `zap-baseline.yml` weekly / manual | `.github/workflows/zap-baseline.yml`, `.zap/rules.tsv` |
| actionlint | **Not in any workflow**; run by hand during release work | `docs/release/runbook.md` §10 |
| Dependabot (Maven, npm, Actions, Docker, Terraform) | GitHub | `.github/dependabot.yml` |

Every action is pinned by commit SHA and each workflow declares least-privilege `permissions` (`.github/workflows/ci.yml` header; `README.md` "CI/CD supply chain"). Required checks and the branch-protection drill: `docs/security/branch-protection.md`.

## 4. CI/CD workflows

| Workflow | Trigger | Jobs and what they run |
|---|---|---|
| `.github/workflows/ci.yml` | push / PR to `main`; `workflow_call` from `release.yml` | `connector-core` (`mvn verify` of the library); `build-and-test` (version sync, `mvn -B verify -Pcoverage`, `-Pscale` test, coverage summary); `backend-postgres` (`-Ppostgres-it`); `storage-s3` (`-Ps3-it`); `agent` (`mvn -f agent/pom.xml verify`); `frontend` (`npm ci`, `npm test`, `npm run build`, nginx header check); `e2e` (Playwright on compose, non-blocking, skipped on tags) |
| `.github/workflows/security.yml` | push / PR to `main`; weekly Monday 05:23 UTC | `gitleaks`, `semgrep`, `dependency-review`, `npm-audit` |
| `.github/workflows/sbom.yml` | push to `main`; release | `backend-sbom` (`mvn -Psbom package`), `frontend-sbom` (`npm sbom --sbom-format cyclonedx`) |
| `.github/workflows/image-rebuild.yml` | weekly Monday 04:41 UTC; manual | rebuild both images with `--pull`, Trivy scan, open/update the "Security: base image refresh" issue |
| `.github/workflows/zap-baseline.yml` | weekly Tuesday 05:37 UTC; manual | ZAP baseline against the compose stack (integration API seeded by `.zap/integration-seed.py`) |
| `.github/workflows/release.yml` | tag `v*.*.*` or `v*.*.*-rc.*` | `version` → `tests` (calls `ci.yml`) → `build` (images once, SBOM, Trivy) → `staging` → `github-release` → `production` (see [Part 2 §1](02-release-config-secrets.md#1-release-and-deployment)) |
| `.github/workflows/deploy.yml` | manual (`version`, `environment`) | redeploys an already-pushed version by digest: rollback, roll-forward, re-apply a task-definition change |

Workflow claims in this table come from reading the workflow files; none of them was run for this set (no GitHub runner here). Whether branch protection actually requires these checks is a GitHub setting: `TO CONFIRM (organisational)` (`docs/security/branch-protection.md`).
