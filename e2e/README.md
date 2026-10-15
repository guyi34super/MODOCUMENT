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
  bank-simulator/         Wave-3 "bank": bank DB, REST stub + webhook verifier, SFTP
                          (own README; `npm run test:bank-sim` = its node:test suites)
  lib/                    env, TOTP (RFC 6238), API client + sign-in, account book,
                          seeding (API first, SQL only for the fresh-install deadlock),
                          fake-LLM control, Playwright fixtures; phase2.js (settings,
                          dashboard, onboarding, announcements, extraction helpers);
                          integration.js + banksim.js (Wave 3, written against the contracts);
                          doccreation.js + pdf-inspect.js (exact PDF templates: page PNGs,
                          page count and text of a generated PDF, no dependencies)
  fixtures/               employees.csv, pdf.js (free-text PDF writer for the AI path),
                          pdf-form.js (multi-page form PDF writer: text, boxes, images;
                          blank and filled twins), png.js (PNG encode/decode/pixel diff)
  tests/                  00-harness, 01-auth, 02-templates-ingestion, 03-session (@smoke)
                          04-known-bugs (@bug), phase2/* (@phase2 @waveN; all live
                          since Wave 4 except AC-EXT-13); phase2/pdf-templates (@pdf)
```

Phase-2 status (Wave 4 QA round): every phase-2 spec is **live**, including
the integration hub (no `E2E_WAVE3` switch any more: the connections/flows
and agent groups skip without the bank simulator) and the Wave-4 extraction
UI (provenance badge, low-confidence callout, extraction section). Still
`fixme`: AC-EXT-13 (AI_PARTIAL needs a multi-batch document). Skipped
without the right stack: AC-INT-15 (SFTP: `E2E_BANK_SIM_SFTP_*`, the compose
simulator) and AC-INT-21 (nginx mTLS listener: `E2E_MTLS_URL`).

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
E2E_DB_APP_ROLE=owner e2e/run-local.sh    # the old BUG-01 workaround (fixed; kept as a switch)
E2E_BANK_SIM=1 e2e/run-local.sh           # + bank simulator (bank DB + REST stub) and the agent jar
```

Per run, the script also generates the two bootstrap **platform admins**
(`MODOCUMENT_PLATFORM_ADMIN_EMAILS/PASSWORDS` for the backend,
`E2E_PLATFORM_ADMIN_{1,2}_{EMAIL,PASSWORD}` for the suite; fixtures
`platformApi` / `platformCheckerApi`) and a throwaway connector key
(`MODOCUMENT_CONNECTOR_KEY`), and sets two more E2E-only
relaxations: the AI read timeout is 5 s (so the fake LLM's `timeout` mode
reaches the code fallback inside a test). The bootstrap admin's password is
temporary since phase 2 (OBS-02): `SMK-AUTH-01` replaces it, and
`bootstrapPassword()` (lib/seed.js) is the CURRENT one — use it whenever a
spec clones the bootstrap admin's row.

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
- `POST /__reset` clears the stats **and resets the mode to the default**:
  call it before `setMode()`, never after.
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
| BUG-01 | P1 | **Fixed (Wave 2)** — kept as a regression test. `POST /api/auth/mfa/verify` returned 502 under `modocument_app` (LOGIN_SUCCESS audit flushed outside the tenant binding). | — |
| BUG-02 | P4 | **Fixed (Wave 2)** — kept as a regression test. "Your session expired" after a forced password change. | — |
| BUG-PDF-01 | P3 | **Fixed (exact PDF templates)** — kept as a regression test (PDF-02b in `tests/phase2/pdf-templates.spec.js`; `DocCreationPdfApiTest`, `BlueprintDetailPage.test.jsx`). A low-confidence data guess (confidence < 0.6, e.g. a free-standing e-mail or date that may be static form text) was removed by default. It is now only suggested (`applied:false`, and its field `suggested:true`): it is left in the draft template, listed as "Suggested — not removed", and removed only when the user applies it. | — |
| BUG-PDF-02 | P2 | **Fixed (QA, exact PDF templates)** — kept as a regression test (`PdfContentEditorTest.removedValuesLeaveNoPerGlyphWidthsBehind`, PDF-02). A removed value stayed in the template PDF as a TJ of its glyph widths (word lengths, `@`, digit groups of the customer's data). | — |
| BUG-04 | P4 | **Fixed (Wave 4)** — kept as a regression test. After a forced password change the inline "Password changed — sign in again" notice never showed (ProtectedRoute redirected before the page navigated with state). `logout({notice})` now commits the notice with the sign-out and ProtectedRoute's redirect carries it. | — |

An open bug's test fails until the bug is fixed; from phase 2 Wave 2 on, a
NEWLY found bug's test is committed as `test.fixme` (with the repro in the
test) and switched on by the fix. BUG-03 (AI timeout surfacing as
`CancellationException`) is a backend unit-level bug: see
`AiTimeoutCancellationBug03Test` and test plan §8b.

## CI (`.github/workflows/ci.yml`, job `e2e`)

The CI stack is `docker-compose.e2e.yml` at the repo root: postgres:16 on
tmpfs, the app image, the frontend nginx image, the fake LLM on
node:22-alpine, and a runner on `mcr.microsoft.com/playwright:v1.56.1-noble`.
Every pulled image is **pinned by digest** (the tag stays for readability),
in the bank simulator's compose file too. It honours the same `E2E_*`
contract as `run-local.sh`.

The job (non-blocking until it has been green on `main` for a week):

| Event | Stack | Suite |
|---|---|---|
| pull request | `docker-compose.e2e.yml` | `--grep @smoke` |
| push to `main` | + `docker-compose.e2e-bank-sim.yml` (bank DB, REST stub, SFTP; `E2E_BANK_SIM=1`) | everything, Wave-3 specs on (`E2E_WAVE3=1`) |
| release tag | skipped | — |

Its first step generates every secret for this run only into the gitignored
`e2e/.run/ci.env` (mode 0600) and masks each value in the log: database
passwords, JWT, MFA and **connector** keys (`E2E_CONNECTOR_KEY`, 32 random
bytes, the app's `MODOCUMENT_CONNECTOR_KEY`), the bootstrap admin, the two
bootstrap **platform admins** (`E2E_PLATFORM_ADMIN_{1,2}_{EMAIL,PASSWORD}`,
the app's `MODOCUMENT_PLATFORM_ADMIN_EMAILS/PASSWORDS`) and, on `main`, the
bank simulator's `BANK_SIM_*` credentials. It selects the compose files
through `COMPOSE_FILE`. To reproduce a CI run locally:

```bash
COMPOSE_FILE=docker-compose.e2e.yml:docker-compose.e2e-bank-sim.yml \
  docker compose --env-file e2e/.run/ci.env up --build \
  --abort-on-container-exit --exit-code-from e2e
```

`AC-INT-15` reads the bank side of the SFTP flow over SFTP (a list-only dry
run of a throwaway inbound flow), so it needs no Docker CLI in the runner.
`E2E_WAVE3` is no longer read by the suite.

BUG-01 is fixed: the gate runs production-shaped (`E2E_DB_APP_ROLE=rls`).
The owner-mode switch (`E2E_DB_APP_ROLE=owner`, `E2E_APP_DB_USER=modocument`,
`E2E_APP_DB_USER_PASSWORD=<E2E_POSTGRES_PASSWORD>`) stays for debugging only.
Pull requests set `E2E_PLAYWRIGHT_ARGS=--grep @smoke`; the full suite runs
on pushes to `main`.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `E2E_DB_URL is not set` | You ran `npx playwright test` without the stack variables. Use `run-local.sh` or export them. |
| `backend exited during startup` | Read `e2e/.run/<run>/backend.log`. It is usually a migration or env problem. |
| Chromium missing or the wrong revision | Playwright 1.56.1 expects `chromium-1194`. Set `E2E_CHROMIUM_PATH=/opt/pw-browsers/chromium`. |
| `… has MFA enabled but the suite does not know its TOTP secret` | Stale account book against a reused DB. Delete `$E2E_STATE_DIR`, or use a fresh DB. |
| 502 `The database request failed.` on `/api/auth/mfa/verify` | BUG-01. Run with `E2E_DB_APP_ROLE=owner`. |
