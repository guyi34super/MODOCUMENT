# Bank-shared templates: visibility rule and call-site classification (ws22)

**Owner:** SE3 (ws22). **Reviewer:** Supervisor (phase-2 plan, top risk #1:
"template visibility split leaks templates"). **Enforced by:**
`TemplateVisibilityMatrixTest` (every route x every kind of caller, H2),
`TemplateVisibilityMakerCheckerTest` (gated submit/apply),
`TemplateVisibilityPgIT` (PostgreSQL, RLS enforced).

## The rule

`Template.Visibility` (V220) is `PRIVATE` (default) or `BANK`.

| | PRIVATE | BANK |
|---|---|---|
| **Read / use** (`TemplateOwnership.requireReadable`, `TemplateRepository.getAccessible` / `listAccessible`) | owner only | every user of the template's bank |
| **Write** (`TemplateOwnership.requireWritable`, `requireWritableBy`) | owner only | holders of `template:publish` (bank ADMIN), including the owner only while they hold it |
| **Another bank** | never | never |

- **Refusals.** A template the caller cannot read answers **404**. Another
  user's private template, another bank's template and a missing id cannot
  be told apart. A shared template the caller can read but not change
  answers **403**.
- **Read predicate.** The predicate is
  `OwnerUserId = caller OR (Visibility = 'BANK' AND BankId = caller's bank)`.
  The bank is always an explicit parameter, so the rule holds without row
  level security (H2). On PostgreSQL the Template RLS policy is unchanged and
  still hides every other bank's rows, shared or not.
- **No owner-only accessor.** `TemplateRepository.getByIdAndOwner` and
  `listByOwner` were removed. Every caller-facing lookup now goes through the
  visibility predicate, so the compiler forced each call site below to be
  classified. A new call site cannot quietly keep the old owner-only
  semantics, and it cannot skip the check either.
- **Maker/checker.** The TEMPLATE_UPDATE and TEMPLATE_DELETE flow is
  unchanged. Only its write rule widened:
  - At **submit**, the maker must pass `requireWritable`.
  - At **apply**, the write rule is judged again for the **maker**
    (`requireWritableBy(templateId, makerUserId)`), using the maker's current
    role and bank from `AppUsers`. It is never judged for the checker.
  - A maker who lost `template:publish` in between gets a 403, and the change
    becomes FAILED.
- **Sharing.** `PUT /Templates/{id}/Visibility` takes
  `{"visibility": "PRIVATE"|"BANK"}` and requires `template:publish`.
  - It is direct (no maker/checker): it changes who may *use* the template,
    not what the template extracts.
  - It is audited as `TEMPLATE_VISIBILITY_CHANGED` with the old and new
    value.
  - Any `template:publish` holder of the bank may change a shared template,
    including making it private again.
  - A private template is only visible to its owner, so only the owner can
    share it.
  - `POST /Templates/CreateWithFields` accepts `visibility`. `BANK` needs
    `template:publish` (403); an unknown value is a 400 validation error.
- **In use.**
  - Deleting a shared template answers **409** while users other than the
    owner have unfinished (PENDING/RUNNING) ingestion jobs on it.
  - Making a shared template private again answers **409** in the same case.
    Their job pages read the template's fields, so unsharing would break
    them mid-run.
  - The existing delete blockers still apply on top: any job or import
    referencing the template.
