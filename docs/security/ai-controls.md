# AI Controls (SUP-S13 — gates C8/C9/C10)

How MoDocument keeps customer data away from language models while still using
them. Scope: every LLM call in the backend — document table extraction, the
legacy transform endpoints, auto-mapping, chat table edits, record cleaning,
schema inference, extraction summaries, template classification, blueprint
section breakdown, AI document composition, the diagnostics prompt endpoint,
and vision OCR.

## One gateway, no raw paths (C9)

`infrastructure/ai/AiGateway` is the **only** class allowed to touch the
Spring AI chat types (`ChatClient`/`ChatModel`). This is enforced by a build
guard (`NoDirectChatModelAccessGuardTest`): any new class importing those
types outside `com.modocument.infrastructure.ai` fails the build.

Every call arrives as a `PromptEnvelope` naming:

- its **purpose** (`AiPurpose`) — drives the output-scanning policy and the
  audit trail;
- the **bank** the prompt acts for and the user it acts on behalf of;
- the prompt text as **parts**, each optionally stamped with the bank its
  content came from.

The gateway then enforces, in order:

1. **One bank per prompt.** A tenant must be bound on the calling thread
   (`TenantContext`), it must equal the envelope's bank, and every part that
   names a source bank must name that same bank. Any mismatch — including a
   missing tenant context — is refused with an `AI_PROMPT_REFUSED` audit
   event (outcome `DENIED`). There is no code path that can assemble a prompt
   mixing two banks' data and have it sent.
2. **No raw identifiers out.** The assembled prompt is re-scanned with the
   shared `PiiPatterns` shapes (Mauritius NIC formats, bare 9+-digit account
   runs, emails). A hit means a call site skipped tokenization, so the call
   is refused (`AI_PROMPT_BLOCKED_PII`, `DENIED`) instead of trusted. The
   audit evidence names the shape categories, never the values.
3. **Audit.** Every successful call writes an `AI_CALL` audit event carrying
   purpose, model, prompt/completion lengths and token counts — no content.

## Pseudonymisation before every prompt (C9)

Call sites tokenize sensitive values with `PiiTokenizer` **before** building
the envelope. Tokenization is reversible per request: each distinct value gets
a stable `[ID_1]` / `[EMAIL_2]`-style placeholder, the mapping lives only in
memory for that one call, and the placeholders are swapped back in the model's
answer. The model manipulates structure and tokens; it never sees the values.

Per call site:

