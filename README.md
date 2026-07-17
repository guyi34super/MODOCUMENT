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
| Database | `spring.datasource.url` / `SPRING_DATASOURCE_URL` | SQL Server `localhost`, database `MoDocument` |

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

## Architecture

Single Maven module with Clean Architecture layering by package:

```
com.modocument
├── domain           entities (JPA) + value objects/enums
├── application      DTOs, ports (interfaces), use cases, validation messages
├── infrastructure   JPA repositories, OpenAI client, prompt strategies,
│                    file readers (POI/Commons CSV/PDFBox + optional Tess4J OCR),
│                    transform pipeline, validation rules
└── api              REST controllers + web config (CORS, case-insensitive routes)
```

Notes:

- PDF text extraction uses PDFBox; pages without text fall back to Tesseract OCR when the
  native library is available, and degrade gracefully (skip OCR) when it is not.
- The seed data (`data.sql`) is loaded automatically on the H2 `local`/`test` profiles.
  For SQL Server, create the `MoDocument` database and run it once manually.
