# MoDocument

Document processing API that turns **any document** (CSV, Excel, PDF, Word, images/scans)
into clean, validated, tabular data — driven by **user-defined templates** with custom
per-field validation (including UI-supplied regex), AI-assisted extraction and mapping,
and resumable ingestion jobs checkpointed in SQL.

Java 21 / Spring Boot 3.5 / Spring AI. PostgreSQL in production (schema managed by
Flyway migrations for both production and local development), in-memory H2 for
the test suite.

## Features

### Custom templates & validation
- **Create any template** with any fields (`Text` / `Number` / `Date`) and per-field
  validation rules in a single call: `POST /Templates/CreateWithFields`.
- **Custom regex validation from the UI** — every pattern is compiled server-side at
  creation time; invalid patterns are rejected with a `400` naming the field, the rule,
  and the exact syntax error. At runtime, regex matching is guarded by a 200 ms deadline
  against catastrophic backtracking (ReDoS).
- Rule engine with pluggable rule types: `REGEX`, `REQUIRED`, `DATE_FORMAT`,
  `EXPIRY_DATE`, `RANGE` (extensible strategy beans).
- Manage individual rules: `GET /ValidationRules/GetByTemplate/{templateId}`,
  `PUT /ValidationRules/Upsert`, `DELETE /ValidationRules/{templateId}/{fieldName}/{ruleType}`.

### Ingest any document into a table
- **Structured files in pure code (no LLM):** `.csv` (Commons CSV) and `.xlsx`/`.xls`
  (Apache POI) are parsed straight into a columns + rows table.
- **Everything else via docling + OCR:** when a [docling-serve](https://github.com/docling-project/docling-serve)
  endpoint is configured, `pdf`, `doc`, `docx`, `pptx`, `html`, and images/scans
  (`png`, `jpg`, `tiff`, `bmp`, `webp`) are converted to markdown with OCR.
- **Automatic fallback readers** when docling is absent or fails: PDFBox for PDFs,
  POI for `.docx`/`.doc`, plus a two-tier OCR chain for standalone images and scanned
  PDF pages — **LLM vision OCR** (the configured OpenAI model, `gpt-4o` by default,
  reading rasterized pages/images) first, then per-page **Tesseract** as the offline
  fallback. Each tier degrades gracefully: no OpenAI key disables vision OCR, no
  native Tesseract library disables local OCR — parsing never fails because of OCR.
- **LLM table extraction** (Spring AI structured output) turns the extracted text of
  unstructured documents into the same table shape.

### Resumable ingestion jobs
- Every ingestion runs as a **job** with per-stage SQL checkpoints:
  `UPLOADED → PARSED → MAPPED → VALIDATED → EXPORTED`.
- Each stage persists its result (parsed table, mapping, mapped rows, validation
  results, export info) to the `IngestionJob` table **before** the next stage starts, so
  a crashed or failed job **resumes from the last completed stage** — even after a
  restart: `POST /api/ingestion/jobs/{id}/resume`.
- Re-run individual stages (`.../remap`, `.../revalidate`), cancel jobs (`.../cancel`),
  and inspect everything (`GET /api/ingestion/jobs`, `GET /api/ingestion/jobs/{id}`).
- **The wizard position is checkpointed too**: `PUT /api/ingestion/jobs/{id}/wizard-step`
  records the pane the user reached (`MAPPING`/`VALIDATION`/`EXPORT`), so reopening a job
  resumes the upload wizard at exactly that step. Mutations that invalidate validation
  (remap, mapping override, chat edit) clamp it back to `MAPPING`.

### Field mapping (code first, AI only when needed)
- Source columns are mapped to template fields by a **tiered, deterministic code
  pipeline**: exact name match → normalized match (case/punctuation-insensitive) →
  **alias & token similarity** (`fname`, `Surname`, `DOB`… — extendable via
  `mapping.aliases`) → **value-shape scoring** that runs sample values through the
  target field's own validation rules and data type (an email regex rule doubles as an
  email detector; samples never leave the backend) → **learned mappings** replayed from
  this template's past completed jobs (your manual corrections become training data).
- Candidates are combined into a **threshold-gated unique assignment**
  (`mapping.confidence-threshold`): each template field is used at most once, and
  low-confidence columns stay unmapped for review instead of being guessed.
- Only the columns code cannot resolve go to the **LLM automapper** (column/field
  names only, never data; disable entirely with `mapping.llm-fallback-enabled=false`);
  invented targets are discarded, and an LLM outage degrades to "left unmapped"
  instead of failing the mapping stage.
- **Review and override** the mapping: `PUT /api/ingestion/jobs/{id}/mapping`
  (`{"mapping": {"source col": "TemplateField" | null}}`) — rebuilds the mapped rows and
  clears stale validation.
- **Edit cell values directly** from the validation table (or via
  `PUT /api/ingestion/jobs/{id}/rows` with the full edited row set) — persists the
  edits and clears stale validation so it can be re-run.

### Validation
- Mapped rows are validated against the template's rules (including your custom regex)
  by the same rule engine as `POST /Validation/validate`, one result per field per row.

