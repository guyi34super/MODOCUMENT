# MoDocument

Document processing API that turns **any document** (CSV, Excel, PDF, Word, images/scans)
into clean, validated, tabular data — driven by **user-defined templates** with custom
per-field validation (including UI-supplied regex), AI-assisted extraction and mapping,
and resumable ingestion jobs checkpointed in SQL.

Java 21 / Spring Boot 3.5 / Spring AI. SQL Server in production, in-memory H2 for local
development and tests.

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
- **Automatic fallback readers** when docling is absent or fails: PDFBox for PDFs
  (per-page Tesseract OCR for scanned pages), POI for `.docx`/`.doc`, Tesseract OCR for
  standalone images. Degrades gracefully when the native OCR library is unavailable.
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

### Field mapping (code first, AI for the rest)
- Source columns are mapped to template fields by **exact name match**, then
  **normalized match** (case/punctuation-insensitive) — all in code, no tokens spent.
- Only unresolved columns go to the **LLM automapper**; targets it invents that aren't
  real template fields are discarded.
- **Review and override** the mapping: `PUT /api/ingestion/jobs/{id}/mapping`
  (`{"mapping": {"source col": "TemplateField" | null}}`) — rebuilds the mapped rows and
  clears stale validation.

### Validation
- Mapped rows are validated against the template's rules (including your custom regex)
  by the same rule engine as `POST /Validation/validate`, one result per field per row.

### Chat AI table editing
- `POST /api/ingestion/jobs/{id}/chat-edit` with
  `{"instruction": "Trim whitespace from all names"}` sends the current table plus your
  instruction to the LLM and persists the returned **full modified table** (structured
  output enforces the shape). Stale validation/export checkpoints are cleared so you
  revalidate after editing.

### Review & export anywhere
- **Download** the validated rows as **CSV, JSON, or XLSX**:
  `GET /api/ingestion/jobs/{id}/export/file?format=csv|json|xlsx` — a copy is archived
  under `wwwroot/exports/`.
- **Push directly to your own database**: `POST /api/ingestion/jobs/{id}/export/database`
  with `{jdbcUrl, username, password, tableName, createTableIfMissing}`. Supports
  `jdbc:sqlserver:` and `jdbc:h2:` targets, auto-creates the table when asked, inserts
  in batches. Table/column identifiers are strictly validated **and** quoted; row values
  only ever travel as bound parameters (no SQL injection); credentials are used for the
  one export and never persisted or logged.

### AI integration (Spring AI)
- All LLM access goes through **Spring AI's `ChatClient`** (OpenAI chat model) behind a
  single application port, with structured output for extraction, mapping, and chat
  edits.
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
- H2 `local` profile with seeded templates/rules — runs with zero external dependencies.
- **Docker**: multi-stage `Dockerfile` + `docker-compose.yml` (app + docling-serve,
  optional SQL Server).
- CI: GitHub Actions runs `mvn -B verify` on every push/PR to `main`.

## Quick start

### Run locally (no SQL Server, no OpenAI key needed)

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Boots on in-memory H2 seeded from `src/main/resources/data.sql`. All CRUD, template,
validation, job, and export endpoints work immediately; only LLM-dependent paths
(unstructured extraction, automap leftovers, chat edit) need an API key:

```bash
AIFILES_OPENAI_APIKEY=<your-openai-key> SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

### Run with Docker

```bash
# app (H2 profile) + docling-serve for full pdf/doc/image OCR conversion
docker compose up --build

# with an OpenAI key for the LLM features
AIFILES_OPENAI_APIKEY=<your-openai-key> docker compose up --build

# also start SQL Server (then create the schema — see Database below)
docker compose --profile mssql up --build
```

The API is on <http://localhost:8080> (Swagger UI: <http://localhost:8080/swagger-ui>),
docling-serve on <http://localhost:5001>.

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
| docling-serve URL | `docling.base-url` / `DOCLING_BASEURL` | empty (built-in readers only) |
| Database | `spring.datasource.url` / `SPRING_DATASOURCE_URL` | SQL Server `localhost`, database `MoDocument` |
| Profile | `SPRING_PROFILES_ACTIVE` | default (SQL Server); use `local` for H2 |

LLM calls go through [Spring AI](https://docs.spring.io/spring-ai/reference/); the
legacy `aifiles.openai.*` keys are bridged into the Spring AI OpenAI auto-configuration,
so existing environment variables keep working. Never commit real keys — pass them as
environment variables.

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
| `GET /api/ingestion/jobs/{id}/export/file?format=csv\|json\|xlsx` | Download validated rows (copy archived in `wwwroot/exports/`) |
| `POST /api/ingestion/jobs/{id}/export/database` | Insert validated rows into a user-supplied SQL Server/H2 database |

### Legacy pipeline & utilities

| Endpoint | Purpose |
|---|---|
| `POST /api/fileupload/upload` | Multipart upload to `wwwroot/uploads/` |
| `POST /api/transform/transform` | Classify a file against a template and extract fields to JSON via the LLM |
| `POST /api/automapper/generate` | LLM column-to-schema mapping |
| `POST /Validation/validate` | Run rows through a template's rule engine |
| `POST /api/aifiles/send` | Send a raw prompt to the chat model |
| `/ProcessStep/*`, `/ImportHistory/*`, `/ImportDetails/*` | Legacy CRUD |

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
     -d '{"jdbcUrl": "jdbc:sqlserver://myhost;databaseName=Staging;trustServerCertificate=true",
          "username": "loader", "password": "***", "tableName": "Contacts", "createTableIfMissing": true}'
```

If a stage fails (LLM outage, unreadable scan, ...), the job stores the error and stays
at its last completed step — fix the cause and `POST .../resume`.

## Architecture

Single Maven module with Clean Architecture layering by package:

```
com.modocument
├── domain           entities (JPA) + value objects/enums
├── application      DTOs, ports (interfaces), use cases, validation messages
├── infrastructure   JPA repositories, Spring AI ChatClient adapter, prompt strategies,
│                    file readers (POI/Commons CSV/PDFBox + optional Tess4J OCR),
│                    docling-serve client, transform/ingestion pipeline,
│                    validation rules, file/database export
└── api              REST controllers + web config (CORS, case-insensitive routes)
```

## Database

- **Local/tests (H2):** schema is auto-created from the JPA entities and seeded from
  `src/main/resources/data.sql` (templates, fields, process steps, 64 validation rules).
- **Production (SQL Server):** create the `MoDocument` database, run `data.sql` once,
  and apply `src/main/resources/sql/sqlserver-additions.sql` once to create the
  `IngestionJob` table used by the resumable pipeline.

## Docker

- `Dockerfile` — multi-stage: Maven + Temurin 21 build, slim JRE 21 runtime image.
- `docker-compose.yml` —
  - `app`: this API on port 8080 (H2 `local` profile by default, so it works with no
    external services); pass `AIFILES_OPENAI_APIKEY` through your environment for the
    LLM features. Uploads/exports are kept in a named volume.
  - `docling`: [docling-serve](https://github.com/docling-project/docling-serve) on
    port 5001, pre-wired via `DOCLING_BASEURL` — this is what gives the containerized
    app full PDF/Word/image OCR conversion (the slim app image has no native Tesseract;
    the built-in OCR fallback silently disables itself there).
  - `mssql` (optional, `--profile mssql`): SQL Server 2022 for a production-style
    setup — point the app at it with `SPRING_DATASOURCE_URL` and create the schema as
    described in [Database](#database).
