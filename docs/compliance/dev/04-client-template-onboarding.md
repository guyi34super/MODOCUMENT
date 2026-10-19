# MO-009 Part 4 — Onboarding a client and "training" its templates

Part of the [MO-009 Developer & Technical Documentation set](../mo-009-developer-technical-documentation.md). This is the procedure as the code implements it: pages are under `frontend/src/pages/`, routes in `frontend/src/App.jsx`, sidebar labels in `frontend/src/components/Layout.jsx`.

## What "training" means in MoDocument

**There is no model fine-tuning or training anywhere in the product.** A search of `src/main/java` for fine-tuning or training code finds none; the only "traineddata" is Tesseract's English language file loaded by `src/main/java/com/modocument/infrastructure/file/OcrService.java` (C12). The LLM is used as-is, through `AiGateway`, with pseudonymised prompts (`docs/security/ai-controls.md`).

"Training" a client template therefore means **configuring** it, so that deterministic code reads the client's documents correctly:

| Lever | What it does | Proof |
|---|---|---|
| Template fields and data types | The target columns (`Text` / `Number` / `Date`) | `README.md` "Custom templates & validation" |
| Field rules | `REQUIRED`, `REGEX`, `DATE_FORMAT`, `EXPIRY_DATE`, `RANGE` (UI also offers an "Email address" pattern); regexes compiled and ReDoS-checked on save | `src/main/java/com/modocument/infrastructure/rule/`; `frontend/src/api/validationRules.js` |
| Extraction mode | `AI_WITH_CODE_FALLBACK` (default), `CODE_ONLY`, `AI_ONLY`; empty = the bank's `extraction.defaultMode` setting | `docs/phase2/contracts.md` §4; `src/main/java/com/modocument/infrastructure/transform/ExtractionModeResolver.java` |
| Field hints | `aliases`, `labelPattern`, `valuePattern` (one capture group), `dateFormats`, validated on save | `frontend/src/pages/TemplateExtractionSection.jsx`; `README.md` "Code-based fallback and extraction modes" |
| Learned mappings | The column mapper replays mappings from this template's past completed jobs (`mapping.history-jobs`, default 25), so a user's manual corrections are reused next time | `src/main/java/com/modocument/application/usecase/AutoMapUseCase.java`; `src/main/resources/application.yml` (`mapping`) |
| Mapping aliases (global) | Extra header aliases for every template (`mapping.aliases`) | `src/main/resources/application.yml` |
| Document-creation field rules and data templates | Rules on PDF fields, reusable data templates, field mapping | `docs/doccreation/field-validation-contract.md`; `docs/doccreation/data-templates-contract.md` |

Learned mappings are deleted with the jobs they come from by the retention purge (`src/main/java/com/modocument/infrastructure/retention/RetentionPurger.java`).

## 1. Onboarding a new client bank (platform owner)

Who: a `PLATFORM_ADMIN` of the platform bank (code `MOI-PLATFORM` by default, `MODOCUMENT_PLATFORM_BANK_CODE`). All bank-lifecycle routes need `platform:banks` and are maker/checker (`docs/platform/platform-owner.md` "Bank lifecycle").

| Step | UI | API | Notes |
|---|---|---|---|
| 1. Open the console | Platform → Overview (`PlatformOverviewPage.jsx`, `/platform`) | `GET /api/platform/overview` | Counts only |
| 2. Onboard the bank | Platform → Banks → "Onboard bank" (`PlatformBanksPage.jsx`, `/platform/banks`) | `POST /api/platform/banks` `{code, name, users:[…]}` → 202 pending change `BANK_CREATE` | 2–20 first users; at least one ADMIN and at least two ADMIN/APPROVER; temporary passwords pass the password policy |
| 3. Second platform admin approves | Administration → Approvals (`PendingChangesPage.jsx`, `/admin/changes`) | `POST /api/admin/changes/{id}/approve` | The maker cannot approve their own change (409) |
| 4. Set platform-controlled settings for the bank (optional) | Platform → Banks → bank → Settings (`SettingsPage.jsx`, `/platform/banks/:id/settings`) | `GET/PUT /api/platform/banks/{bankId}/settings` | E.g. which API-client authentication methods the bank may use (`docs/settings/catalogue.md`) |
| 5. Later: suspend, reactivate, break-glass admin | `PlatformBankDetailPage.jsx` (`/platform/banks/:id`) | `POST /api/platform/banks/{id}/suspend` · `/reactivate` · `/admins` | Suspend and add-admin are maker/checker |