### Chat AI table editing
- `POST /api/ingestion/jobs/{id}/chat-edit` with
  `{"instruction": "Fix the validation errors"}` — when the job has validation errors,
  **only the failing rows** (with their error messages) are sent to the LLM, which
  returns a **patch of modified rows** merged back by row index (a fraction of the
  tokens of a full-table round trip). With no validation errors it falls back to
  sending the whole table. Structured output enforces the shape; stale
  validation/export checkpoints are cleared so you revalidate after editing.

### Review & export anywhere
- **Download** the validated rows as **CSV, JSON, XLSX, TSV, or XML**:
  `GET /api/ingestion/jobs/{id}/export/file?format=csv|json|xlsx|tsv|xml` — a copy is
  archived under `wwwroot/exports/`.
- **Push directly to your own database**: `POST /api/ingestion/jobs/{id}/export/database`
  with `{jdbcUrl, username, password, tableName, createTableIfMissing}`. Supports
  `jdbc:postgresql:` and `jdbc:h2:` targets, auto-creates the table when asked, inserts
  in batches. Table/column identifiers are strictly validated **and** quoted; row values
  only ever travel as bound parameters (no SQL injection); credentials are used for the
  one export and never persisted or logged.

### Data extraction from external sources
- Collect structured data from **authorized external sources** — websites, REST APIs
  (the compliant way to pull social-platform data is their **official API** with your
  own credentials), and external JDBC databases — then feed it into the same template
  mapping / validation / export pipeline.
- **Sources** (`/api/extraction/sources`) describe where and what to extract. Creating
  one requires `termsAcknowledged: true` — you confirm you are authorized to access the
  source under its terms of service and applicable data-protection/privacy law.
  The *data spec* (what data you want) is free text, **or select a template**
  (`templateId`): when the text is blank, the spec is derived from the template's
  fields (`Name (Text), HireDate (Date), …`) at job creation, and jobs inherit the
  template for the bridge step.
- **Jobs** (`/api/extraction/jobs`) run distributed: work is split into small leased
  tasks (one page / one API page / one DB batch) claimed by a worker pool. Retries with
  exponential backoff, content-hash deduplication, incremental re-extraction
  (ETag/Last-Modified, API cursors, DB watermarks), pause/resume, and crash recovery
  via lease expiry come built in. Records stream through bounded batches — the whole
  dataset is never held in memory.
- **Storage**: sources, jobs, raw payloads, normalized records, and the audit trail
  persist by default in the **primary SQL database** (`extraction.store=sql`), each
  source/job owned by its user — so everything survives restarts with no extra
  services (jobs interrupted mid-run are marked FAILED at startup and can be re-run).
  **MongoDB** (`extraction.store=mongo`) remains the multi-instance/scale option and
  also persists the task queue; `extraction.store=memory` keeps the test suite
  self-contained. **Kafka** (optional,
  `extraction.kafka.enabled`) fans work signals across app instances — the store stays
  the source of truth, so duplicate or lost signals are harmless.
- **Compliance is enforced in code, not just documented**: every web fetch passes one
  gate that checks the per-source **domain allow-list**, **robots.txt** (fail-closed
  when it cannot be fetched, crawl-delay honored), and a **per-host rate limiter**;
  the crawler sends an honest bot User-Agent, and 401/402/403 (logins, paywalls) are
  terminal skips — never retried, never worked around, no cookies, no CAPTCHA handling.
  Every fetch attempt and every blocked/deduped/retried decision is written to a
  queryable **audit trail** (`GET /api/extraction/jobs/{id}/audit`). The job page in
  the UI polls live progress with running/completed toasts, and shows the audit
  trail — loaded automatically when a job completes without records, so blocked
  fetches are never silent.
- **Secrets are never persisted**: API auth is an env-var reference on the source, or a
  per-run token; DB credentials are supplied per-run and held in process memory only
  (DB tasks are pinned to the node that holds them).
- **LLM agents (optional)**: infer a template schema from sample records
  (`POST .../infer-schema`), clean batches during bridging (`llmClean: true`), and
  summarize a run (`GET .../summary`). All degrade gracefully without an API key.
- **Bridge** (`POST /api/extraction/jobs/{id}/bridge`) hands the records to the
  existing document pipeline: columns are auto-mapped onto your template, rows are
  validated by the rule engine, and a standard ingestion job is created — so review,
  chat editing, and CSV/JSON/XLSX/database export work on extracted data unchanged.
- Websites are fetched with plain HTTP by default; **Playwright** (headless Chromium)
  renders JS-heavy pages when `extraction.web.render-js-enabled=true` and its browsers
  are installed, degrading to HTTP otherwise.

### AI integration (Spring AI)
- All LLM access goes through **Spring AI's `ChatClient`** (OpenAI chat model) behind a
  single application port, with structured output for extraction, mapping, and chat
  edits.
- The same `ChatClient` and key also power **multimodal vision OCR** of images and
  scanned PDF pages (`ocr.vision.*` settings); it switches itself off when no key is
  configured.
- Legacy config keys are bridged, so `AIFILES_OPENAI_APIKEY` / `AIFILES_OPENAI_MODELID`
  keep working. The app **boots without an API key** — only LLM-dependent endpoints
  return an error until one is set.
- Legacy AI endpoints preserved: `POST /api/aifiles/send` (raw prompt),
  `POST /api/transform/transform` (classify + extract to JSON),
  `POST /api/automapper/generate` (column mapping).