| Call site | Purpose | Input handling |
|---|---|---|
| `IngestionParseStepImpl` (table extraction) | `TABLE_EXTRACTION` | Extracted document text tokenized per batch; rows detokenized from the reply. Tenant context is propagated onto the parallel batch executor so the gateway's tenancy check holds on pooled threads. |
| `TransformServiceImpl.transform` | `TRANSFORM_EXTRACTION` | Full document text tokenized; extracted JSON detokenized. |
| `TransformServiceImpl.getTemplateId` | `TEMPLATE_CLASSIFY` | Snippet one-way masked (`maskText`) inside `TemplateIdentifierPrompt`; the answer is a template number, nothing to detokenize. |
| `AutoMapUseCase` | `AUTO_MAP` | Column/field **names** plus sample **values**; the values are tokenized, so the shape signal (`[ID_1]`) survives while no raw value leaves. Reply is name→name JSON. |
| `AutoMapperController` (legacy endpoint) | `AUTO_MAP` | Column and field names only — no data values by construction. |
| `ChatEditTableUseCase` | `CHAT_EDIT` | Table rows tokenized (`tokenizeRows`); the envelope is pinned to the job's bank; patch/table reply detokenized. |
| `CleanRecordsUseCase` | `DATA_CLEANING` | Row batches tokenized; cleaned rows detokenized. |
| `InferSchemaUseCase` | `SCHEMA_INFERENCE` | Sample records already tokenized upstream; envelope pinned to the job's bank. When `AiPreflight` reports the AI off/unconfigured/circuit-open or the call fails, types are inferred from the sample values in code (`generatedBy: CODE`, `fallbackReason`). |
| `SummarizeExtractionUseCase` | `EXTRACTION_SUMMARY` | Stats + tokenized samples; summary detokenized. Same fallback: a summary from counters, fill rates and distinct counts that quotes no value (`generatedBy: CODE`). |
| `AiSectionSplitter` | `DOC_SECTION_SPLIT` | Body listing **one-way masked** (`maskText`), not tokenized: the model's anchors are body-element indexes (`[3]`), not character offsets, so length changes are safe, and the accepted titles/anchor text are re-read from the document itself — nothing from the model needs unmasking. |
| `TemplateNormalizer` | `DOC_TEMPLATE_CLEAN` | Optional AI-assisted sample-data strip on blueprint upload; heuristics are primary. |
| `TemplateFieldExtractor` | `DOC_TEMPLATE_FIELD` | Detect fillable fields in FORM sections (LENIENT). |
| `AiRegionAdvisor` (PDF templates) | `DOC_PDF_REGIONS` | Listing of text runs (`r12 p0 72,700,80,10 s10 "Full name:"`) and code-found candidate fields, **one-way masked** (`maskText`): coordinates are comma-separated so no number shape is masked, and the model answers with run/candidate ids only. Every id is re-validated against the page model; a reply with any unknown id, kind or a masked placeholder in a label is dropped whole and the code labels stand (`detectedBy: CODE`). Runs only when the caller passes `aiAllowed`. |
| `AiRegionAdvisor` (PDF templates) | `DOC_PDF_DATA` | Same masked run listing plus the run ids code classified as sample data. The model may only add real run ids as data (never a heading or label run) or demote proposed ones; boxes are always taken from the real glyphs, AI-only items get confidence 0.6 and are shown for review, and any invalid reply leaves the code classification unchanged. |
| `AiDocumentComposer` | `DOC_COMPOSE` | **Reversible tokenization** of the template listing, user paragraphs, captions, section titles and image file names in one session; the validated plan is detokenized before rendering, so the generated document carries the real values while the model never saw them. (Deliberately NOT the one-way masking used for classification snippets/section splitting: masking would bake `[ID]` placeholders into the generated document.) |
| ~~`AiFilesController` (`POST /api/aifiles/send`)~~ | `DIAGNOSTIC` | **Removed in 1.2.0** (open prompt proxy). |
| `VisionOcrService` | `VISION_OCR` | Images cannot be tokenized — see the vision policy below. |

Tokenization is heuristic (field-name keywords + value shapes), fail-closed by
default (`aifiles.privacy.tokenize-pii: true`), and extendable with
`aifiles.privacy.extra-sensitive-fields`.

## Output scanning (C10)

`AiOutputScanner` runs on **every completion before it is returned** (and
therefore before any detokenization at the call site):

- **Identifier leak** — a raw NIC/account/email shape in the completion that
  was not literally present in the request. Requests carry only tokens, so
  such a value is model-invented or model-recalled; either way it is not the
  user's data and must not be written anywhere.
- **Instruction/injection echo** — the completion repeats our own format
  instructions (`LlmStructuredJsonParser.looksLikeInstructionEcho`) or carries
  prompt-injection language (ignore/disregard instructions, system-prompt
  talk, developer-mode/jailbreak/unrestricted-role shapes).

Policy per purpose (`AiPurpose.outputPolicy()`):

| Policy | Purposes | On a finding |
|---|---|---|
| **STRICT** | `TABLE_EXTRACTION`, `TRANSFORM_EXTRACTION`, `CHAT_EDIT`, `DATA_CLEANING`, `DOC_SECTION_SPLIT`, `DOC_COMPOSE`, `DOC_PDF_DATA` | Completion rejected (`AiOutputRejectedException`), `AI_OUTPUT_BLOCKED` audit (`DENIED`); the caller falls back to its non-AI path (heuristic section parser, deterministic document engine, code data detection, original rows) or reports "nothing was changed". |
| **LENIENT** | `TEMPLATE_CLASSIFY`, `AUTO_MAP`, `SCHEMA_INFERENCE`, `EXTRACTION_SUMMARY`, `VISION_OCR`, `DIAGNOSTIC`, `DOC_PDF_REGIONS` | Identifier shapes scrubbed (`«redacted»`), `AI_OUTPUT_FLAGGED` audit (`FAILED`), the sanitized completion is returned — these outputs are suggestions validated downstream. |

