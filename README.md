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
  POI for `.docx`/`.doc`, plus an OCR chain for standalone images and scanned PDF
  pages — per-page **Tesseract** by default, with optional **LLM vision OCR**
  (rasterized pages/images sent to a vision-capable model) ahead of it. Vision OCR
  is **off by default** (`OCR_VISION_ENABLED=false`): page images cannot be
  pseudonymised the way text prompts are, so sending them to a model is an explicit
  opt-in. Each tier degrades gracefully — parsing never fails because of OCR.
- **LLM table extraction** (Spring AI structured output) turns the extracted text of
  unstructured documents into the same table shape.

### Code-based fallback and extraction modes
When the AI cannot produce the table, a **code-based extractor** reads it instead, with
no LLM involved.
- **How it reads a document.** Several strategies run and the best-scoring table wins:
  - delimited text (delimiter sniffing, quotes, BOM, windows-1252);
  - native spreadsheet cells;
  - markdown and `Row N:` tables;
  - whitespace-aligned columns (PDF text);
  - `Label: value` forms, one record per repeated block;
  - per-field value regexes.
- **Confidence.** Each candidate gets a score: 0.4 field coverage + 0.3 value shape +
  0.2 structure + 0.1 row-count agreement. It is accepted at
  `extraction.code.minConfidence` (default 0.75).
- **Normalisation.** Dates become ISO, using the field's own date formats first.
  Numbers are read locale-aware.
- **Extraction mode.** Each template sets one (`extractionMode`); `null` inherits the
  bank's `extraction.defaultMode` setting.
  - `AI_WITH_CODE_FALLBACK` (default): AI first. Code takes over in these cases:
    - the bank turned AI off;
    - no key is configured;
    - the AI circuit breaker is open;
    - the user's LLM quota is spent;
    - the model fails, times out, returns something unusable or is withheld by the
      output scanner;
    - the gateway refuses the prompt.

    In batched extraction only the failed batches go to code, recorded as
    `AI_PARTIAL`. So do the remaining batches once the 10-minute AI budget is
    spent. If code cannot reach the threshold either, the job fails, naming both
    failures.
  - `CODE_ONLY`: never calls the AI. Scores from 0.40 pass with a low-confidence flag.
  - `AI_ONLY`: the AI or a failed job (the pre-phase-2 behaviour).
- **Field hints help the extractor.** Each template field may carry:
  - `aliases`: alternative header or label texts, which the column auto-mapper uses
    too;
  - `labelPattern`: a regex for the label;
  - `valuePattern`: a regex with one capture group;
  - `dateFormats`.

  The patterns are vetted against ReDoS on save. A dangerous or invalid one is a 400.
- **Provenance on every job.** The job records `parsedBy` (`AI` / `CODE` /
  `AI_PARTIAL`), `parseStrategy`, `parseConfidence` and `parseFallbackReason`. Each
  fallback is audited as `INGESTION_PARSE_FALLBACK` (labels and the score only).
- **Fast failure.** A failing model reaches the fallback within seconds: one retry on
  a 5xx, a 5 s connect timeout and a 120 s read timeout (`modocument.ai.http.*`,
  `spring.ai.retry.*`).