On approval the bank and all its first users are created in one transaction, each with `MustChangePassword`; ADMINs enrol TOTP at first sign-in (`docs/platform/platform-owner.md`). Audit: `BANK_ONBOARDED`, `USER_CREATED`.

Contractual onboarding steps (agreement, DPIA, data-processing terms) are outside the code: `TO CONFIRM (organisational)`; the DPIA template is `docs/security/dpia-template.md`.

## 2. Users and roles

| Step | UI | API |
|---|---|---|
| First sign-in, forced password change (+ MFA enrolment for admins) | `LoginPage.jsx`, `ForceChangePasswordPage.jsx` | `POST /api/auth/login`, `PUT /api/auth/password`, `/api/auth/mfa/*` |
| Request a user (email, display name, role, temporary password) | Administration → Users (`AdminUsersPage.jsx`, `/admin/users`) | `POST /api/admin/users` → pending change |
| A second admin approves | Administration → Approvals (`PendingChangesPage.jsx`) | `POST /api/admin/changes/{id}/approve` |
| Change role, disable, unlock, reset password or MFA | `AdminUsersPage.jsx` | `PUT /api/admin/users/{id}/role`, `PUT …/disabled`, `POST …/unlock`, `…/password-reset`, `…/mfa-reset` |
| Quarterly access review | `AdminUsersPage.jsx` | `GET /api/admin/users/access-review` |

