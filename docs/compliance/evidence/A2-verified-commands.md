# A2 — Commands verified for MO-009

Evidence for the [MO-009 Developer & Technical Documentation set](../mo-009-developer-technical-documentation.md). Every command below was run by Agent A2 on **2026-10-19** in the development container (a git worktree of this repository at commit `eb53e97`, plus this set's documentation changes, which touch no code). Human re-performance is still required for sign-off.

Environment observed (C0): OpenJDK 21.0.11, Apache Maven 3.9.11, Node v22.22.0, npm 10.9.4, `psql`/`pg_dump` 16.14, Docker CLI present but **no Docker daemon**, Terraform **not installed**. A scratch PostgreSQL 16.14 server was listening on `127.0.0.1:55432` (the cluster `e2e/run-local.sh` uses by default).

All secrets used below were generated per run with `openssl rand`, exported to the processes of that run only, and never written to the repository. Throwaway databases and roles were dropped afterwards.

| ID | Command (from the repository root) | Result | Used in |
|---|---|---|---|
| C1 | `mvn -B -q -o -DskipTests -f connector-core/pom.xml install` | exit 0, 15 s | Part 1 §3a |
| C2 | `mvn -B -q -o -DskipTests compile` | exit 0, 47 s | Part 1 §3a |
| C3 | `mvn -B -q -o -DskipTests -f agent/pom.xml compile` | exit 0, 12 s | Part 1 §3a |
| C4 | `cd frontend && npm ci --prefer-offline --no-audit --no-fund && npm run build` | `npm ci` exit 0 (11 s); `npm run build` exit 0 (14 s), Vite "built in 12.37s"; `node_modules/` and `dist/` are gitignored | Part 1 §3b |
| C5 | `mvn -B -o -Dtest='OpenApiContractTest,FlywayVersionRangeTest,MigrationPolicyTest,SettingsCatalogueDocTest' -Dsurefire.failIfNoSpecifiedTests=false test` | BUILD SUCCESS in 155 s: `OpenApiContractTest` 7 tests, `FlywayVersionRangeTest` 7, `MigrationPolicyTest` 58, `SettingsCatalogueDocTest` 1 — 73 run, 0 failures, 0 errors, 0 skipped | Part 2 §2, §4b; Part 3 §1 |
| C6 | `docker info`; `POSTGRES_PASSWORD=x MODOCUMENT_APP_DB_PASSWORD=y MODOCUMENT_JWT_SECRET=z MODOCUMENT_MFA_KEY=w docker compose config -q`; `docker compose config -q` (no variables) | `docker info`: "failed to connect to the docker API … daemon is not running" — so `docker compose up --build -d` could **not** be run. With the four variables the compose file validates (exit 0). Without them: `error while interpolating services.app.environment.MODOCUMENT_JWT_SECRET: required variable MODOCUMENT_JWT_SECRET is missing a value` | Part 1 §2a; Part 5 §4 |
| C7 | Local backend boot, README "Run locally" path: throwaway owner role and database on `127.0.0.1:55432`; `SPRING_PROFILES_ACTIVE=local` with `MODOCUMENT_DB_URL/_USERNAME/_PASSWORD` (owner), generated `MODOCUMENT_JWT_SECRET` and `MODOCUMENT_MFA_KEY`, two bootstrap admins (`MODOCUMENT_BOOTSTRAP_ADMIN_*`, `MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_*`), `MODOCUMENT_STORAGE_ROOT` in the scratchpad; `mvn -B -o -q spring-boot:run -Dspring-boot.run.arguments="--server.port=<free> --server.address=127.0.0.1"`; then `curl` checks | `/actuator/health` → `{"status":"UP"}` 64 s after start (includes Maven start-up). `GET /api/probe/synthetic` → `{"status":"UP","version":"1.2.0","db":true,…}` with an `X-Request-Id` header. Anonymous `GET /Templates/GetAll` → 401. `POST /api/auth/login` (first admin) → keys `token`, `user`; `role=ADMIN`, `mustChangePassword=True`. `GET /api/auth/me` with the token → 200; `GET /Templates/GetAll` with the token → 403 (password change required). `GET /v3/api-docs` (local, anonymous) → 200; `GET /swagger-ui/index.html` → 200; `GET /actuator/metrics` → 401. Flyway: 58 successful versioned migrations, latest version `334`. `AppUsers` rows: 2 | Part 1 §2b–§2c; Part 3 §1, §3; Part 5 §2 |
| C7b | After stopping the app: `pg_dump -Fc -f devcheck.dump <db>`; `CREATE DATABASE <restore_db> OWNER <owner>`; `pg_restore -d <restore_db> --no-owner --role=<owner> devcheck.dump`; then per database: tables in `public`, rows in `flyway_schema_history`, rows in `pg_policies` | `pg_dump` exit 0, 311,508 bytes; `pg_restore` exit 0, about 1 s. Source: tables=60, flyway=58, policies=50. Restored: tables=60, flyway=58, policies=50. Databases, role and dump file removed afterwards | Part 5 §3c |
| C8 | `bash scripts/check-version-sync.sh` | `Version-sync: OK 1.2.0 (pom.xml, agent/pom.xml, connector-core/pom.xml, frontend/package.json, frontend/package-lock.json, CHANGELOG.md)`, exit 0 | Part 2 §1a |
| C9 | `bash scripts/check-nginx-headers.sh` | `check-nginx-headers: OK — 7 location(s) in frontend/nginx.conf carry the required security headers`, exit 0 | Part 1 §3d |
| C10 | `bash -n e2e/run-local.sh` | exit 0 (syntax only; the suite itself was not run) | Part 1 §2e |
| C11 | `grep -ohE '\$\{MODOCUMENT_[A-Z0-9_]+' src/main/resources/application.yml \| sort -u \| wc -l`; `awk` count of `- name:` entries under `modocument.alerts.rules` in `src/main/resources/application.yml` | 128 distinct `MODOCUMENT_*` variables; 26 alert rules (account-locked … agent-offline) | Part 2 §4a; Part 5 §1c |
| C12 | `grep -vc '^#' docs/api/openapi-routes.txt`; `awk` grouping of the same file by path prefix; `grep -rliE 'fine[- ]?tun' src/main/java \| wc -l`; `grep -rnoiE 'fine[- ]?tun[a-z]*\|traineddata\|training data' src/main/java` | 245 operations (matches the file header); per-family counts as in Part 3 §5; 0 files mention fine-tuning; "traineddata" appears only in `src/main/java/com/modocument/infrastructure/file/OcrService.java` (Tesseract language data) | Part 3 §5; Part 4 intro |

## Not verified here

| Command | Why not |
|---|---|
| `docker compose up --build -d`, `docker compose --profile … up` | No Docker daemon in the container (C6) |
| `mvn -B verify` (full), `-Pcoverage`, `-Ppostgres-it`, `-Ps3-it`, `-Pconnectors-it`, `-Pscale`, `-Psbom` | Full suite not needed for documentation; the IT profiles need Docker/Testcontainers. CI runs them (`.github/workflows/ci.yml`) |
| `npm test` (Vitest) | Not required by the task; CI runs it |
| `e2e/run-local.sh` | Needs Playwright's Chromium and a full stack run; syntax check only (C10) |
| `terraform fmt/validate/plan` | Terraform not installed; `infra/terraform/README.md` records validation on 9–10 Oct 2026 |
| `scripts/bump-version.sh` | Changes versioned files; not appropriate in a documentation change |
| Any GitHub Actions workflow, any AWS action, a restore from AWS Backup or RDS PITR | `NOT IN REPO (live cloud)` |