### Platform
- Legacy CRUD APIs: `/Templates/*`, `/TemplateFields/*`, `/TemplateDataTypes/*`,
  `/ProcessStep/*`, `/ImportHistory/*`, `/ImportDetails/*`.
- File upload: `POST /api/fileupload/upload` (multipart → `wwwroot/uploads/`).
- **Swagger UI** at `/swagger-ui` (static contract at `/swagger/spec.yaml`).
- `local` profile: same PostgreSQL + Flyway as production, plus dev conveniences
  (Swagger, in-memory extraction). The JWT signing key is **not** committed —
  supply `MODOCUMENT_JWT_SECRET` or a gitignored `application-local-secrets.yml`.
- **Docker**: multi-stage `Dockerfile` + `docker-compose.yml` (app + PostgreSQL +
  docling-serve), non-root runtime image with a healthcheck.
- CI: GitHub Actions runs `mvn -B verify` on every push/PR to `main`.

### Document creation (blueprint → sections → fill → generate)
- **Upload your company's report (.docx) as a blueprint** — the engine breaks it
  down into sections. An **AI breakdown runs first**: the LLM reads the
  document's paragraph listing and identifies every logical section the way a
  human reader would (styled headings, numbered lines, bold titles, labelled
  parts), returning validated anchors into the real document. When AI is
  disabled, unconfigured, over quota, or returns anything that doesn't check
  out, a deterministic heuristic takes over (outline levels or `Heading N`
  styles, a bold-paragraph fallback, and a single implicit section for
  heading-less files) — uploads never fail for lack of an API key. The
  uploaded file itself is kept untouched as the source of truth.
  Configure with `MODOCUMENT_DOCCREATION_SECTION_AI` (default `true`) and
  `MODOCUMENT_DOCCREATION_SECTION_MODEL` (default: the global model).
- **Fill each section** with paragraphs and pictures. Bulk-drop all images at
  once: file names are matched against each section's editable keywords ("roof"
  claims `Roof_East_02.jpg`, "dc2" claims `dc2-overview.png`) and placed
  automatically; unmatched images wait in an **Unsorted tray** instead of being
  lost.
- **One click reproduces the document in that exact manner** — the **AI
  composes the final document**: it is handed the full template plus everything
  you added and returns the complete document as an assembly plan (where each
  paragraph and image goes, wording polished into the document's tone). The
  plan is validated so the output is provably exact — every original element
  kept once, in order; every user block placed exactly once; nothing invented —
  and rendered onto the original file, so layout, styles and letterhead survive
  byte-for-byte. The deterministic engine takes over whenever AI is disabled
  (`MODOCUMENT_DOCCREATION_GENERATE_AI=false`), unconfigured, over quota, or
  its plan fails validation. Output: an editable **.docx** always, plus **PDF**
  when LibreOffice is available (it ships in the Docker image; without it the
  PDF endpoint answers 409 and the .docx keeps working).
- **Per-company flows:** the flow behind the engine is a strategy bean selected
  by the blueprint's `companyId` (`DocumentCreationFlow` → section parsing,
  image auto-assignment, generation decoration). The DEFAULT flow covers
  everything above; giving a company its own flow is one new `@Component`
  implementing the interface — nothing else changes. There is no Company table
  yet: `companyId` is a free-form key, and every blueprint/document is owned by
  the user who created it like all other data.

## Quick start

### Run locally (no OpenAI key needed)

```bash
# start a database once (or point MODOCUMENT_DB_* at your own PostgreSQL)
docker compose up -d postgres

# provide a JWT signing key — no secret is committed, even for local dev.
# (Alternatively, copy application-local-secrets.yml.example to
# application-local-secrets.yml and put the key there; it is gitignored.)
export MODOCUMENT_JWT_SECRET=$(openssl rand -base64 48)

SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Runs against PostgreSQL exactly like production: Flyway creates and updates the
schema on startup (`src/main/resources/db/migration`), lookup data is seeded by
the migrations, and your data survives restarts. Users, templates, fields and
validation rules are created through the app — accounts are admin-created, and
the first administrator is seeded at startup from
`MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` / `MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD` when
the user table is empty. All CRUD, template, validation, job, and
export endpoints work immediately; only LLM-dependent paths (unstructured
extraction, automap leftovers, chat edit) need an API key:

```bash
AIFILES_OPENAI_APIKEY=<your-openai-key> SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

### Run with Docker

```bash
# production-style stack: app + PostgreSQL (Flyway-managed schema).
# The JWT signing secret is required — the app refuses to boot without one.
export MODOCUMENT_JWT_SECRET=$(openssl rand -base64 48)
# First-run administrator (self-registration is disabled):
export MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
export MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD=<a-strong-password>
docker compose up --build

# with an API key for the LLM features (OpenRouter by default; any
# OpenAI-compatible endpoint works via AIFILES_OPENAI_BASEURL)
AIFILES_OPENAI_APIKEY=<your-key> docker compose up --build

# dev mode against the same PostgreSQL (dev JWT secret, Swagger enabled)
APP_PROFILES=local docker compose up --build

# extraction at scale: MongoDB store + Kafka work distribution
APP_PROFILES=scale docker compose --profile extraction up --build
```

