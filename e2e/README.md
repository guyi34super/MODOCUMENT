# MoDocument E2E suite (Playwright)

End-to-end tests against a production-shaped stack: PostgreSQL with Flyway and
row-level security, the backend jar with the production profile, the built
SPA behind a proxy with the same API locations as `frontend/nginx.conf`, and
a **fake OpenAI-compatible LLM** in place of the model. The test plan, the
acceptance criteria and the traceability table are in
[`docs/phase2/test-plan.md`](../docs/phase2/test-plan.md).

```
e2e/
  package.json            @playwright/test 1.56.1 + pg 8.16.3 (exact pins)
  playwright.config.js    baseURL from E2E_BASE_URL, headless Chromium, 1 worker,
                          retries 0 locally / 1 on CI, trace on failure
  global-setup.js         waits for SPA + API + fake LLM, registers the bootstrap admin
  run-local.sh            Docker-free runner (build, DB, stub, backend, SPA, tests, teardown)
  stubs/fake-llm.mjs      fake LLM: POST /v1/chat/completions + control plane
  lib/                    env, TOTP (RFC 6238), API client + sign-in, account book,
                          seeding (API first, SQL only for the fresh-install deadlock),
                          fake-LLM control, Playwright fixtures
  fixtures/               employees.csv, pdf.js (free-text PDF writer for the AI path)
  tests/                  00-harness, 01-auth, 02-templates-ingestion, 03-session (@smoke)
                          04-known-bugs (@bug), phase2/* (test.fixme, @phase2 @waveN)
```

## Run it locally (no Docker)

Prerequisites: Java 21, Maven, Node 20+, `psql`/`pg_isready`, `openssl`,
`curl`, a PostgreSQL 16 you can reach as a superuser (the sandbox's scratch
cluster on `127.0.0.1:55432` is the default and is started if it is down),
and Chromium for Playwright 1.56.1 (`PLAYWRIGHT_BROWSERS_PATH`; never run
`playwright install` in the sandbox).

```bash
e2e/run-local.sh                          # build everything, run the whole suite
e2e/run-local.sh --grep @smoke            # anything after the script goes to `playwright test`
SKIP_BUILD=1 e2e/run-local.sh             # reuse target/*.jar and frontend/dist
E2E_KEEP=1 e2e/run-local.sh               # keep the database and run dir afterwards
E2E_DB_APP_ROLE=owner e2e/run-local.sh    # BUG-01 workaround, see "Known bugs"
```

What the script does, in order: builds the jar (`mvn -B -q -DskipTests
package`, extra flags via `E2E_MVN_ARGS`, e.g. `-o`) and the SPA (`npm run
build`). It creates a fresh database `modoc_e2e_<run>` owned by a fresh role
(the Flyway user) and makes sure `modocument_app` (the RLS runtime role)
exists. It generates every secret for this run only: JWT key, MFA key, DB
passwords, bootstrap admin email and password, AI key. It starts the fake
LLM, the backend on a free port (`--server.address=127.0.0.1`) and `vite
preview` on another free port, waits for `/actuator/health` and the SPA, then
runs Playwright. A trap stops all three process groups and drops the database
and role, on success, failure or Ctrl-C. Logs stay in `e2e/.run/<run>/`
(`backend.log`, `web.log`, `fake-llm.log`). The HTML report is in
`e2e/e2e-report/` (`npx playwright show-report e2e-report`), and failure
traces are in `e2e/test-results/`.

Other variables: `E2E_PG_HOST`, `E2E_PG_PORT`, `E2E_PG_SUPERUSER`;
`E2E_PG_SET_APP_PASSWORD=1` gives an existing `modocument_app` this run's
password, on a cluster that uses password auth rather than trust. You can
also pin `E2E_LLM_PORT`, `E2E_APP_PORT`, `E2E_WEB_PORT` and
`E2E_BOOT_TIMEOUT` (default 300 s), and pass JVM flags in `E2E_JAVA_OPTS`.

### Against a stack that is already running

```bash
cd e2e && npm ci
export E2E_BASE_URL=http://127.0.0.1:3000          # SPA origin; /api goes through it
export E2E_LLM_URL=http://127.0.0.1:18089          # fake LLM control plane
export E2E_DB_URL=postgres://<owner>:<pw>@127.0.0.1:5432/<db>   # schema owner, seeding only
export E2E_BOOTSTRAP_ADMIN_EMAIL=... E2E_BOOTSTRAP_ADMIN_PASSWORD=...
export E2E_DB_APP_ROLE=rls                         # or owner — what the backend runs as
npx playwright test
```

