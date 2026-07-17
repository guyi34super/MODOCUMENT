# MoDocument

Document processing API with LLM-powered extraction, template mapping, and validation.
Java 21 / Spring Boot backend (rewritten from the original .NET 8 implementation with the
same routes, JSON contract, and behavior).

## Requirements

- Java 21
- Maven 3.9+
- SQL Server (default profile) — or nothing at all when using the `local` H2 profile

## Run locally (no SQL Server, no OpenAI key needed)

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

This boots against an in-memory H2 database seeded with the templates, template fields,
process steps, and validation rules from `src/main/resources/data.sql`. All CRUD and
validation endpoints work without further setup; only the LLM endpoints need an API key.

## Configuration

| Setting | Key / env var | Default |
|---|---|---|
| OpenAI API key | `aifiles.openai.api-key` / `AIFILES_OPENAI_APIKEY` | empty (LLM endpoints return an error until set) |
| OpenAI model | `aifiles.openai.model-id` / `AIFILES_OPENAI_MODELID` | `gpt-4o` |
| OpenAI base URL | `aifiles.openai.base-url` / `AIFILES_OPENAI_BASEURL` | `https://api.openai.com` (host root, no `/v1`) |
| Database | `spring.datasource.url` / `SPRING_DATASOURCE_URL` | SQL Server `localhost`, database `MoDocument` |
| docling-serve URL | `docling.base-url` / `DOCLING_BASEURL` | empty (built-in POI/CSV/PDFBox/Tesseract readers are used) |

LLM calls go through [Spring AI](https://docs.spring.io/spring-ai/reference/); the legacy
`aifiles.openai.*` keys are bridged into the Spring AI OpenAI auto-configuration, so existing
environment variables keep working. When `docling.base-url` points at a
[docling-serve](https://github.com/docling-project/docling-serve) instance, uploaded documents
(pdf, doc/docx, xlsx, csv, images/scans) are converted to markdown there — with OCR — and the
built-in readers act as an automatic fallback.

Never commit real keys — pass them as environment variables:

```bash
AIFILES_OPENAI_APIKEY=<your-openai-key> SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

## Build & test

```bash
mvn -B verify
```

## API overview

Swagger UI is served at `/swagger-ui` rendering the static contract at `/swagger/spec.yaml`.

- CRUD: `/Templates/*`, `/TemplateFields/*`, `/TemplateDataTypes/*`, `/ProcessStep/*`,
  `/ImportHistory/*`, `/ImportDetails/*` (GetAll / GetById/{id} / Create / Update / delete by id)
- `POST /api/aifiles/send` — send a prompt to the OpenAI chat completion service
- `POST /api/fileupload/upload` — multipart upload; files land in `wwwroot/uploads/`
- `POST /api/transform/transform` — classify an uploaded file against a template, extract
  its fields to JSON via the LLM, and record import history/details
- `POST /api/automapper/generate?extractedFields=...&filename=...` — LLM column-to-schema mapping
- `POST /Validation/validate` — run rows through the template's validation rule engine

### Custom templates & validation rules

- `POST /Templates/CreateWithFields` — create a template together with its fields and
  per-field validation rules in one call. Regex patterns (supplied by the UI) are compiled
  server-side; invalid ones are rejected with a 400 listing field, rule, and syntax error.
- `GET /ValidationRules/GetByTemplate/{templateId}`, `PUT /ValidationRules/Upsert`,
  `DELETE /ValidationRules/{templateId}/{fieldName}/{ruleType}` — manage individual rules
  (rule types travel by name: `REGEX`, `REQUIRED`, `DATE_FORMAT`, `EXPIRY_DATE`, `RANGE`, ...).

### Resumable ingestion jobs

Documents of any supported kind are ingested against a chosen template as a **job** whose
stages checkpoint to the `IngestionJob` table (`UPLOADED → PARSED → MAPPED → VALIDATED →
EXPORTED`), so work survives failures/restarts and can be resumed or re-run per stage:

- `POST /api/ingestion/jobs` — multipart (`file`, `templateId`, `autoRun`) or JSON
  (`fileName` of a prior upload). csv/xlsx are tabularized in code; pdf/doc/docx/images go
  through docling/OCR and an LLM table-extraction prompt (structured output).
  Columns are auto-mapped to template fields by name first, LLM only for the leftovers,
  then rows are validated with the template's rules.
- `GET /api/ingestion/jobs`, `GET /api/ingestion/jobs/{id}` — inspect jobs, checkpoints,
  mapping, and validation results.
- `POST /api/ingestion/jobs/{id}/resume` — continue from the last completed stage
  (including after a `FAILED` run); `.../remap`, `.../revalidate`, `.../cancel`.
- `PUT /api/ingestion/jobs/{id}/mapping` — apply a reviewed mapping override.
- `POST /api/ingestion/jobs/{id}/chat-edit` — modify the table with a natural-language
  instruction (`{"instruction": "Trim whitespace from all names"}`) executed by the LLM.
- `GET /api/ingestion/jobs/{id}/export/file?format=csv|json|xlsx` — download the validated
  rows (a copy is archived under `wwwroot/exports/`).
- `POST /api/ingestion/jobs/{id}/export/database` — insert the validated rows into a
  user-supplied database (`jdbc:sqlserver:` / `jdbc:h2:`), with strict identifier
  validation, parameterized inserts, and optional table auto-creation; credentials are
  never persisted.

For SQL Server, apply `src/main/resources/sql/sqlserver-additions.sql` once to create the
`IngestionJob` table (H2 profiles create it automatically).

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

Notes:

- PDF text extraction uses PDFBox; pages without text fall back to Tesseract OCR when the
  native library is available, and degrade gracefully (skip OCR) when it is not.
- The seed data (`data.sql`) is loaded automatically on the H2 `local`/`test` profiles.
  For SQL Server, create the `MoDocument` database and run it once manually.