The web frontend — and the API through its nginx proxy — is on
<http://localhost:3000>. The app's own port 8080 is intentionally NOT
published: nginx is the single entry point and overwrites the
`X-Forwarded-*` headers the app trusts for per-IP rate limiting, audit IPs
and HSTS (trusted-proxy model — a directly reachable app port would make
them spoofable). For direct-to-app tooling (raw `curl` on `:8080`, Swagger
UI at <http://localhost:8080/swagger-ui> in dev mode), uncomment the
loopback-only `ports` mapping on the `app` service in `docker-compose.yml`.

### Web frontend (React)

A lightweight React UI lives in [`frontend/`](frontend): dashboard, template &
field creation, validation rules, the file-upload pipeline (parse → map →
validate → export), website data extraction, and profile/settings. It is served
by nginx in Docker (`frontend` service, port 3000), or run it against a locally
running backend with:

```bash
cd frontend
npm install
npm run dev          # http://localhost:3000, proxies API calls to :8080
npm test             # unit tests (Vitest + React Testing Library)
```

### Authentication

All API endpoints (except `/api/auth/login`, `/api/auth/refresh`, and
`/actuator/health`) require a JWT Bearer token. Public self-registration is
disabled: the first administrator is created at startup from
`MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` / `MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD`
(required when the user table is empty), and further accounts are
admin-created. Log in with that account:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"<your-password>"}'
# -> { "token": "...", "user": { ... } }

curl http://localhost:8080/Templates/GetAll -H "Authorization: Bearer <token>"
```

Endpoints: `POST /api/auth/login`, `POST /api/auth/refresh`,
`POST /api/auth/logout`, `GET /api/auth/me`, `PUT /api/auth/profile`,
`PUT /api/auth/password`. Access tokens are short-lived
(`modocument.security.jwt.ttl-minutes`, default 15); login also sets a rotating
7-day refresh token in an httpOnly `modoc_refresh` cookie scoped to
`/api/auth/refresh`. Logout revokes the access token's jti and every refresh
token server-side, and a password change invalidates every outstanding token
(sign in again).
`MODOCUMENT_JWT_SECRET` (≥32 bytes, e.g. `openssl rand -base64 48`) is **required**
in production and on `local` — the app fails fast at startup if it is missing, too
short, or (in production) set to the old dev default. No secret is committed for
`local`; set the env var or the gitignored `application-local-secrets.yml`. Only
the `test` profile ships a fixed dev-only key, so the suite is deterministic.

**Single sign-on (OIDC, optional)**: bank users are expected to sign in
through an external identity provider — Entra ID for Microsoft banks,
Keycloak (shipped behind the `keycloak` compose profile) for everyone else —
with MFA enforced at the provider. The IdP only authenticates; the app then
issues its own tokens exactly as above (the same refresh cookie; the SPA
lands on `/login/complete` — no token ever travels in a URL). Password login
stays as the break-glass path. Nothing is active until
`modocument.oidc.providers.*` is configured: see
`application-oidc.yml.example` and `docs/security/identity-providers.md`.

Both open auth endpoints are rate-limited per IP and per email
(`modocument.security.rate-limit.auth.*`), and the LLM-backed endpoints
(`/api/aifiles/send`, `/api/automapper/generate`, chat-edit) carry a per-user
quota (`modocument.security.rate-limit.llm.*`); exceeding either returns `429`.

### Build & test

```bash
mvn -B verify
```

## Configuration

| Setting | Key / env var | Default |
|---|---|---|
| OpenAI API key | `aifiles.openai.api-key` / `AIFILES_OPENAI_APIKEY` | empty (LLM endpoints error until set) |
| OpenAI model | `aifiles.openai.model-id` / `AIFILES_OPENAI_MODELID` | `gpt-4o` |
| OpenAI base URL | `aifiles.openai.base-url` / `AIFILES_OPENAI_BASEURL` | `https://api.openai.com` (host root, no `/v1`) |
| PII tokenization | `aifiles.privacy.tokenize-pii` / `AIFILES_PRIVACY_TOKENIZEPII` | `true` (fail closed) |
| Mapping LLM fallback | `mapping.llm-fallback-enabled` / `MAPPING_LLMFALLBACK_ENABLED` | `true` (code tiers always run first) |
| Mapping confidence | `mapping.confidence-threshold` (+ `sample-size`, `history-jobs`, `aliases`) | `0.7`, 20 samples, 25 jobs |
| Extra sensitive fields | `aifiles.privacy.extra-sensitive-fields` | empty (substring match, case/punct-insensitive) |
| docling-serve URL | `docling.base-url` / `DOCLING_BASEURL` | empty (built-in readers only) |
| Vision OCR | `ocr.vision.enabled` / `OCR_VISION_ENABLED` | `true` (active only when an OpenAI key is set) |
| Vision OCR model | `ocr.vision.model` / `OCR_VISION_MODEL` | `gpt-4o` |
| Vision OCR page budget | `ocr.vision.max-pages` / `OCR_VISION_MAXPAGES` (+ `dpi`, `max-dimension`) | 20 pages/PDF, 200 DPI, 2048px longest edge |
| JWT signing secret | `modocument.security.jwt.secret` / `MODOCUMENT_JWT_SECRET` | **required** in production and `local` (≥32 bytes; not committed); fixed dev secret on `test` only |
| Auth rate limit | `modocument.security.rate-limit.auth.*` (`per-ip-max`, `per-email-max`, `window-millis`) | 30/IP, 10/email per 15 min → `429` |
| LLM quota | `modocument.security.rate-limit.llm.*` (`per-user-max`, `window-millis`) | 60/user per hour → `429` |
| Database | `spring.datasource.url` / `MODOCUMENT_DB_URL` (+ `MODOCUMENT_DB_USERNAME`, `MODOCUMENT_DB_PASSWORD`) | PostgreSQL `localhost:5432/modocument` |
| CORS origins | `modocument.cors.allowed-origins` / `MODOCUMENT_CORS_ALLOWED_ORIGINS` | localhost dev origins — set your frontend origin in production |
| Storage root | `modocument.storage.root` / `MODOCUMENT_STORAGE_ROOT` | `wwwroot` (uploads + export copies; mount a volume) |
| Profile | `SPRING_PROFILES_ACTIVE` | default (PostgreSQL); `local` adds dev conveniences, `scale` adds Mongo+Kafka extraction |
| Extraction store | `extraction.store` / `EXTRACTION_STORE` | `memory`; set `mongo` (or the `scale` profile) for multi-instance runs |
| MongoDB URI | `spring.data.mongodb.uri` / `EXTRACTION_MONGO_URI` | `mongodb://localhost:27017/modocument` |
| Kafka distribution | `extraction.kafka.enabled` / `EXTRACTION_KAFKA_ENABLED` | `false` (workers poll the store queue) |
| Kafka brokers | `spring.kafka.bootstrap-servers` / `EXTRACTION_KAFKA_SERVERS` | `localhost:9092` |
| Worker pool | `extraction.worker.threads` (+ `lease-seconds`, `max-attempts`, `backoff-millis`) | 4 threads, 60s lease, 4 attempts |
| Crawler identity | `extraction.web.user-agent` | `MoDocumentBot/1.0 (+https://example.com/bot)` — set a real contact URL |
| Crawl politeness | `extraction.web.default-delay-millis` (robots crawl-delay always honored) | `1000` |
| Crawl caps | `extraction.web.max-pages-default` / `max-depth-default` | 100 pages, depth 2 |
| JS rendering | `extraction.web.render-js-enabled` (needs Playwright browsers) | `false` |