The account book (`$E2E_STATE_DIR/accounts.json`, default `e2e/.state/`)
remembers passwords, TOTP secrets and the last TOTP step used by each
account. Delete it whenever you point the suite at a fresh database.

## The fake LLM (`stubs/fake-llm.mjs`)

It is a plain Node server with no dependencies. The backend's AI base URL
points at it (`MODOCUMENT_AI_BASE_URL=http://<stub>`, with no `/v1`).

| Mode | Behaviour | Drives (phase 2) |
|---|---|---|
| `ok` (default) | 200, deterministic JSON built from the prompt: TableExtraction SCHEMA / FULL / ROW_BATCH / EXTRACT_AND_MAP (columns, rows, mapping to the template fields) and AutoMapping. Any other prompt gets `{}`. | AI path baseline (SMK-ING-02) |
| `error500` | 500, OpenAI error body | fallback reason `ERROR`, circuit breaker |
| `error429` | 429 `rate_limit_exceeded` | upstream quota |
| `timeout` | accepts the request and never answers | `TIMEOUT` |
| `badjson` | 200, completion is not JSON | `INVALID_OUTPUT` |
| `badenvelope` | 200, body is not JSON | transport-level garbage |

Control plane:
- `POST /__mode {"mode":"error500","count":2,"delayMs":0}` sets the mode.
  `count` limits it to the next N completions.
- `GET /__mode` returns the current mode.
- `GET /__stats` returns `{calls, byKind, byMode, last[]}`; use it to prove
  "CODE_ONLY never calls the LLM".
- `POST /__reset` clears the stats.
- `GET /health` is the liveness check.

From specs, use the `llm` fixture (`lib/llm.js`). It resets the mode to `ok`
around every test. The stub logs prompt kind and length only, never content.
Run it alone with `FAKE_LLM_PORT=18089 node stubs/fake-llm.mjs`.

## Accounts, MFA and seeding

- **Bootstrap admin**: generated per run and enrolled in TOTP by
  `SMK-AUTH-01` through the UI. The secret is read from the enrolment screen.
- **Second admin (checker)**: a fresh install has exactly one bank admin, and
  every user-security change needs a different approver. That means the
  product cannot add a second user by itself (OBS-01 in the test plan).
  `lib/db.js` therefore clones the bootstrap admin's row by SQL, flagged
  `MustChangePassword`, and its first sign-in goes through the normal forced
  change and MFA enrolment. This is the only SQL write the suite makes, and
  assertions never use the database.
- **Operator**: a READ_WRITE user created the same way. It owes no MFA, so
  the document-flow specs do not depend on an admin login.
- **Users created in tests** go through maker/checker: `createUserViaMakerChecker()`
  in `lib/seed.js`, with the bootstrap admin as maker and the second admin
  as checker.
- **TOTP** (`lib/totp.js`): RFC 6238 (SHA-1, 30 s, 6 digits), checked
  against the RFC vectors in HARNESS-01. The backend refuses a step at or
  before the last one used. `nextCode()` returns the lowest step it will
  accept and waits only when it has to. `sessionFor(email)` handles enrolment,
  the code step and a forced password change, and caches the token.

## Writing specs

- Put the acceptance-criterion ID first in the title (`AC-SET-03 …`), plus
  tags: `@smoke` for the per-wave gate, `@phase2 @waveN` for feature specs,
  and `@bug` for regression tests of open bugs.
- Locators: role or label first (`getByRole`, `getByLabel`). Use a
  `data-testid` only where the spec file's header documents one; each
  `tests/phase2/*.spec.js` header lists the selector contract its feature
  owner agreed to.
- Make the feature live in the same PR that ships it: remove `.fixme`, fill
  in the steps and keep the AC ID.
- Run in file order with one worker: TOTP steps are single-use for each
  account. Name data with `uniqueName()` so re-runs never collide.

## Known bugs (tests in `tests/04-known-bugs.spec.js`, tagged `@bug`)

| ID | Severity | What | Workaround |
|---|---|---|---|
| BUG-01 | P1 | `POST /api/auth/mfa/verify` returns 502 when the backend runs as `modocument_app`, as production does. The `LOGIN_SUCCESS` audit row is flushed at commit outside the tenant binding, so RLS rejects it. No MFA user, and therefore no admin, can sign in. | `E2E_DB_APP_ROLE=owner` (the backend runs as the schema owner and RLS is bypassed) |
| BUG-02 | P4 | After a successful forced password change, the login page says "Your session expired". `logout()` is called with the token the change just revoked, and the 401 triggers the expired-session redirect. | none needed |