`VISION_OCR` is the one purpose with the identifier scan off: its completion
IS the document's own text, so identifier shapes in it are the customer's data
coming back in — that text is then tokenized like any other extracted text
before later prompts.

## Vision OCR policy (C8)

A page image cannot be pseudonymised. Vision OCR is therefore **off by
default** (`ocr.vision.enabled: false`) and requires an explicit double
opt-in: `OCR_VISION_ENABLED=true` plus a configured, vision-capable
model/endpoint. The local Tesseract OCR tier always remains and is the
default for scanned content. When enabled, vision calls still run through the
gateway (tenancy check, content-free logging, `AI_CALL` audit).

## Local models by default (C8)

- Default endpoint: `http://localhost:11434` (Ollama), api-key `ollama`
  (local endpoints need no real key), one default model `qwen2.5` for
  parsing, chat edits and the extraction agents (since v1.2.0;
  `MODOCUMENT_CHAT_EDIT_MODEL` / `MODOCUMENT_EXTRACTION_MODEL` override it
  with a pinned tag).
- Docker: `docker compose --profile ai-local up` starts an `ollama` service
  on the compose network; the app's defaults point at it.
- An **external** endpoint is an explicit operator opt-in via
  `MODOCUMENT_AI_BASE_URL` / `MODOCUMENT_AI_API_KEY` / `MODOCUMENT_AI_MODEL`
  (legacy `AIFILES_OPENAI_*` names still work).