LLM calls go through [Spring AI](https://docs.spring.io/spring-ai/reference/); the
legacy `aifiles.openai.*` keys are bridged into the Spring AI OpenAI auto-configuration,
so existing environment variables keep working. Never commit real keys — pass them as
environment variables.

### Privacy: PII tokenization

The LLM chat/analysis features manipulate your data **without seeing raw sensitive
values**. Before a prompt leaves the backend, sensitive cells are replaced with stable
placeholders (`[NAME_1]`, `[EMAIL_2]`, `[ID_1]`, …); only the backend holds the
per-request mapping, and the placeholders are swapped back in the model's answer — the
model edits structure and tokens, the backend re-substitutes reality (a blind-patch
pipeline). Detection combines field-name heuristics (names, emails, phones, government
IDs, addresses, birth dates, gender, pay — extendable via
`aifiles.privacy.extra-sensitive-fields`) with value-shape patterns (email, phone,
9-digit SIN/SSN, 13–19-digit card) for unlabeled columns. Applied to: chat table
edits, LLM record cleaning, schema inference, extraction summaries, and (mask-only)
the template-classification snippet. Plain numbers/dates in non-sensitive columns
deliberately stay raw so format cleanup still works.

Boundaries to know: document-to-table extraction (`TableExtraction` / `POST
/Transform`), vision OCR, and the raw `POST /api/aifiles/send` passthrough inherently
send document content to the LLM — finding values in raw text *is* their job.
Tokenized cells cannot be content-reformatted by the model (it never sees them), and
prose summaries may paraphrase a placeholder — which degrades to vagueness, never to a
leak. Detection is heuristic, not NER: a bare name in an unlabeled free-text column
with no recognizable shape passes through.

## API overview

Interactive documentation: **Swagger UI at `/swagger-ui`**, rendering the static
contract at `/swagger/spec.yaml`. Summary:

### Templates & validation rules

| Endpoint | Purpose |
|---|---|
| `POST /Templates/CreateWithFields` | Create template + fields + validation rules (regex verified) in one transaction |
| `GET /ValidationRules/GetByTemplate/{templateId}` | List a template's rules |
| `PUT /ValidationRules/Upsert` | Insert/update one rule (regex verified) |
| `DELETE /ValidationRules/{templateId}/{fieldName}/{ruleType}` | Delete one rule |
| `/Templates/*`, `/TemplateFields/*`, `/TemplateDataTypes/*` | Legacy CRUD |

### Ingestion pipeline

| Endpoint | Purpose |
|---|---|
| `POST /api/ingestion/jobs` | Create a job — multipart (`file`, `templateId`, `autoRun`) or JSON (`fileName`, `templateId`, `autoRun`); `autoRun` (default `true`) runs parse → map → validate |
| `GET /api/ingestion/jobs`, `GET /api/ingestion/jobs/{id}` | List/inspect jobs with all checkpoints |
| `POST /api/ingestion/jobs/{id}/resume` | Continue from the last completed stage (incl. after `FAILED`) |
| `POST /api/ingestion/jobs/{id}/remap` / `.../revalidate` | Re-run one stage |
| `POST /api/ingestion/jobs/{id}/cancel` | Cancel (terminal) |
| `PUT /api/ingestion/jobs/{id}/mapping` | Apply a reviewed mapping override |
| `POST /api/ingestion/jobs/{id}/chat-edit` | Modify the table with a natural-language instruction |

### Export

| Endpoint | Purpose |
|---|---|
| `GET /api/ingestion/jobs/{id}/export/file?format=csv\|json\|xlsx\|tsv\|xml` | Download validated rows (copy archived in `wwwroot/exports/`) |
| `POST /api/ingestion/jobs/{id}/export/database` | Insert validated rows into a user-supplied PostgreSQL/H2 database |

### Data extraction

| Endpoint | Purpose |
|---|---|
| `POST /api/extraction/sources` | Create a source (requires `termsAcknowledged: true`; website sources need a domain allow-list) |
| `GET/PUT/DELETE /api/extraction/sources/{id}` | Manage sources |
| `POST /api/extraction/jobs` | Create (and by default start) a distributed extraction job; optional per-run `credentials` stay in memory only |
| `POST /api/extraction/jobs/{id}/start\|pause\|resume\|cancel` | Job lifecycle |
| `GET /api/extraction/jobs/{id}` | Status with live task counts and counters |
| `GET /api/extraction/jobs/{id}/records?kind=raw\|processed` | Cursor-paged record peek |
| `GET /api/extraction/jobs/{id}/audit` | Cursor-paged audit trail (fetches, robots/domain blocks, retries, dedups, LLM calls) |
| `POST /api/extraction/jobs/{id}/bridge` | Hand records to the document pipeline (auto-map + validate → standard ingestion job) |
| `POST /api/extraction/jobs/{id}/infer-schema` | LLM: suggest a template schema from sample records |
| `GET /api/extraction/jobs/{id}/summary` | LLM: short human summary of the run |
| `GET /api/extraction/metrics` | Queue depth, compliance counters, worker pool state |

### Legacy pipeline & utilities

| Endpoint | Purpose |
|---|---|
| `POST /api/fileupload/upload` | Multipart upload to `wwwroot/uploads/` |
| `POST /api/transform/transform` | Classify a file against a template and extract fields to JSON via the LLM |
| `POST /api/automapper/generate` | LLM column-to-schema mapping |
| `POST /Validation/validate` | Run rows through a template's rule engine |
| `POST /api/aifiles/send` | Send a raw prompt to the chat model |
| `/ProcessStep/*`, `/ImportHistory/*`, `/ImportDetails/*` | Legacy CRUD |

### Document creation

| Endpoint | Purpose |
|---|---|
| `POST /api/doccreation/blueprints` | Upload a .docx blueprint (multipart `file` + optional `name`, `companyId`); parsed into sections |
| `GET /api/doccreation/blueprints[/{id}]` | List / get blueprints with their sections |
| `GET /api/doccreation/blueprints/{id}/file` | Download the original blueprint |
| `PUT .../blueprints/{id}/sections/{sectionId}/keywords` | Tune a section's image auto-assignment keywords |
| `DELETE /api/doccreation/blueprints/{id}` | Delete (409 while documents build on it) |
| `POST /api/doccreation/instances` | Start a document from a blueprint |
| `GET /api/doccreation/instances/{id}` | Builder view: sections + blocks + Unsorted tray + `pdfAvailable` |
| `POST .../instances/{id}/sections/{sectionId}/blocks` | Add a paragraph or image block |
| `PUT /api/doccreation/blocks/{blockId}` / `DELETE` | Edit / remove a block (an image block's asset returns to Unsorted) |
| `PUT .../instances/{id}/sections/{sectionId}/blocks/order` | Reorder a section's blocks |
| `POST /api/doccreation/instances/{id}/assets` | Bulk image upload with keyword auto-assignment |
| `GET /api/doccreation/assets/{assetId}/content` | Image bytes for authenticated previews |
| `PUT /api/doccreation/assets/{assetId}/assignment` | Place an image in a section or back to Unsorted |
| `POST /api/doccreation/instances/{id}/generate` | Reproduce the document (.docx always, PDF when available) |
| `GET /api/doccreation/instances/{id}/download?format=docx\|pdf` | Download the generated file (regenerates when stale) |

## Ingestion walkthrough

```
upload ──> PARSED ──────> MAPPED ─────────> VALIDATED ──────> EXPORTED
           table from     columns → fields  rule engine       csv/json/xlsx
           code or        (code first,      (incl. custom     or your own
           docling/OCR    LLM leftovers)    regex)            database
           + LLM
```

```bash
# 1. create a template with a custom regex rule
curl -X POST localhost:8080/Templates/CreateWithFields -H 'Content-Type: application/json' -d '{
  "name": "Contacts",
  "fields": [
    {"fieldName": "FullName", "dataType": "Text",   "rules": [{"ruleType": "REQUIRED", "config": {}}]},
    {"fieldName": "Email",    "dataType": "Text",   "rules": [{"ruleType": "REGEX", "config": {"Pattern": "^[\\w.+-]+@[\\w-]+\\.[\\w.]+$"}}]},
    {"fieldName": "Age",      "dataType": "Number", "rules": [{"ruleType": "RANGE", "config": {"Min": 0, "Max": 120}}]}
  ]}'

# 2. ingest a document against it (parse -> map -> validate in one call)
curl -X POST localhost:8080/api/ingestion/jobs -F file=@contacts.csv -F templateId=100

# 3. review: fix the mapping if needed, then revalidate
curl -X PUT  localhost:8080/api/ingestion/jobs/1/mapping -H 'Content-Type: application/json' \
     -d '{"mapping": {"Full Name": "FullName", "email": "Email", "Age": null}}'
curl -X POST localhost:8080/api/ingestion/jobs/1/revalidate

# 4. optionally edit via chat (needs an OpenAI key)
curl -X POST localhost:8080/api/ingestion/jobs/1/chat-edit -H 'Content-Type: application/json' \
     -d '{"instruction": "Capitalize all names"}'

# 5. export — download a file, or push to your own database
curl -OJ "localhost:8080/api/ingestion/jobs/1/export/file?format=xlsx"
curl -X POST localhost:8080/api/ingestion/jobs/1/export/database -H 'Content-Type: application/json' \
     -d '{"jdbcUrl": "jdbc:postgresql://myhost:5432/staging",
          "username": "loader", "password": "***", "tableName": "Contacts", "createTableIfMissing": true}'
```

If a stage fails (LLM outage, unreadable scan, ...), the job stores the error and stays
at its last completed step — fix the cause and `POST .../resume`.

## Extraction walkthrough

```
source ──> tasks ─────────> raw records ──> processed records ──> bridge ──> ingestion job
(website/  leased by        content-hash    normalized flat        auto-map   review, chat
 API/DB)   worker fleet,    deduplicated    rows, deterministic    + rule     edit, export
           robots + rate    (Mongo)         cleanup (+ optional    engine     (same as any
           limits enforced                  LLM cleaning)                     document)
```

```bash
# 1. register a source you are authorized to extract from
curl -X POST localhost:8080/api/extraction/sources -H 'Content-Type: application/json' -d '{
  "name": "Public docs site",
  "type": "WEBSITE",
  "termsAcknowledged": true,
  "dataSpec": "employee names and numbers from the staff tables",
  "web": {"seedUrls": ["https://docs.example.com/staff"],
          "allowedDomains": ["example.com"], "maxDepth": 2, "maxPages": 50}}'

# 2. run an extraction job (distributed workers, robots.txt + rate limits enforced)
curl -X POST localhost:8080/api/extraction/jobs -H 'Content-Type: application/json' \
     -d '{"sourceId": "<source-id>"}'

# 3. watch progress, peek at records, check the audit trail
curl localhost:8080/api/extraction/jobs/<job-id>
curl "localhost:8080/api/extraction/jobs/<job-id>/records?kind=processed&limit=10"
curl "localhost:8080/api/extraction/jobs/<job-id>/audit"

# 4. optional LLM help: suggest a template schema from the extracted records
curl -X POST localhost:8080/api/extraction/jobs/<job-id>/infer-schema

# 5. bridge into the document pipeline, then use the normal review/export endpoints
curl -X POST localhost:8080/api/extraction/jobs/<job-id>/bridge \
     -H 'Content-Type: application/json' -d '{"templateId": 100, "llmClean": true}'
curl -OJ "localhost:8080/api/ingestion/jobs/<ingestion-job-id>/export/file?format=csv"
```

A REST API source pulls pages through official APIs (`"type": "REST_API"`, auth via an
env-var reference or a per-run `credentials.apiToken`); a database source
(`"type": "DATABASE"`) streams watermark-ordered batches with per-run
`credentials.dbUsername`/`dbPassword` that are never persisted.

**Responsible use**: only extract from sources you are authorized to access. The
service enforces robots.txt, domain allow-lists, rate limits, and never bypasses
logins, paywalls, CAPTCHAs, or anti-bot measures — but *you* remain responsible for
complying with each source's terms of service and with data-protection law (GDPR and
similar) for any personal data you collect, store, or process.

## Architecture

Single Maven module with Clean Architecture layering by package:

```
com.modocument
├── domain           entities (JPA) + Mongo documents + value objects/enums
├── application      DTOs, ports (interfaces), use cases, validation messages
├── infrastructure   JPA repositories, Spring AI ChatClient adapter, prompt strategies,
│                    file readers (POI/Commons CSV/PDFBox + vision LLM OCR with
│                    optional Tess4J fallback),
│                    docling-serve client, transform/ingestion pipeline,
│                    validation rules, file/database export,
│                    extraction (Mongo + in-memory stores, lease queue, Kafka event
│                    bus, worker pool, robots/rate-limit compliance, web/API/JDBC
│                    connectors)
└── api              REST controllers + web config (CORS, case-insensitive routes)
```

Extraction runs on a lease-based task queue: the store (MongoDB in production,
in-memory locally) is the source of truth for every task's lease/attempt state, and
Kafka — when enabled — only distributes "work available" signals across instances, so
duplicate or lost signals are harmless and any instance can pick up a crashed
instance's tasks once their leases expire. Scale out by running more app instances.

## Database

- **Production (PostgreSQL):** the schema is created and versioned by **Flyway**
  (`src/main/resources/db/migration/` — `V1__baseline_schema.sql` +
  `V2__lookup_seed.sql`) automatically on startup; Hibernate runs with
  `ddl-auto: validate` and only checks the mapping. Point the app at your server
  with `MODOCUMENT_DB_URL` / `MODOCUMENT_DB_USERNAME` / `MODOCUMENT_DB_PASSWORD`.
  Only lookup data is seeded — no accounts, templates, or rules.
- **Local development (PostgreSQL):** identical to production — the `local`
  profile inherits the datasource and Flyway migrations, so schema changes are
  exercised in dev exactly as they will run in prod. Ship a schema change as a
  new `V<n>__description.sql` file under `src/main/resources/db/migration/`;
  the backend applies pending migrations on the next start (see `V3__foreign_key_indexes.sql`
  for an incremental example).
- **Tests (H2):** the test suite keeps fast in-memory H2 — schema auto-created
  from the JPA entities and seeded from `src/main/resources/data.sql`
  (data-type and process-step lookups only); Flyway is disabled there.

### Hosted PostgreSQL (Supabase, RDS, Neon, …)

Any hosted PostgreSQL works — point the `MODOCUMENT_DB_*` variables at it and
the backend migrates the schema on first boot. Supabase example:

```bash
# Session pooler connection (Dashboard → Connect). Use the SESSION pooler on
# port 5432 — it is IPv4-friendly and keeps prepared statements, which JDBC/
# Hibernate/Flyway need. Do NOT use the transaction pooler (port 6543), and
# note the direct db.<ref>.supabase.co host requires IPv6.
export MODOCUMENT_DB_URL="jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require"
export MODOCUMENT_DB_USERNAME="postgres.<project-ref>"
export MODOCUMENT_DB_PASSWORD="<database password>"   # never commit this
export MODOCUMENT_JWT_SECRET="$(openssl rand -base64 48)"

mvn spring-boot:run
```

On the first boot the log shows Flyway applying `V1__baseline_schema`,
`V2__lookup_seed` and `V3__foreign_key_indexes`, after which the tables are
visible in Supabase's Table Editor; later boots print "Schema … is up to
date." The same variables work for the Docker image and the compose `app`
service (the bundled `postgres` service is then unnecessary).

## Docker

- `Dockerfile` — multi-stage: Maven + Temurin 21 build, slim JRE 21 runtime image
  that runs as a non-root user and healthchecks `/actuator/health`.
- `docker-compose.yml` —
  - `app`: this API on port 8080, wired to the `postgres` service by default.
    Requires `MODOCUMENT_JWT_SECRET` in your environment; pass
    `AIFILES_OPENAI_APIKEY` through for the LLM features. Uploads/exports are kept
    in a named volume. `APP_PROFILES=local` runs the dev profile against the
    same database.
  - `postgres`: PostgreSQL 17 with a named volume and a `pg_isready` healthcheck;
    the app waits for it to be healthy and Flyway migrates the schema on boot.
    Override `POSTGRES_USER`/`POSTGRES_PASSWORD` (the compose default is for local
    use only).
  - `docling` (optional, `--profile docling` + `DOCLING_BASEURL=http://docling:5001`):
    [docling-serve](https://github.com/docling-project/docling-serve) on port 5001 for
    rich layout-aware conversion — not needed for the standard flow. Note
    the slim app image has no native Tesseract, so the local OCR fallback silently
    disables itself there — but with `AIFILES_OPENAI_APIKEY` set the container still
    gets full-quality image/scan OCR via the vision model, even without docling.
  - `mongodb` + `kafka` (optional, `--profile extraction`): the extraction store and
    work-distribution bus. Run `APP_PROFILES=scale docker compose --profile
    extraction up --build` to switch the app onto them. JS rendering (Playwright) is
    off in the container image — the crawler uses plain HTTP there; install the
    Playwright browsers locally (`npx playwright install chromium` or
    `mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D
    'exec.args=install chromium'`) and set `extraction.web.render-js-enabled=true`
    to enable it in local runs.

## Production deployment checklist

- **`MODOCUMENT_JWT_SECRET`** — required; generate with `openssl rand -base64 48`.
  The app refuses to start with a missing, short, or known-default secret.
- **Database** — set `MODOCUMENT_DB_URL` / `MODOCUMENT_DB_USERNAME` /
  `MODOCUMENT_DB_PASSWORD` to a PostgreSQL instance with a strong password;
  Flyway migrates the schema on startup.
- **CORS** — set `MODOCUMENT_CORS_ALLOWED_ORIGINS` to your frontend origin(s).
- **TLS** — terminate HTTPS at your reverse proxy / load balancer in front of the
  frontend (nginx, port 80) and the API; nothing in the stack serves TLS itself.
- **Health** — probe `GET /actuator/health` (unauthenticated); the Docker image and
  compose file already use it.
- **Files** — mount a volume at the storage root (`MODOCUMENT_STORAGE_ROOT`,
  default `wwwroot/`) so uploads and export copies survive restarts.
- **API docs** — Swagger/OpenAPI endpoints are disabled outside the `local`/`test`
  profiles; the H2 console permit is likewise dev-only.