Pick the lowest role that does the job (`READ_ONLY` unless there is a reason otherwise) (`docs/security/jml-runbook.md` "Joiner"). OIDC accounts are provisioned just-in-time on first SSO login (`docs/security/identity-providers.md`). Roles and permissions: [Part 3 §4](03-api-reference.md#4-authorisation-roles-and-permissions).

## 3. Document Flow, step 1: design the template

Sidebar **Document Flow → 1. Design Templates** (`TemplatesPage.jsx` at `/templates`, `TemplateEditorPage.jsx` at `/templates/new` and `/templates/:id`).

1. Create the template with its fields and data types. API: `POST /Templates/CreateWithFields` (fields and rules in one transaction; invalid regexes are a 400 naming the field and rule) or `POST /Templates/Create` plus `POST /TemplateFields/Create`.
2. Set the **extraction mode** and each field's **hints** in the template's extraction section (`TemplateExtractionSection.jsx`, inside `TemplateEditorPage.jsx`). API: `PUT /Templates/Update` (mode) and `PUT /TemplateFields/Update/{id}` (hints). A dangerous or invalid pattern is a 400 shown next to the input.
3. Share it with the bank when other users, the Integration API or document creation need it: `PUT /Templates/{id}/Visibility` `{"visibility":"BANK"}` (needs `template:publish`, i.e. ADMIN). New templates are `PRIVATE` (`docs/phase2/template-visibility.md`).
4. Template update and delete are maker/checker outside the `local` profile (`MODOCUMENT_MAKER_CHECKER_TEMPLATE_CHANGES`; `src/main/resources/application-local.yml` sets `template-changes: false`).

## 4. Document Flow, step 2: Set Field Rules

Sidebar **2. Set Field Rules** (`ValidationRulesPage.jsx` at `/validation`, with `RegexBuilder.jsx`).

- Per field: a required flag and one format rule — email, pattern (regex), number range, date format, expiry date (`frontend/src/api/validationRules.js`).
- API: `GET /ValidationRules/GetByTemplate/{templateId}`, `PUT /ValidationRules/Upsert`, `DELETE /ValidationRules/{templateId}/{fieldName}/{ruleType}`; test rows with `POST /Validation/validate`.
- Regex matching runs with a 200 ms deadline against catastrophic backtracking (`README.md` "Custom templates & validation").

## 5. Document Flow, step 3: Upload & Process (ingestion jobs)

Sidebar **3. Upload & Process** (`IngestionListPage.jsx` at `/ingestion`, `IngestionJobPage.jsx` at `/ingestion/:id`, provenance badge `IngestionProvenance.jsx`).

1. Upload a sample document against the template: `POST /api/ingestion/jobs` (multipart `file`, `templateId`, `autoRun`). Supported types: `GET /api/ingestion/supported-types`.
2. The job runs `UPLOADED → PARSED → MAPPED → VALIDATED → EXPORTED`, checkpointing each stage; a failed job resumes from its last stage (`POST /api/ingestion/jobs/{id}/resume`) (`README.md` "Resumable ingestion jobs").
3. Check provenance: `parsedBy` (`AI` / `CODE` / `AI_PARTIAL`), `parseStrategy`, `parseConfidence`, `parseFallbackReason` (`README.md`). A low confidence or a code fallback tells you which hints to add (step 3.2).
4. Review the mapping (wizard step MAPPING) and correct it: `PUT /api/ingestion/jobs/{id}/mapping`. The correction becomes a learned mapping for the next jobs of this template once the job completes.
5. Validate and fix cells (VALIDATION): `PUT …/rows`, `POST …/revalidate`, or the AI chat edit `POST …/chat-edit` (needs `ai:use` and a reachable model).
6. Export (EXPORT): `GET …/export/file?format=csv|json|xlsx|tsv|xml` or `POST …/export/database`; above the role's row cap a reason is required (`docs/security/alerting-and-export-caps.md`).
7. Repeat with a few real-shaped samples until mapping and validation need no manual correction. Use synthetic or approved data only.

## 6. Document Creation (exact PDF templates)

Sidebar **Document Creation**, four numbered steps (`frontend/src/components/Layout.jsx`); contracts in `docs/doccreation/`.

| Step | Page and route | Main API | Notes |
|---|---|---|---|
| 1. Upload document | `BlueprintListPage.jsx` (`/creation/blueprints`), `BlueprintDetailPage.jsx` (`/creation/blueprints/:id`) | `POST /api/doccreation/blueprints` (`file`, `flow=BLANK\|WITH_DATA\|AUTO`) → DRAFT; `PUT …/blueprints/{id}/overlay` to confirm or save a new version | Only real PDFs; the template is the original PDF; regions are detected by code, AI may improve labels (`doccreation.sectionAiEnabled`) (`docs/doccreation/pdf-templates-contract.md`) |
| 2. Validation (data templates) | `DataValidationPage.jsx` (`/creation/data-templates`) | `/api/doccreation/data-templates` (create, copy from flow templates, `PUT …/fields`, `PUT …/rules`, `POST …/rules/test`) | Reusable field sets and rules, made from scratch or copied from flow templates (`docs/doccreation/data-templates-contract.md`) |
| 3. Map fields | `DataMappingListPage.jsx` (`/creation/mapping`) and the template's "Map fields" step | `PUT /api/doccreation/blueprints/{id}/data-template`; `GET/PUT …/data-mapping`; data sources `GET/POST/PUT/DELETE …/sources` | Fields matched by name and aliases; suggestions accepted in one click |
| 4. Fix documents | `DocInstancesPage.jsx` (`/creation/documents`), `DocBuilderPage.jsx` (`/creation/documents/:id`) | `POST /api/doccreation/instances`; `PUT …/values`; `GET …/fill-preview`, `POST …/fill-from-record`; `POST …/validate`; `POST …/generate`; `GET …/download` | A document is generated and downloaded only once validated, unless validation is off |

- **Validation on/off.** `PUT /api/doccreation/blueprints/{id}/validation-setting {enabled}` sets the default for new documents; each document has its own switch (`PUT /api/doccreation/instances/{id}/validation-setting`). With validation off the steps are Upload / Map fields / Documents and nothing is gated on validation (`docs/doccreation/data-templates-contract.md` §8; migration `V334__ws34_doc_validation_switch.sql`).
- **Data sources and records.** Validated rows of flow-template jobs are indexed as records (`TemplateRecord`); the builder's "Fill from data" searches them (`GET /api/doccreation/records`), masked per role (`docs/doccreation/flow-records-contract.md`).
- **Release review.** Submit, release or reject generated documents under Administration → Releases (`PendingReleasesPage.jsx`, `/approvals/releases`; `doc:release`).

## 7. Data extraction from external sources (optional)

Sidebar **Data Extraction** (`ExtractionSourcesPage.jsx`, `ExtractionJobsPage.jsx`, `ExtractionJobDetailPage.jsx`). Register a source with `termsAcknowledged: true` (`POST /api/extraction/sources`), run a job (`POST /api/extraction/jobs`), optionally infer a template (`POST …/infer-schema`), then bridge the records into a normal ingestion job against the template (`POST …/bridge`) (`README.md` "Extraction walkthrough").

## 8. Integration flows (connecting the bank's systems)

Sidebar **Integrations** (ADMIN; `docs/integration/developer-guide.md`, `docs/integration/agent.md`):

| Step | Page | API |
|---|---|---|
| Approve outbound destinations | Egress destinations (`IntegrationEgressRulesPage.jsx`); platform side `PlatformEgressRulesPage.jsx` | `/api/admin/integrations/egress-rules`, `/api/platform/egress-rules` |
| Install an agent (when the bank's systems are not reachable from the cloud) | Agents (`IntegrationAgentsPage.jsx`) | `POST /api/admin/integrations/agents` → approval → one-time enrolment token |
| Create a connection (DIRECT or AGENT; REST_API, DATABASE, SFTP, WEBHOOK) and test it | Connections (`IntegrationConnectionsPage.jsx`, `ConnectionWizard.jsx`) | `/api/admin/integrations/connections` (maker/checker `INTEGRATION_CONNECTION_CHANGE`) |
| Create a flow: INBOUND or OUTBOUND, trigger MANUAL / SCHEDULE / EVENT, template, field mapping, PII masking, row cap | Flows (`IntegrationFlowsPage.jsx`, `IntegrationFlowBuilderPage.jsx`) | `/api/admin/integrations/flows` (`POST …/flows/{id}/preview` writes nothing) |
| Watch runs and retry | Runs (`IntegrationRunsPage.jsx`, `IntegrationRunDetailPage.jsx`) | `/api/admin/integrations/runs` (`integration:run`) |
| Let the bank's systems call in | API clients (`IntegrationApiClientsPage.jsx`) | `/api/admin/integrations/api-clients` → Integration API v1 ([Part 3 §6](03-api-reference.md#6-integration-api-v1-bank-systems)) |

Vocabularies: `docs/phase2/contracts.md` §4; REST shapes: §7.3.

## 9. Onboarding checklist (per client)

| # | Item | Owner |
|---|---|---|
| 1 | Bank onboarded and approved; first admins signed in, passwords changed, MFA enrolled | Platform owner; bank admins |
| 2 | Bank settings reviewed (sign-in, rate limits, data protection, AI & extraction, integrations) | Bank admin (`/admin/settings`) |
| 3 | Users created at the lowest suitable role | Bank admin + second approver |
| 4 | Each flow template designed, rules set, extraction mode and hints configured, shared with the bank if needed | Implementation staff (`TO CONFIRM (organisational)`: which team) |
| 5 | Sample documents ingested until mapping and validation are stable | Implementation staff |
| 6 | PDF templates uploaded, data templates and mappings configured, validation on/off decided | Implementation staff |
| 7 | Integration egress rules, connections, flows, API clients or agent set up and tested | Bank IT + bank admin |
| 8 | Alert channels and SIEM feed wired for the bank ([Part 5](05-operations-logging-backup.md)) | Operations (`TO CONFIRM (organisational)`) |