- The app boots and the full suite passes with **no AI endpoint reachable**.
  Not every feature has a non-AI path, though: see
  [Availability and code fallback](#availability-and-code-fallback-ws23) for
  exactly what keeps working and what answers 503.
- **Prompts and completions are not stored** (the per-request token map lives
  only in memory) and **never logged** — logs and audit evidence carry
  purposes, models and lengths only, at every log level.

## Availability and code fallback (ws23)

The gateway refuses a model call in three cases, all before any prompt
leaves the backend:

- **No key configured**: `AiNotConfiguredException`.
- **AI switched off for the bank** (`ai.enabled` setting, Data protection
  tab): `AiUnavailableException`, reason `AI_DISABLED`. This is the master
  switch: no feature makes a model call for that bank.
- **Circuit breaker open** (`AiCircuitBreaker`, one per application
  instance): `AiUnavailableException`, reason `CIRCUIT_OPEN`, with a
  `Retry-After`.
  - It opens after `ai.circuitBreaker.failureThreshold` (default 5)
    consecutive transport failures, timeouts or 5xx answers. 4xx answers,
    output-scanner rejections and unusable replies do not count.
  - It stays open for `ai.circuitBreaker.openDuration` (default 120 s), then
    lets exactly one trial call through.
  - Opening is audited as `AI_CIRCUIT_OPENED` under the platform bank, with
    counts and the failure type only.

HTTP endpoints answer **503** for all three.

The AI client has a 5 s connect timeout and a 120 s read timeout
(`modocument.ai.http.*`). It retries a 5xx once after 1 s
(`spring.ai.retry.*`) and never retries a timeout. A failing model is
therefore given up on within seconds, and a hung one after the read timeout.
Before this, Spring AI's defaults applied: about 19 minutes of retries, and
no read timeout.

What happens to each feature when the model cannot be used:

| Feature | Without the AI |
|---|---|
| Ingestion parse, mode `AI_WITH_CODE_FALLBACK` (default) | The code extractor reads the table instead. This covers AI off, no key, circuit open, LLM quota spent, provider error, timeout, output-scanner rejection, an unusable reply and a gateway prompt refusal. The job records `parsedBy=CODE` and the reason, and `INGESTION_PARSE_FALLBACK` is audited. A failed batch of a long table is re-parsed by code (`AI_PARTIAL`). If code cannot reach `extraction.code.minConfidence` either, the job fails, naming both failures. |
| Ingestion parse, mode `CODE_ONLY` | Never calls the model. |
| Ingestion parse, mode `AI_ONLY` | The job fails, as before phase 2. |
| Structured files (CSV, Excel, markdown/row-line text) | Parsed by code before any AI call, in every mode. |
| ~~Legacy transform (`/api/transform/transform`)~~ | Removed in 1.2.0 (unused). |
| Column mapping | The code tiers always run. Columns they leave unresolved stay unmapped for review. |
| Document creation: section breakdown, composition | The heuristic parser and the deterministic engine. |
| LLM record cleaning while bridging | The original rows are kept. |
| Vision OCR | Tesseract. |
| Chat table edits, schema inference, run summaries, `/api/automapper/generate` | **No non-AI path**: 503. The heuristic schema inference and summaries are planned for phase 2, wave 4. |

Falling back to code never sends data anywhere: the code extractor runs
locally and its notes carry no cell values. A prompt the gateway refused
(tenancy, raw identifiers) is therefore a privacy-safe fallback, logged at
WARN.

## Evidence (tests)

- `AiGatewayC9Test` — real-format NIC/account through transform and automap
  produce token-only model requests; cross-bank parts, tenant mismatches and
  missing tenant context are refused and audited; the gateway's own leak scan
  blocks an untokenized prompt.
- `AiGatewayC8LoggingTest` — no prompt/completion content in logs at TRACE;
  `AI_CALL` evidence is a fixed content-free key set; refusal logs name shape
  categories, never values.
- `AiOutputScannerTest`, `AiOutputPolicyC10Test` — seeded wrong-NIC and
  injection-echo completions caught; STRICT rejection with caller fallback;
  LENIENT scrub-and-flag.
- ws23 availability:
  - `AiCircuitBreakerTest`: opens after N counted failures, one half-open
    trial, closes on success. The gateway refuses at once while the circuit
    is open and for a bank with AI switched off.
  - `AiFailureClassifierTest`: every failure maps to its reason.
  - `IngestionFallbackIntegrationTest`: a real loopback model answering 500,
    or never answering, falls back to code in about 1.5 s or 2.6 s (2 s read
    timeout in the test). `CODE_ONLY` makes no call, and `AI_ONLY` fails the
    job.
  - `IngestionFallbackPgIT`: the same on PostgreSQL with row level security.
- `NoDirectChatModelAccessGuardTest` — no class outside `infrastructure/ai`
  references the Spring AI chat types.

## Residual risks (for the C11 sign-off)

1. **No NER-grade field extraction.** Pseudonymisation is keyword/shape
   driven. Free-prose names, addresses and other identifiers without a
   detectable shape still travel to the model (locally by default). Mitigate
   with `extra-sensitive-fields` per deployment; full NER is out of scope.
2. **No input cleaning.** Document content is not sanitized against prompt
   injection before prompting — a malicious uploaded document's text reaches
   the model verbatim (tokenized). Mitigations are downstream: output
   scanning, strict plan/patch validation, bounded rewrites — not prevention.
3. **Figures written by the model.** In chat edits, record cleaning and
   AI-composed documents the model writes cell values and polished sentences;
   a wrong-but-plausible number inside an accepted completion is not
   detectable by shape scanning. Mitigations: validation rules re-run on
   edited tables, bounded-rewrite word-retention checks, human review before
   release (ws11 gates) — residual risk remains.
4. **No model pinning.** Models are addressed by name on the configured
   endpoint; there is no hash/version pinning of weights. A swapped or
   compromised model (or endpoint) returns arbitrary output; the output
   scanner and validators bound, but do not eliminate, the impact.