These tests fail until the bug is fixed. That is intended: do not mark them
fixme. With `E2E_DB_APP_ROLE=rls` (the default), `SMK-AUTH-01` also fails
because of BUG-01, and the other 01-auth tests do not run.

## CI (for SE5 to add — QA does not own workflows)

The CI stack is `docker-compose.e2e.yml` at the repo root: postgres:16 on
tmpfs, the app image, the frontend nginx image, the fake LLM on
node:22-alpine, and a runner on `mcr.microsoft.com/playwright:v1.56.1-noble`.
It honours the same `E2E_*` contract as `run-local.sh`. A job to add to
`.github/workflows/ci.yml`, or to a new `e2e.yml` on `pull_request` and
`push` to main:

```yaml
  e2e:
    name: E2E (Playwright on the compose stack)
    runs-on: ubuntu-latest
    timeout-minutes: 45
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8 # v5.0.0
      - name: Generate run-scoped secrets
        run: |
          umask 077
          mkdir -p e2e/.run            # gitignored (e2e/.gitignore)
          app_pw=$(openssl rand -hex 24)
          {
            echo "E2E_POSTGRES_PASSWORD=$(openssl rand -hex 24)"
            echo "E2E_APP_DB_PASSWORD=$app_pw"
            echo "E2E_APP_DB_USER_PASSWORD=$app_pw"
            echo "E2E_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')"
            echo "E2E_MFA_KEY=$(openssl rand -base64 48 | tr -d '\n')"
            echo "E2E_BOOTSTRAP_ADMIN_EMAIL=e2e-admin-${GITHUB_RUN_ID}@e2e.modocument.test"
            echo "E2E_BOOTSTRAP_ADMIN_PASSWORD=E2e-$(openssl rand -base64 18 | tr -d '/+=\n')-7q"
            echo "E2E_AI_KEY=e2e-$(openssl rand -hex 8)"
            echo "E2E_RUN_ID=${GITHUB_RUN_ID}"
          } > e2e/.run/ci.env
          while IFS='=' read -r name value; do
            case "$name" in *EMAIL|E2E_RUN_ID) ;; *) echo "::add-mask::$value" ;; esac
          done < e2e/.run/ci.env
      - name: Run the suite
        run: |
          docker compose -f docker-compose.e2e.yml --env-file e2e/.run/ci.env config -q
          docker compose -f docker-compose.e2e.yml --env-file e2e/.run/ci.env up --build \
            --abort-on-container-exit --exit-code-from e2e
      - name: Stack logs on failure
        if: failure()
        run: docker compose -f docker-compose.e2e.yml --env-file e2e/.run/ci.env logs --tail 300 app frontend fake-llm
      - name: Upload report and traces
        if: always()
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        with:
          name: e2e-report
          path: |
            e2e/e2e-report
            e2e/test-results
          retention-days: 14
      - name: Tear down
        if: always()
        run: docker compose -f docker-compose.e2e.yml --env-file e2e/.run/ci.env down -v
```

While BUG-01 is open, the gate can run in workaround mode. Append these
lines to the env file:

```
E2E_DB_APP_ROLE=owner
E2E_APP_DB_USER=modocument
E2E_APP_DB_USER_PASSWORD=<the E2E_POSTGRES_PASSWORD value>
```

and pass `E2E_PLAYWRIGHT_ARGS=--grep-invert @bug`. Remove all of this when
BUG-01 is fixed. For a per-PR smoke job, set `E2E_PLAYWRIGHT_ARGS=--grep
@smoke`; the full suite runs on main and nightly.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `E2E_DB_URL is not set` | You ran `npx playwright test` without the stack variables. Use `run-local.sh` or export them. |
| `backend exited during startup` | Read `e2e/.run/<run>/backend.log`. It is usually a migration or env problem. |
| Chromium missing or the wrong revision | Playwright 1.56.1 expects `chromium-1194`. Set `E2E_CHROMIUM_PATH=/opt/pw-browsers/chromium`. |
| `… has MFA enabled but the suite does not know its TOTP secret` | Stale account book against a reused DB. Delete `$E2E_STATE_DIR`, or use a fresh DB. |
| 502 `The database request failed.` on `/api/auth/mfa/verify` | BUG-01. Run with `E2E_DB_APP_ROLE=owner`. |