- **DTO.** `GET /Templates/GetAll` and `GetById` return `TemplateDto`. It
  keeps `templateId`, `name` and `description`, and adds:
  - `visibility`;
  - `ownedByMe`;
  - `editable` (the write rule, computed server-side for the UI);
  - `ownerName` (the display name of the colleague who owns a shared
    template, looked up in the caller's bank only).

## Call-site classification

**R** = read, **U** = use (the template id is referenced by something the
caller creates or runs), **W** = write, **C** = create, **I** = inherited
(authorized by an owner-scoped parent such as the job), **S** = system /
count-only.

| # | Call site | Route / trigger | Before ws22 | Class | Guard now | Refusal |
|---|---|---|---|---|---|---|
| 1 | `TemplateController.getAll` → `TemplateVisibilityUseCase.list` | `GET /Templates/GetAll` | `listByOwner` | R | `listAccessible(user, bank)`: own + BANK of own bank | — |
| 2 | `TemplateController.getTemplateById` → `TemplateVisibilityUseCase.get` | `GET /Templates/GetById/{id}` | `getByIdAndOwner` | R | `requireReadable` | 404 |
| 3 | `TemplateController.create` | `POST /Templates/Create` | none | C | always PRIVATE, owner = caller (body `visibility` ignored) | — |
| 4 | `CreateTemplateWithFieldsUseCase.handle` | `POST /Templates/CreateWithFields` | none | C | `visibility` BANK needs `template:publish` | 403 / 400 |
| 5 | `TemplateUpdateChangeApplier.prepare` (gate on) | `PUT /Templates/Update` | `getByIdAndOwner(caller)` | W | `requireWritable` (maker at submit) | 404 / 403 |
| 6 | `TemplateUpdateChangeApplier.apply` → `UpdateTemplateUseCase.handle(maker)` | `POST /api/admin/changes/{id}/approve` | `getByIdAndOwner(maker)` | W | `requireWritableBy(maker)` | 404 / 403 → FAILED |
| 7 | `UpdateTemplateUseCase.handle(caller)` (gate off) | `PUT /Templates/Update` | `getByIdAndOwner(caller)` | W | `requireWritableBy(caller)` = `requireWritable` | 404 / 403 |
| 8 | `TemplateDeleteChangeApplier.prepare` (gate on) | `DELETE /Templates/{id}` | `getByIdAndOwner(caller)` | W | `requireWritable` + shared-in-use guard (the maker hears it at submit) | 404 / 403 / 409 |
| 9 | `TemplateDeleteChangeApplier.apply` / gate off → `DeleteTemplateUseCase.handle` | approve / `DELETE /Templates/{id}` | `getByIdAndOwner` | W | `requireWritableBy` + shared-in-use guard + existing job/import blockers | 404 / 403 / 409 |
| 10 | `TemplateController.setVisibility` → `TemplateVisibilityUseCase.changeVisibility` | `PUT /Templates/{id}/Visibility` (new, `template:publish`) | — | W | `requireWritable`; unsharing refused while colleagues' jobs are unfinished | 404 / 409 (403 by route) |
| 11 | `TemplateFieldsController.getAll` | `GET /TemplateFields/GetAll?templateId=` | `require` (owner) | R | `requireReadable` | 404 |
| 12 | `TemplateFieldsController.getTemplateFieldsById` | `GET /TemplateFields/GetById/{id}` | `require` (owner) | R | `requireReadable(field.templateId)` | 404 |
| 13 | `TemplateFieldsController.create` | `POST /TemplateFields/Create` | `require` (owner) | W | `requireWritable` | 404 / 403 |
| 14 | `TemplateFieldsController.update` | `PUT /TemplateFields/Update/{id}` | `require` x2 (owner) | W | `requireWritable` on the current **and** the target template | 404 / 403 |
| 15 | `TemplateFieldsController.delete` | `DELETE /TemplateFields/{id}` | `require` (owner) | W | `requireWritable` | 404 / 403 |
| 16 | `ValidationRuleController.getByTemplate` | `GET /ValidationRules/GetByTemplate/{id}` | `require` (owner) | R | `requireReadable` | 404 |
| 17 | `ValidationRuleController.upsert` | `PUT /ValidationRules/Upsert` | `require` (owner) | W | `requireWritable` | 404 / 403 |
| 18 | `ValidationRuleController.delete` / `deleteByPath` | `DELETE /ValidationRules?…`, `DELETE /ValidationRules/{t}/{f}/{r}` | `require` (owner) | W | `requireWritable` | 404 / 403 |
| 19 | `ValidationController.validate` | `POST /Validation/validate` | `require` (owner) | U | `requireReadable` | 404 |
| 20 | ~~`TransformController.transformFile`~~ (removed in 1.2.0) | `POST /api/transform/transform` | `require` (owner) | U | `requireReadable` (after upload ownership and the AI template classification) | 404 |
| 21 | `IngestionJobUseCase.create` | `POST /api/ingestion/jobs` (multipart and JSON) | `getByIdAndOwner` | U | `getAccessible(user, bank)` | 404 |
| 22 | Ingestion pipeline: parse/map/validate, `AutoMapUseCase` (mapping history stays per owner), `ValidateRowsUseCase(job.templateId)`, chat edit, unmapped fields, job view, `PiiMaskingService.maskForExport(job.templateId)` on export | job routes `/api/ingestion/jobs/{id}/…` | `templateFields.listByTemplateId(job.templateId)` | I | the job is owner-scoped (`IngestionJobUseCase.get`); the template was usable when the job was created; unsharing/deleting while it runs is refused (#9, #10) | 404 (job) |
| 23 | `ExtractionSourceUseCase.validate` (create/update source) | `POST`/`PUT /api/extraction/sources` | `getByIdAndOwner` | U | `getAccessible(user, bank)` | 404 |
| 24 | `ExtractionJobUseCase.create` (explicit `templateId`) | `POST /api/extraction/jobs` | `getByIdAndOwner` | U | `getAccessible(user, bank)` | 404 |
| 24b | `ExtractionJobUseCase.create` (template inherited from the source) | `POST /api/extraction/jobs` | not re-checked | U | `getAccessible(user, bank)` again — a template made private (or deleted) since the source was saved is dropped from the job, so no spec is derived from fields the caller can no longer read | — (job runs without template) |
| 25 | `ExtractionJobUseCase.dataSpecFromTemplate` | job creation | — | I | runs only on a template id #24 / #24b accepted | — |
| 26 | `BridgeToTemplateUseCase.bridge` | `POST /api/extraction/jobs/{id}/bridge` | `getByIdAndOwner` | U | `getAccessible(user, bank)` (after the owner-scoped extraction job) | 404 |
| 27 | `ImportHistoryController.create` / `update` | `POST /ImportHistory/Create`, `PUT /ImportHistory/Update/{id}` | **none** | U | `requireReadable` — **new**: unchecked, the response's embedded `template` disclosed another user's template name/description | 404 |
| 28 | `AutoMapperController.generate` | `POST /api/automapper/generate` | template from the caller's own import (`getByFileNameAndOwner`) | I | owner-scoped import | 404 |
| 29 | `ImportHistoryRepositoryImpl.add`/`update` → `templateJpa.findById` | after #27 | — | S | FK resolution only, after the controller check | — |
| 30 | `AdminDashboardUseCase` → `countByBankIdAndVisibility` | `GET /api/admin/dashboard` | `countByBankId` | S | bank-wide counts only (private / shared) | — |
| 31 | `TemplateRepository.getById` / `list` | — | — | S | no caller-facing use left; kept for system paths | — |

Embedded copies:

- **TemplateFields.** `TemplateFields.template` (EAGER) is only returned by
  the field routes, which check `requireReadable` first (#11, #12).
- **ImportHistory.** `ImportHistory.template` is returned by the caller's
  own import history (owner-scoped).
- **Unshared later.** An import made while a template was shared keeps
  showing that template's name to its owner after the template is made
  private again. This is deliberate: it is the user's own history.

## Not changed / later

- **Integration API v1.** `GET /api/integration/v1/templates` (SE2, wave 3)
  must list `Visibility = 'BANK'` templates of the client's bank only.
- **Extraction mode (SE4, V230).** It is part of the template definition, so
  writing it goes through `requireWritable`. The parse step reads it for a
  job's template as an inherited read (#22).
- **The template picker in the upload pages.** It now lists shared templates
  too (`GET /Templates/GetAll`). Two owners may use the same name; the
  Templates page shows a Shared badge and the owner's name.