- Structured files (CSV, Excel, clean markdown tables) are parsed by code first in
  every mode, so they never wait for the AI.

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
  names plus **pseudonymised sample values** — identifiers are replaced with
  `[ID_1]`-style tokens before the prompt, so the shape signal survives but no raw
  value leaves the backend; disable entirely with `mapping.llm-fallback-enabled=false`);
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
  with `{vendor, host, port, database, schema, sslmode, username, password, tableName,
  createTableIfMissing}`. The vendor is `POSTGRESQL`, `ORACLE`, `SQLSERVER` or `MARIADB`.
  The server builds the JDBC URL itself (the integration hub's `DbDialect`), with
  allow-listed options and connect, login, socket and query timeouts. A raw `jdbcUrl` is
  refused since 1.2.0. The destination passes the bank's egress check. The table is
  created when asked, and rows are inserted in one all-or-nothing batch. Table/column identifiers are strictly validated **and** quoted; row values
  only ever travel as bound parameters (no SQL injection); credentials are used for the
  one export and never persisted or logged.
- **Per-role export row caps** (ws18, gate D2): above the cap (READ_WRITE 5,000,
  ADMIN 50,000 rows — `MODOCUMENT_EXPORT_CAP_*`) an export is refused with
  403 `EXPORT_CAP_EXCEEDED` unless the user states a reason; every export,
  refusal and override is audited and alerted. Details:
  [docs/security/alerting-and-export-caps.md](docs/security/alerting-and-export-caps.md).

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
- Websites are fetched with plain HTTP (server-rendered HTML). JavaScript rendering
  (Playwright) was removed in v1.2.0 (ws29) — its ~200 MB driver was more than half
  the application jar and the image never carried the browsers; a source with
  `renderJs` set is fetched as plain HTTP, as it always was in the container.

### AI integration (Spring AI) — local by default, gated and audited
- **Local models are the default** (gate C8): out of the box the app talks to a
  local [Ollama](https://ollama.com) endpoint (`http://localhost:11434`, models
  `qwen2.5` + `mistral`) — **no document content leaves the machine unless an
  operator explicitly configures an external endpoint** via
  `MODOCUMENT_AI_BASE_URL` / `MODOCUMENT_AI_API_KEY` / `MODOCUMENT_AI_MODEL`
  (the legacy `AIFILES_OPENAI_*` names keep working). The app boots and passes
  its suite with **no AI endpoint reachable at all**.
  - Ingestion and the transform fall back to code extraction. So do mapping,
    document creation, record cleaning and OCR.
  - Chat edits, schema inference, run summaries and the raw AI endpoints answer 503.
  - Feature by feature, with the AI circuit breaker and the per-bank AI switch:
    [docs/security/ai-controls.md](docs/security/ai-controls.md#availability-and-code-fallback-ws23).
- **One gateway, no raw paths** (gate C9): every LLM call goes through the single
  `AiGateway` (`infrastructure/ai`) — a guard test fails the build on any other
  class touching the Spring AI chat types. Call sites pseudonymise identifiers
  (NIC/account/email/phone shapes → reversible `[ID_1]`-style tokens) before the
  prompt; the gateway re-scans the assembled prompt and refuses anything still
  carrying a raw identifier shape, anything whose bound tenant is missing or
  mismatched, and any prompt that would mix two banks' data.
- **Output scanning** (gate C10): every completion is scanned before it is
  returned — identifier shapes that were never in the request and
  instruction-injection echoes are rejected (strict purposes fall back to their
  non-AI path) or scrubbed and flagged (lenient purposes), with audit events
  either way.
- **Prompts and completions are not stored and never logged**: logs and
  `AI_CALL` audit evidence carry purposes, models and lengths only.
- **Vision OCR is off by default** (`OCR_VISION_ENABLED=false`): page images
  cannot be pseudonymised, so sending them to a vision model is an explicit
  opt-in; local Tesseract OCR remains the default.
- Legacy AI endpoint, deprecated in 1.2.0 (through the gateway, tokenized):
  `POST /api/automapper/generate` (column mapping). The open prompt proxy
  `POST /api/aifiles/send` and `POST /api/transform/transform` were removed in
  1.2.0 ([docs/api/versioning-policy.md](docs/api/versioning-policy.md) §7).
- Full control description: [docs/security/ai-controls.md](docs/security/ai-controls.md).

### Platform
- Legacy CRUD APIs: `/Templates/*`, `/TemplateFields/*`, `/TemplateDataTypes/*`,
  `/ProcessStep/*`, `/ImportHistory/*`, `/ImportDetails/*`. The unused ones are
  deprecated (sunset 2027-04-01) and answer with `Deprecation`/`Sunset` headers, see
  [docs/api/versioning-policy.md](docs/api/versioning-policy.md) §7.
- File upload: `POST /api/fileupload/upload` (multipart → `wwwroot/uploads/`; deprecated,
  use the multipart `POST /api/ingestion/jobs`).
- **Audit viewer API** (1.2.0): `GET /api/admin/audit` (filters, keyset paging),
  `/api/admin/audit/export` (CSV), `/api/admin/audit/actions`, and the platform's
  `/api/platform/audit[/counts]`; catalogue in
  [docs/security/audit-events.md](docs/security/audit-events.md).
- **OpenAPI** generated from code at `/v3/api-docs` (all profiles); Swagger UI at `/swagger-ui` (`local` only).
- `local` profile: same PostgreSQL + Flyway as production, plus dev conveniences
  (Swagger UI, admin MFA not forced, template changes without maker/checker;
  extraction persists in the same PostgreSQL). The JWT signing key is **not** committed —
  supply `MODOCUMENT_JWT_SECRET` or a gitignored `application-local-secrets.yml`.
- **Docker**: multi-stage `Dockerfile` + `docker-compose.yml` (nginx frontend +
  app + PostgreSQL, optional Ollama/docling/Keycloak/Mongo+Kafka profiles); every
  image runs non-root (app uid 10001, nginx-unprivileged uid 101) with all
  capabilities dropped, required secrets have no defaults, and the SPA/API carry a
  full security-header set ([docs/security/web-hardening.md](docs/security/web-hardening.md)).
- **Infrastructure as code**: the AWS af-south-1 go-live topology as Terraform under
  [`infra/terraform/`](infra/terraform/README.md) — written, not yet applied (see the
  deployment checklist and "Implement later" below).
- **CI/CD supply chain** (ws19, gates A7/A8/E3/E8): `ci.yml` runs `mvn -B verify -Pcoverage`
  (with a JaCoCo coverage summary), a `-Pscale` compile check, the real-PostgreSQL and
  S3 (S3Mock) integration suites (failsafe only), the frontend tests/build
  and the nginx security-header check on every push/PR to `main`; `security.yml` adds
  gitleaks (full history), Semgrep (registry packs + [`.semgrep/modocument.yml`](.semgrep/modocument.yml)),
  dependency review and `npm audit --audit-level=high`; `sbom.yml` publishes CycloneDX
  SBOMs (`mvn -Psbom`, `npm sbom`); weekly Trivy-scanned image rebuilds and a ZAP
  baseline against the compose stack; Dependabot for Maven, npm, Actions, Docker and Terraform.
  Every action is pinned by commit SHA with least-privilege `permissions`. Required
  checks and the seeded-PR drill: [docs/security/branch-protection.md](docs/security/branch-protection.md).
- **Security alerting** (ws18, gate D1): data-driven rules over the audit trail
  (immediate + per-minute windowed) raise alerts to the log, a webhook (the
  phone path: Slack/WhatsApp bridge/PagerDuty — `MODOCUMENT_ALERTS_WEBHOOK_URL`)
  and e-mail, with delivery evidence under Administration → Security alerts.
  [docs/security/alerting-and-export-caps.md](docs/security/alerting-and-export-caps.md).
- **SIEM feed** (ws18, gate E6): `GET /api/siem/events` streams the caller's
  bank's audit events as NDJSON in an ECS-mapped schema, with an optional
  single-bank webhook push —
  [docs/security/siem-event-schema.md](docs/security/siem-event-schema.md).
- **Synthetic probe**: `GET /api/probe/synthetic` (public, rate-limited) for
  uptime monitors — build version + database round trip only.

### Document creation (exact PDF templates)
**The document creation flow** has four steps, numbered under "Document
Creation" in the sidebar: **1. Upload document** — upload a PDF and confirm
its fields; **2. Validation** — a reusable **data template** holds the fields a
document collects and their rules, made from scratch or copied from a flow
template; **3. Map fields** — each PDF template chooses a data template and
maps its fields onto the data fields (matched by name, suggestions accepted in
one click); **4. Fix documents** — fill a document (from records or by hand),
fix it until it passes, then download it. A document is saved as final
(validated) and downloaded only once its data passes the data template's
rules plus the PDF's own extra rules. See
[docs/doccreation/data-templates-contract.md](docs/doccreation/data-templates-contract.md).

The template **is the original PDF**: logo, fonts, layout, colours and page
geometry stay exactly as uploaded. Documents store only values and photos;
generation draws them onto the template. Output is **PDF only** (the Word
path is gone; see [docs/doccreation/pdf-templates-contract.md](docs/doccreation/pdf-templates-contract.md)).
- **Two upload flows.** A **blank form** becomes the template as-is. A
  **filled document** has its data removed first (text cut from the page,
  AcroForm values cleared); the rest of the page stays pixel-identical. `AUTO`
  lets the engine decide. Only real PDFs are accepted, checked by their bytes
  (`%PDF-`); password-protected files and files over 500 pages are refused.
- **Review before saving.** The upload is a DRAFT: the proposed fill-in areas
  (text, multi-line, date, number, dropdown, checkbox, photo, signature) and, for a filled
  document, each removed value shown next to its box. The removed sample values
  exist only during the review and are deleted when the template is confirmed.
- **Template editor.** Draw, move and resize regions; set label, kind, group,
  font size, alignment and "required"; erase anything ("remove this"), replace
  or add text, add a logo. Every save is a new **version**; documents keep the
  version they were started from. Concurrent edits get a 409 instead of
  overwriting each other. Page previews are rendered server-side and cached.
- **Code first, AI only adds.** AcroForm widgets, leader lines, empty boxes and
  `Label:` gaps are detected by code; with **Template AI assistance**
  (`doccreation.sectionAiEnabled`, default on, also off with the bank's AI
  master switch) the model may improve labels and recall, validated against the
  real page geometry.
- **WYSIWYG builder.** The template pages are shown exactly, with inputs on the
  regions. Values are **merged per field**, so saving one never wipes another.
  Text that does not fit is shrunk down to `doccreation.pdfMinFontSize` (default
  6 pt, 4–10) and then continues on an added page; the builder warns early.
- **Photos.** Upload them once into a tray; **auto-fill** places them by file
  name (matched against each photo box's label, key and group) and then in
  reading order. Drag a photo onto any photo box; a filled box swaps.
- **Exact output.** Generation re-checks its own output: outside the filled
  regions every page must be pixel-identical to the template, or the PDF is
  refused (`500 EXACTNESS_FAILED`, audited). Release review (submit, release,
  reject, review file) works on the generated PDF.
- **Word templates from earlier versions** are hidden from the lists and answer
  `410 GONE`; a bank administrator removes them, with their documents and
  files, through the audited `POST /api/admin/doccreation/purge-legacy`.
- **Fill from data.** In the builder, pick a flow template, search its
  validated records (people) and pick one: a preview shows each field's
  proposed value, where it came from and whether it would overwrite, with a
  tick per field; then apply ("fill empty fields only" or "overwrite"). Every
  validated row of the data flow is indexed for this
  (`TemplateRecord`, see [docs/doccreation/flow-records-contract.md](docs/doccreation/flow-records-contract.md));
  a user sees the records of their own jobs and of templates shared with the
  bank, masked per role exactly like the job views.
- **Data sources.** A template keeps saved mappings to one or more flow
  templates: one **primary** source (the record you pick) and **linked**
  sources found through a shared key (e.g. the NIC), so one person's details
  can come from several flows. Fields with the same or a similar name
  (aliases included) are connected automatically, one-to-one; any link can be
  changed. Without saved sources the mapping is built on the fly by name.
- **Layout from the builder.** Move, resize or add a field while filling a
  document and save the layout to the template as a new version; the document
  moves to it (`POST …/instances/{id}/upgrade`), keeping values by field key
  and reporting the ones that no longer exist.
- **Field rules and validation.** Each field can carry a placeholder, help
  text, a default value, a maximum length and, for dates and numbers, how the
  value is printed; a **Dropdown** field offers fixed options (AcroForm combo
  boxes become dropdowns). Each template has its own **field rules**
  (required, pattern, e-mail, length, range, allowed values, date format and
  range, expiry, required-if, compare with another field), written by hand or
  imported from the field rules of a flow template (its data sources or a
  chosen template, matched by name). Saving a document validates it: only a
  **validated** document can be generated, downloaded and submitted, and any
  change makes it a draft again. See
  [docs/doccreation/field-validation-contract.md](docs/doccreation/field-validation-contract.md).

## Quick start

### Run locally (no API key, no AI endpoint needed)

```bash
# once: copy .env.example → .env, fill in generated secrets (see placeholders
# there). Mau team bootstrap email is pre-filled as team.mau.ai@gmail.com for
# empty-database installs; passwords stay local only. docker compose reads .env
# automatically and refuses to start without required secrets (no insecure
# defaults, even for local dev). Keep the file: the DB passwords are fixed at
# first boot.
#
# Or generate from scratch:
cat > .env <<EOF
POSTGRES_PASSWORD=$(openssl rand -hex 24)
MODOCUMENT_APP_DB_PASSWORD=$(openssl rand -hex 24)
MODOCUMENT_JWT_SECRET=$(openssl rand -base64 48)
MODOCUMENT_MFA_KEY=$(openssl rand -base64 48)
EOF
set -a; . ./.env; set +a          # ...and load them into this shell

# start a database once (or point MODOCUMENT_DB_* at your own PostgreSQL)
docker compose up -d postgres

# no secret is committed, even for local dev (the JWT key can also live in a
# gitignored application-local-secrets.yml — see the .example file). Outside
# Docker the app connects as the schema owner:
export MODOCUMENT_DB_PASSWORD="$POSTGRES_PASSWORD"

SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Runs against PostgreSQL exactly like production: Flyway creates and updates the
schema on startup (`src/main/resources/db/migration`), lookup data is seeded by
the migrations, and your data survives restarts. Users, templates, fields and
validation rules are created through the app — accounts are admin-created, and
the first administrator is seeded at startup from
`MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` / `MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD` when
the user table is empty. All CRUD, template, validation, job, and
export endpoints work immediately. The LLM-dependent paths (unstructured
extraction, automap leftovers, chat edit) expect a **local Ollama** at
`http://localhost:11434` by default — no account, no key, nothing leaves your
machine:

```bash
# once: install Ollama and pull the default models
ollama pull qwen2.5
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Without Ollama running, those paths simply degrade (heuristics / "left
unmapped" / an error on the explicitly-AI endpoints) — nothing else breaks.
An **external** OpenAI-compatible endpoint is an explicit opt-in:

```bash
MODOCUMENT_AI_BASE_URL=https://openrouter.ai/api \
MODOCUMENT_AI_API_KEY=<your-key> \
MODOCUMENT_AI_MODEL=<model-id> \
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

### Run with Docker

```bash
# production-style stack: app + PostgreSQL (Flyway-managed schema).
# These secrets are required — compose refuses to start without them (or
# reuse the .env from "Run locally"; in production prefer Docker secrets via
# the *_FILE variables, see docs/security/web-hardening.md).
export MODOCUMENT_JWT_SECRET=$(openssl rand -base64 48)
export MODOCUMENT_MFA_KEY=$(openssl rand -base64 48)
export POSTGRES_PASSWORD=$(openssl rand -hex 24)
export MODOCUMENT_APP_DB_PASSWORD=$(openssl rand -hex 24)
# First-run administrators (self-registration is disabled). Every user
# change needs a second approver, so seed the second admin too:
export MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
export MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD=<a-strong-password>
export MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL=admin2@example.com
export MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD=<another-strong-password>
docker compose up --build

# with LOCAL AI (the default endpoint): add the ollama service, then pull
# the models once — prompts never leave the compose network
docker compose --profile ai-local up --build
docker compose exec ollama ollama pull qwen2.5

# EXTERNAL endpoint (explicit opt-in; any OpenAI-compatible API)
MODOCUMENT_AI_BASE_URL=https://openrouter.ai/api \
MODOCUMENT_AI_API_KEY=<your-key> \
MODOCUMENT_AI_MODEL=<model-id> docker compose up --build

# dev mode against the same PostgreSQL (Swagger enabled; the JWT secret still
# comes from MODOCUMENT_JWT_SECRET — no dev secret is committed)
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

All API endpoints (except the login entry points `/api/auth/login`,
`/api/auth/refresh`, `/api/auth/mfa/verify`, `/api/auth/providers`, the
health check `/actuator/health` and the public synthetic probe
`/api/probe/synthetic`) require a JWT Bearer token. Machine callers are the
other exception: `POST /api/integration/oauth/token` authenticates the API
client itself and `POST /api/agent/v1/enroll` takes a one-time enrolment
token (see `SecurityConfig`). Public self-registration is
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

#### First run: two administrators

Creating, re-roling or disabling a user is maker/checker: a **second**
administrator must approve it. A fresh install with a single administrator
could therefore never add anyone, so seed the second one at the same time:

| Variable | |
|---|---|
| `MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` / `MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD` | First ADMIN (required on an empty database in production). |
| `MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL` / `MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD` | Optional second ADMIN — set both or neither. |

Both passwords also accept the `_FILE` form (Docker secrets), must pass the
password policy, and are **temporary**: each administrator must choose a
new password (and enrol TOTP) at first sign-in.
`MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE=true` keeps the configured
passwords on throwaway dev stacks only (refused unless
`modocument.security.permit-dev-paths=true`). Seeding runs once, on an empty
user table; at every startup the app warns loudly while the default bank has
fewer than two enabled ADMIN/APPROVER accounts — on a running install the
platform owner can add a break-glass administrator
([docs/platform/platform-owner.md](docs/platform/platform-owner.md)).

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
(`/api/automapper/generate`, chat-edit) carry a per-user
quota (`modocument.security.rate-limit.llm.*`); exceeding either returns `429`.

### Build & test

```bash
mvn -B -f connector-core/pom.xml install   # the shared connector library, first
mvn -B verify
```

`connector-core/` (the DATABASE, REST_API, SFTP and WEBHOOK connectors the
app and the on-prem agent share) is a sibling Maven project, not a module:
install it once, and again after changing it
(docs/integration/connector-sharing.md).

## Configuration

| Setting | Key / env var | Default |
|---|---|---|
| AI API key | `aifiles.openai.api-key` / `MODOCUMENT_AI_API_KEY` (legacy `AIFILES_OPENAI_APIKEY`) | `ollama` (local endpoint needs no real key; set empty to disable the LLM endpoints) |
| AI model | `aifiles.openai.model-id` / `MODOCUMENT_AI_MODEL` (legacy `AIFILES_OPENAI_MODELID`) | `qwen2.5` (local Ollama) |
| AI base URL | `aifiles.openai.base-url` / `MODOCUMENT_AI_BASE_URL` (legacy `AIFILES_OPENAI_BASEURL`) | `http://localhost:11434` — **local by default**; an external endpoint is an explicit opt-in (host root, no `/v1`) |
| PII tokenization | `aifiles.privacy.tokenize-pii` / `AIFILES_PRIVACY_TOKENIZEPII` | `true` (fail closed) |
| Mapping LLM fallback | `mapping.llm-fallback-enabled` / `MAPPING_LLMFALLBACK_ENABLED` | `true` (code tiers always run first) |
| Mapping confidence | `mapping.confidence-threshold` (+ `sample-size`, `history-jobs`, `aliases`) | `0.7`, 20 samples, 25 jobs |
| Extra sensitive fields | `aifiles.privacy.extra-sensitive-fields` | empty (substring match, case/punct-insensitive) |
| docling-serve URL | `docling.base-url` / `DOCLING_BASEURL` | empty (built-in readers only) |
| Vision OCR | `ocr.vision.enabled` / `OCR_VISION_ENABLED` | **`false`** (page images cannot be pseudonymised — explicit opt-in; Tesseract remains the offline default) |
| Vision OCR model | `ocr.vision.model` / `OCR_VISION_MODEL` | `qwen2.5vl` (must be vision-capable on the configured endpoint) |
| Vision OCR page budget | `ocr.vision.max-pages` / `OCR_VISION_MAXPAGES` (+ `dpi`, `max-dimension`) | 20 pages/PDF, 200 DPI, 2048px longest edge |
| JWT signing secret | `modocument.security.jwt.secret` / `MODOCUMENT_JWT_SECRET` | **required** in production and `local` (≥32 bytes; not committed); fixed dev secret on `test` only |
| Auth rate limit | `modocument.security.rate-limit.auth.*` (`per-ip-max`, `per-email-max`, `window-millis`) | 30/IP, 10/email per 15 min → `429` |
| LLM quota | `modocument.security.rate-limit.llm.*` (`per-user-max`, `window-millis`) | 60/user per hour → `429` |
| Database | `spring.datasource.url` / `MODOCUMENT_DB_URL` (+ `MODOCUMENT_DB_USERNAME`, `MODOCUMENT_DB_PASSWORD`) | PostgreSQL `localhost:5432/modocument` |
| CORS origins | `modocument.cors.allowed-origins` / `MODOCUMENT_CORS_ALLOWED_ORIGINS` | localhost dev origins — set your frontend origin in production |
| File store | `modocument.storage.backend` / `MODOCUMENT_STORAGE_BACKEND` | `local`; `s3` on ECS (uploads, exports, doc assets in S3 — ws29) |
| Storage root (local) | `modocument.storage.root` / `MODOCUMENT_STORAGE_ROOT` | `wwwroot` (uploads + export copies; mount a volume) |
| S3 file store | `modocument.storage.s3.*`: `bucket`, `prefix`, `region`, `kms-key-id`, `temp-cache-mb` (`MODOCUMENT_STORAGE_S3_*`) | bucket required with `s3`; region from the AWS chain; KMS = bucket default; 512 MiB local-copy cache |
| JVM flags (image) | `JAVA_TOOL_OPTIONS` (appended after the image defaults), `JAVA_OPTS` (command line), `MODOCUMENT_CDS=off` | `-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError` + the AppCDS archive |
| Profile | `SPRING_PROFILES_ACTIVE` | default (PostgreSQL); `local` adds dev conveniences, `scale` adds Mongo+Kafka extraction (needs a `-Pscale` build) |
| Extraction store | `extraction.store` / `EXTRACTION_STORE` | `sql` (the primary database); `memory` is for tests; set `mongo` (or the `scale` profile) for multi-instance runs |
| MongoDB URI | `spring.data.mongodb.uri` / `EXTRACTION_MONGO_URI` | `mongodb://localhost:27017/modocument`; **required** (authenticated URI) on the `scale` profile |
| Kafka distribution | `extraction.kafka.enabled` / `EXTRACTION_KAFKA_ENABLED` | `false` (workers poll the store queue) |
| Kafka brokers | `spring.kafka.bootstrap-servers` / `EXTRACTION_KAFKA_SERVERS` | `localhost:9092` |
| Worker pool | `extraction.worker.threads` (+ `lease-seconds`, `max-attempts`, `backoff-millis`) | 4 threads, 60s lease, 4 attempts |
| Crawler identity | `extraction.web.user-agent` | `MoDocumentBot/1.0 (+https://example.com/bot)` — set a real contact URL |
| Crawl politeness | `extraction.web.default-delay-millis` (robots crawl-delay always honored) | `1000` |
| Crawl caps | `extraction.web.max-pages-default` / `max-depth-default` | 100 pages, depth 2 |
| JS rendering | `extraction.web.render-js-enabled` — no effect since v1.2.0 (Playwright removed) | `false` |

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
/Transform`) and vision OCR inherently
send document content to the LLM — finding values in raw text *is* their job.
Tokenized cells cannot be content-reformatted by the model (it never sees them), and
prose summaries may paraphrase a placeholder — which degrades to vagueness, never to a
leak. Detection is heuristic, not NER: a bare name in an unlabeled free-text column
with no recognizable shape passes through.

## API overview

Interactive documentation: **Swagger UI at `/swagger-ui`** (`local` profile), rendering the
contract generated from the code at `/v3/api-docs` — the authoritative spec; its route inventory is
pinned in CI ([docs/api/openapi-routes.txt](docs/api/openapi-routes.txt)), versioning and deprecation
follow [docs/api/versioning-policy.md](docs/api/versioning-policy.md), and the hand-written
`/swagger/spec.yaml` is deprecated. Summary:

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
| `POST /api/ingestion/jobs/{id}/export/file` | Same download with `{format, reason}` in the body (over-cap reason, ws18) |
| `POST /api/ingestion/jobs/{id}/export/database` | Insert validated rows into a user-supplied PostgreSQL, Oracle, SQL Server or MariaDB database (`reason` above the role cap) |

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
| `POST /api/extraction/jobs/{id}/infer-schema` | Suggest a template schema from sample records (LLM; code inference when the AI is unavailable, `generatedBy`) |
| `GET /api/extraction/jobs/{id}/summary` | Short human summary of the run (LLM; counter-based when the AI is unavailable, `generatedBy`) |
| `GET /api/extraction/metrics` | Queue depth, compliance counters, worker pool state (deprecated in 1.2.0) |

### Legacy pipeline & utilities

| Endpoint | Purpose |
|---|---|
| `POST /api/fileupload/upload` | Multipart upload to `wwwroot/uploads/` (deprecated in 1.2.0) |
| `POST /api/automapper/generate` | LLM column-to-schema mapping (deprecated in 1.2.0) |
| `POST /Validation/validate` | Run rows through a template's rule engine |
| `/ProcessStep/*`, `/ImportHistory/*`, `/ImportDetails/*` | Legacy CRUD (partly deprecated in 1.2.0) |

### Document creation

| Endpoint | Purpose |
|---|---|
| `POST /api/doccreation/blueprints` | Upload a PDF (`file`, optional `name`, `companyId`, `flow=BLANK\|WITH_DATA\|AUTO`); returns the DRAFT with proposed regions and removals |
| `GET /api/doccreation/blueprints[/{id}?version=N]` | List PDF templates / get one version's overlay (boxes as page fractions) |
| `PUT /api/doccreation/blueprints/{id}/overlay` | Confirm the draft or save edits as a new version (`baseVersion`; 409 when stale) |
| `GET /api/doccreation/blueprints/{id}/pages/{page}?variant=original\|template` | Page preview (PNG, cached) |
| `POST /api/doccreation/blueprints/{id}/edit-images` | Upload a logo/image for an ADD_IMAGE edit |
| `GET /api/doccreation/blueprints/{id}/versions` | Version history |
| `GET /api/doccreation/blueprints/{id}/file?variant=original\|template` | Download the original or a version's template PDF |
| `DELETE /api/doccreation/blueprints/{id}` | Delete (409 while documents build on it) |
| `POST /api/doccreation/instances` | Start a document (409 on a DRAFT template) |
| `GET /api/doccreation/instances/{id}` | Builder view: pages, regions, values, photos, overflow warnings |
| `PUT /api/doccreation/instances/{id}/values` | Merge values (empty string clears a key) |
| `POST /api/doccreation/instances/{id}/images` | Upload photos (`files`, `autoFill`) |
| `PUT` / `DELETE /api/doccreation/instances/{id}/images/{imageId}` | Place a photo (swap) or back to the tray / delete it |
| `GET /api/doccreation/instances/{id}/pages/{page}` | Preview a page with the values drawn in |
| `POST /api/doccreation/instances/{id}/generate` | Generate the PDF (exactness-checked) |
| `GET /api/doccreation/instances/{id}/download` | Download the PDF (regenerates when stale) |
| `POST /api/admin/doccreation/purge-legacy` | Bank admin: delete the old Word templates and their documents |
| `GET /api/doccreation/records?templateId=&q=&field=&page=&size=` | Search a flow template's validated records (masked per role; `size` ≤ 100) |
| `GET /api/doccreation/records/{id}` | One record (read-audited) |
| `GET` / `POST /api/doccreation/blueprints/{id}/sources` | A template's data sources with mappings and suggestions / add one (auto-mapped; 409 for a second primary or a duplicate) |
| `PUT` / `DELETE /api/doccreation/blueprints/{id}/sources/{sourceId}` | Save a source's mapping and link fields / remove it (the primary only once no linked source is left) |
| `GET /api/doccreation/instances/{id}/fill-preview?recordId=` | What filling from a record would write (saved or automatic mapping, linked records) |
| `POST /api/doccreation/instances/{id}/fill-from-record` | Fill from a record (`fields`, `overwrite=EMPTY_ONLY\|ALL`, `linkedRecordIds`) |
| `POST /api/doccreation/instances/{id}/upgrade` | Move the document to the template's current version (`droppedKeys`) |
| `GET` / `PUT /api/doccreation/blueprints/{id}/rules` | A template's field rules / replace them (`baseRevision`; 409 when stale) |
| `POST /api/doccreation/blueprints/{id}/rules/test` | Check sample values against the saved or the given rules (nothing is stored) |
| `GET /api/doccreation/blueprints/{id}/rules/import-preview?templateId=` | What importing flow-template field rules would add (data sources when no `templateId`) |
| `POST /api/doccreation/blueprints/{id}/rules/import` | Import flow-template field rules (`MERGE` or `REPLACE`; also needs `validation:read`) |
| `POST /api/doccreation/instances/{id}/validate` | Validate a document: `VALIDATED`, or `DRAFT` with the errors per field |
| `PUT /api/doccreation/blueprints/{id}/data-template` | Choose a data template (`dataTemplateId`; the mapping is pre-filled from the suggestions) or clear it (`null`) |
| `GET` / `PUT /api/doccreation/blueprints/{id}/data-mapping` | The mapping of the template's fields onto the data fields, with suggestions and counts / save it (400 for unknown fields or kinds that don't fit) |

## Ingestion walkthrough

```
upload ──> PARSED ──────> MAPPED ─────────> VALIDATED ──────> EXPORTED
           table from     columns → fields  rule engine       csv/json/xlsx
           code or        (code first,      (incl. custom     or your own
           docling/OCR    LLM leftovers)    regex)            database
           + LLM
```

The calls below are shortened: each one also needs
`-H "Authorization: Bearer $TOKEN"` (see "Authentication"), and they assume
the app is reachable on `localhost:8080` (`mvn spring-boot:run`; the compose
stack publishes only `localhost:3000`, which proxies the same paths).

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

# 4. optionally edit via chat (needs a reachable AI endpoint: local Ollama by default)
curl -X POST localhost:8080/api/ingestion/jobs/1/chat-edit -H 'Content-Type: application/json' \
     -d '{"instruction": "Capitalize all names"}'

# 5. export — download a file, or push to your own database
curl -OJ "localhost:8080/api/ingestion/jobs/1/export/file?format=xlsx"
curl -X POST localhost:8080/api/ingestion/jobs/1/export/database -H 'Content-Type: application/json' \
     -d '{"vendor": "POSTGRESQL", "host": "myhost", "port": 5432, "database": "staging",
          "sslmode": "require", "username": "loader", "password": "***",
          "tableName": "Contacts", "createTableIfMissing": true}'
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
├── doccreation      document creation (exact PDF templates): its own api/dto/
│                    entity/repo/usecase layers plus pdftemplate, fields, rules
└── api              REST controllers + web config (CORS, case-insensitive routes)
```

Extraction runs on a lease-based task queue: the store (MongoDB in production,
in-memory locally) is the source of truth for every task's lease/attempt state, and
Kafka — when enabled — only distributes "work available" signals across instances, so
duplicate or lost signals are harmless and any instance can pick up a crashed
instance's tasks once their leases expire. Scale out by running more app instances.

## Database

- **Production (PostgreSQL):** the schema is created and versioned by **Flyway**
  (`src/main/resources/db/migration/` — from `V1__baseline_schema.sql` and
  `V2__lookup_seed.sql` up to `V334` at release 1.2.0, plus the
  `afterMigrate__rls_enforce.sql` callback) automatically on startup; Hibernate runs with
  `ddl-auto: validate` and only checks the mapping. Point the app at your server
  with `MODOCUMENT_DB_URL` / `MODOCUMENT_DB_USERNAME` / `MODOCUMENT_DB_PASSWORD`.
  Only lookup data is seeded — no accounts, templates, or rules.
- **Local development (PostgreSQL):** identical to production — the `local`
  profile inherits the datasource and Flyway migrations, so schema changes are
  exercised in dev exactly as they will run in prod. Ship a schema change as a
  new file under `src/main/resources/db/migration/`, named
  `V{n}__ws{NN}_{description}.sql` inside your workstream's range
  (`src/main/resources/db/migration/RANGES.md`) and expand-only
  ([docs/release/migration-policy.md](docs/release/migration-policy.md));
  the backend applies pending migrations on the next start.
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

On the first boot the log shows Flyway applying the whole chain, from
`V1__baseline_schema` to the newest migration (58 versioned migrations up to
`V334` at release 1.2.0), after which the tables are visible in Supabase's
Table Editor; later boots print "Schema … is up to date." The same variables work for the Docker image and the compose `app`
service (the bundled `postgres` service is then unnecessary).

## Docker

- `Dockerfile` — multi-stage: Maven + Temurin 21 build, slim JRE 21 runtime image
  that runs as a non-root user and healthchecks `/actuator/health`.
- `docker-compose.yml` —
  - `frontend`: the React build served by unprivileged nginx (uid 101, container
    port 8080, published as host **3000**) — the single entry point, proxying the
    API to the app and setting the security headers (`frontend/nginx.conf`,
    `frontend/security-headers.conf`).
  - `app`: this API on container port 8080 (not published — trusted-proxy model),
    running as uid 10001 with all capabilities dropped, wired to the `postgres`
    service by default. Requires `MODOCUMENT_JWT_SECRET`, `MODOCUMENT_MFA_KEY`,
    `POSTGRES_PASSWORD` and `MODOCUMENT_APP_DB_PASSWORD` (see "Run with Docker");
    the external-AI and ws18 alert/SIEM variables pass through when set.
    Uploads/exports are kept in a named volume. `APP_PROFILES=local` runs the dev
    profile against the same database.
  - `postgres`: PostgreSQL 17 with a named volume and a `pg_isready` healthcheck;
    the app waits for it to be healthy and Flyway migrates the schema on boot.
    `POSTGRES_PASSWORD` and `MODOCUMENT_APP_DB_PASSWORD` are required (no
    defaults); the host port is bound to `127.0.0.1` only.
  - `docling` (optional, `--profile docling` + `DOCLING_BASEURL=http://docling:5001`):
    [docling-serve](https://github.com/docling-project/docling-serve) on port 5001 for
    rich layout-aware conversion — not needed for the standard flow. The app
    image ships Tesseract (`tesseract-ocr` + English traineddata, v1.2.0) for the
    local OCR fallback on scanned PDFs; with `OCR_VISION_ENABLED=true` and a
    reachable vision-capable AI endpoint the container also gets image/scan OCR
    via the vision model, even without docling (vision OCR is off by default).
  - `mongodb` + `kafka` (optional, `--profile extraction`): the extraction store and
    work-distribution bus. Run `APP_PROFILES=scale APP_MAVEN_PROFILES=scale docker
    compose --profile extraction up --build` to switch the app onto them: the
    default build ships neither client (v1.2.0, Maven profile `scale`; locally
    `mvn -Pscale spring-boot:run`), and a default jar started with the `scale`
    profile stops with a message saying so. The crawler fetches pages over plain
    HTTP (JavaScript rendering was removed in v1.2.0).

## Production deployment checklist

The target production topology is written as Terraform under
[`infra/terraform/`](infra/terraform/) (AWS af-south-1: CloudFront + WAF →
ALB TLS 1.2+ → ECS on EC2 → Multi-AZ RDS with forced TLS; locked backups,
GuardDuty/CloudTrail, GitHub-OIDC deploys — see its README for layout and
apply order; `terraform validate` is pending a machine with terraform
installed). Whatever hosting you use, the application-level checklist is:

- **Secrets** (`MODOCUMENT_JWT_SECRET`, `MODOCUMENT_MFA_KEY`) — required,
  ≥ 32 bytes each (`openssl rand -base64 48`); the app refuses to start with
  a missing, short, or known-default secret. Prefer files over environment
  values: every sensitive variable accepts `<NAME>_FILE` (Docker/Kubernetes
  secrets, SEC-018 — [details](docs/security/web-hardening.md)). In the IaC
  they are Secrets Manager values injected into the task — never in images
  or tfvars. Inventory every key in
  [`docs/security/key-certificate-register.md`](docs/security/key-certificate-register.md).
- **Database** — set `MODOCUMENT_DB_URL` / `MODOCUMENT_DB_USERNAME` /
  `MODOCUMENT_DB_PASSWORD`; Flyway migrates on startup. Use the two-role
  model: the app connects as `modocument_app` (NOBYPASSRLS — create it per
  [`docker/postgres-init/01-app-role.sh`](docker/postgres-init/01-app-role.sh)),
  Flyway as the schema owner (`MODOCUMENT_FLYWAY_USERNAME`/`_PASSWORD`).
- **Database TLS** — `MODOCUMENT_DB_SSLMODE=require` (or `verify-full` with the
  server CA; in your own `MODOCUMENT_DB_URL`: `?sslmode=verify-full&sslrootcert=…`
  — the app image ships the AWS RDS CA bundle at
  `/app/certs/rds-global-bundle.pem`, which the Terraform envs use) and a
  server that refuses plaintext — on RDS `rds.force_ssl=1`, set by
  [`infra/terraform/modules/rds`](infra/terraform/modules/rds). The default
  `prefer` only exists for TLS-less local PostgreSQL
  ([details](docs/security/web-hardening.md#5d-database-tls-c4--sec-028-application-side)).
- **Bootstrap admin** — `MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD`
  (self-registration is disabled); enrol MFA at first login (mandatory for
  ADMIN). On AWS they are Secrets Manager secrets injected into the task
  until the first admin exists, then removed with `bootstrap_admin_enabled =
  false` ([infra/terraform/README.md](infra/terraform/README.md) "Apply
  order").
- **CORS** — set `MODOCUMENT_CORS_ALLOWED_ORIGINS` to your frontend origin(s).
- **TLS + trusted proxy** — terminate HTTPS in front of the frontend nginx
  (non-root, container port 8080; nothing in the stack serves TLS itself) and
  keep the app port unpublished: nginx overwrites `X-Forwarded-*`, which the
  app trusts for rate limiting, audit IPs and HSTS. Behind your own load
  balancer set `MODOCUMENT_TRUSTED_PROXY_CIDRS` on the frontend so nginx
  takes the client address and scheme from that balancer only
  ([web-hardening.md §5e](docs/security/web-hardening.md#5e-proxy-chain-behind-alb--cloudfront-trusted-proxy-mode)).
  In the IaC: ALB policy `ELBSecurityPolicy-TLS13-1-2-2021-06`, origin
  locked to CloudFront, trusted-proxy mode with `CloudFront-Viewer-Address`.
- **Health** — probe `GET /actuator/health` (unauthenticated); the Docker
  image, compose file and ECS task definition already use it. External uptime
  monitors use the public, per-IP rate-limited synthetic probe
  `GET /api/probe/synthetic` (build version + one database round trip through
  the whole proxy → app → DB path). SLA and probe/status-page plan:
  [`docs/security/sla-and-status.md`](docs/security/sla-and-status.md).
- **Files** — on ECS the app keeps uploads, export copies and doc assets in an
  S3 bucket (`MODOCUMENT_STORAGE_BACKEND=s3`, SSE-KMS, versioned, TLS-only —
  `infra/terraform/modules/file-store`, v1.2.0), so they survive task
  replacement and every task sees them. Elsewhere mount a volume at the storage
  root (`MODOCUMENT_STORAGE_ROOT`, default `wwwroot/`).
- **Backups** — nightly snapshots with 90-day retention and a cross-region
  copy into a **locked** vault (`infra/terraform/modules/backups`); flip the
  irreversible locks only after the restore drill passes (gate D6).
- **Monitoring** — GuardDuty + CloudTrail (log-file validation) + CloudWatch
  alarms → one SNS topic (`infra/terraform/modules/security-monitoring`);
  logs archive to the 7-year WORM bucket (SEC-033).
- **Security alerts & SIEM** — wire the application alert channels:
  `MODOCUMENT_ALERTS_WEBHOOK_URL` (+ `_SECRET`; the phone path) and/or
  `MODOCUMENT_ALERTS_EMAIL_TO` with `SPRING_MAIL_HOST`; give each bank's SIEM
  a dedicated ADMIN account for `GET /api/siem/events`, or push one bank via
  `MODOCUMENT_SIEM_WEBHOOK_URL` / `_BANK_CODE`
  ([alerting-and-export-caps.md](docs/security/alerting-and-export-caps.md),
  [siem-event-schema.md](docs/security/siem-event-schema.md)).
- **Export caps** — review the per-role row caps (`MODOCUMENT_EXPORT_CAP_READ_WRITE`,
  default 5,000; `MODOCUMENT_EXPORT_CAP_ADMIN`, default 50,000); above them an
  export needs a stated reason and is audited and alerted.
- **API docs** — the generated spec (`/v3/api-docs`) is served in every profile but needs a
  bearer token outside `local`/`test` (`MODOCUMENT_API_DOCS_ENABLED=false` removes it); the
  Swagger UI and the H2 console permit are dev-only.
- **Rate limits** — per-user hourly buckets on the expensive endpoints (`MODOCUMENT_RATELIMIT_*`;
  table in [docs/security/owasp-api-top10.md](docs/security/owasp-api-top10.md)); counters are
  in-memory per instance, so N replicas allow up to N × the configured numbers.
- **Vulnerability reports** — `SECURITY.md` + `/.well-known/security.txt`
  ship the contact; the mailbox itself is gate E5 (table below).

## Implement later (cloud & organisational) — Go-Live Gate items not executable from this repository

These gate items need a cloud account, a vendor, or an organisational
decision — code cannot close them. The repo **prepares** each one (last
column); the owner executes and files the evidence. Status detail and dates:
[`docs/security/security-roadmap.md`](docs/security/security-roadmap.md).

| Gate item | What it is | Owner | Evidence needed | Repo artifact that prepares it |
|---|---|---|---|---|
| A1 | AWS production account build-out (af-south-1) | Bhavish Nobeen | `terraform apply` outputs + account inventory | [`infra/terraform/envs/production`](infra/terraform/envs/production) |
| A2 | Console/root MFA for every cloud operator | Bhavish Nobeen | IAM credential report | — (console-only; policy in [`docs/security/identity-providers.md`](docs/security/identity-providers.md) spirit) |
| A3–A5 | VPC/network/edge build-out, WAF in blocking mode | Bhavish Nobeen | applied plan + WAF metrics | [`modules/network`](infra/terraform/modules/network), [`modules/edge`](infra/terraform/modules/edge) |
| A6 | CloudTrail into a **separate lock account** | Bhavish Nobeen | trail ARN + lock-account bucket policy | [`modules/security-monitoring`](infra/terraform/modules/security-monitoring) + two-account model in [`infra/terraform/README.md`](infra/terraform/README.md) |
| A1/SUP-S07 | Separate **staging account** | Bhavish Nobeen | staging apply in its own account | [`infra/terraform/envs/staging`](infra/terraform/envs/staging) |
| A8/SUP-S05 | CI deploys with **no static cloud keys** | Bhavish Nobeen | GitHub Actions run assuming the OIDC role | [`modules/ci-oidc`](infra/terraform/modules/ci-oidc) |
| SEC-018 | Secrets in a managed secret store | Bhavish Nobeen | Secrets Manager entries (values set out-of-band) | secret shells in `envs/*/main.tf`; [`docs/security/key-certificate-register.md`](docs/security/key-certificate-register.md) |
| D3 | Egress allow-list **enforced** | Bhavish Nobeen (define), Reezvi Pydiah (sign off) | signed list + applied SG/NACL | `app_egress_https_cidrs` + `enforce_egress_nacl` seams in [`modules/network`](infra/terraform/modules/network)/[`edge`](infra/terraform/modules/edge) |
| D4–D5 | Real backups: 90d, cross-region, **locked vault** | Rushal Seeruthun | vault lock config + recovery-point listing | [`modules/backups`](infra/terraform/modules/backups) |
| D6 | **Restore drill** (timed, documented) | Rushal Seeruthun | drill log with RTO/RPO achieved | staging env + [`docs/security/sla-and-status.md`](docs/security/sla-and-status.md) targets |
| D7 | DR/BCDR sign-off | Reezvi Pydiah | signed BCDR statement | drill evidence + [`docs/security/cia-controls-table.md`](docs/security/cia-controls-table.md) |
| E4 | External **penetration test** (budget 31 Oct 2026) | Reezvi Pydiah (procure), Bhavish Nobeen (remediate) | report + retest closure | staging env (open `ingress_cidrs` for the window); scope = [`docs/security/security-feature-catalogue.md`](docs/security/security-feature-catalogue.md) |
| E5 | **security@** mailbox live | Taj Aundoo | test mail round-trip | [`SECURITY.md`](SECURITY.md) + `frontend/public/.well-known/security.txt` |
| E7 | **Load test executed** (k6 scenarios written in ws17, not yet run) | Rushal Seeruthun | k6 report vs SLA targets | [`loadtest/`](loadtest/README.md) scenarios + staging ALB output; [`docs/security/sla-and-status.md`](docs/security/sla-and-status.md) |
| C11 | **Written acceptance of residual AI risks** | **Reezvi Pydiah** | signed acceptance note | risk list in [`docs/security/ai-controls.md`](docs/security/ai-controls.md) "Residual risks" (ws15 AI gateway): shape-driven pseudonymisation with no NER-grade field extraction, no input cleaning against prompt injection, model-written figures (validated structurally, not factually), no model version pinning. Context: README "Privacy: PII tokenization" + [`docs/security/threat-control-matrix.md`](docs/security/threat-control-matrix.md) AI row |
| SUP-S10 | DMARC/SPF/DKIM on the sending domain | Taj Aundoo | DNS records + DMARC report | — (DNS-level; no repo artifact) |
| SUP-S09 | MDM on staff devices | Taj Aundoo | MDM enrolment report | — (organisational) |
| SEC-031 | SIEM reference test (one detection end-to-end with a bank's real SIEM) | Rushal Seeruthun | alert received from a staged `ACCOUNT_LOCKED` burst | **App side done (ws18):** bank-scoped feed `GET /api/siem/events` + signed single-bank push ([`docs/security/siem-event-schema.md`](docs/security/siem-event-schema.md), `SiemFeedE6Test`) and the alert channels (`AlertingGateD1Test`); infra side: metric filter + alarm in [`modules/security-monitoring`](infra/terraform/modules/security-monitoring) |
| A8 | **Branch protection switched on** + seeded-vulnerable-PR drill | Bhavish Nobeen | ruleset screenshot/export + the blocked drill PR | settings and drill runbook in [`docs/security/branch-protection.md`](docs/security/branch-protection.md); the CI gauntlet and CODEOWNERS already exist (ws19) |
| SEC-038 | Certification path decision (ISO 27001 / SOC 2) | Reezvi Pydiah | decision memo | evidence pack under [`docs/security/`](docs/security/) is structured to serve either |
| ws29 | **S3 file store live**: apply `modules/file-store` (bucket, CMK, task-role policy, `MODOCUMENT_STORAGE_*` env) per environment, deploy, then upload → parse → export → doc generate on staging; the `storage-s3` CI job (S3Mock contract suite) green | Bhavish Nobeen | bucket listing with the new objects + the staging smoke run | [`modules/file-store`](infra/terraform/modules/file-store), [`docs/release/runbook.md`](docs/release/runbook.md) §14 |
| ws29 | **Image checks on a Docker host**: Tesseract reports available (`OcrService`), the AppCDS archive is mapped, startup time before/after — not measurable in the dev sandbox (no Docker daemon) | Bhavish Nobeen | `docker run` log lines + timings | Dockerfile, [`docs/release/runbook.md`](docs/release/runbook.md) §14 |
| ws29 | **uploads/ storage-side expiry**: blueprints and assets of documents still being edited share `uploads/` with customer uploads, so the bucket's 30-day expiry covers `exports/` only; give them their own prefix, then set `uploads_expiry_days = 30` | Bhavish Nobeen | lifecycle rule applied | `uploads_expiry_days` in [`modules/file-store`](infra/terraform/modules/file-store) |
| ws26 | **Per-bank private connectivity** (site-to-site VPN or AWS PrivateLink) for DIRECT-mode integrations. Implement it per bank at onboarding, only when the bank does not run the on-prem Connector Agent | Bhavish Nobeen (with each bank's network team) | VPN/endpoint configuration + the approved `BankEgressRule` rows | `bank_egress_cidrs` SG seam in [`modules/network`](infra/terraform/modules/network); the agent alternative needs no inbound path ([`docs/integration/agent.md`](docs/integration/agent.md)) |
