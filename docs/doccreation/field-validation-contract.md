# Field editing, field rules and document validation: contract (ws33)

This contract covers three changes to document creation:

- **Field properties.** Template fields (regions) get richer properties and a dropdown kind.
- **Field rules.** Each PDF template (blueprint) gets its own rules for its fields. Rules can be written by hand or imported from flow-template field rules.
- **Validation.** A document is validated before it counts as saved. Otherwise it stays a draft.

The BE-A, BE-B and FE streams and QA all build against this contract. Shapes change only through the Supervisor.

Wave 0 created these pieces, which are already on the branch:
- **Migrations:** V320, V321 and V322.
- **Entities:** `DocTemplateRegion.fieldPropsJson`, `DocBlueprint.rulesRevision`, the validation columns and `STATUS_VALIDATED` on `DocInstance`, and a new `DocFieldRule` entity with its `DocFieldRuleJpaRepository`.
- **Shared types:** `doccreation/rules/DocRuleType` and `doccreation/fields/FieldProps`.
- **Error code:** `DocCreationException.NOT_VALIDATED` with `notValidated()`.
- **Audit actions:** `DOC_FIELD_RULES_SAVED`, `DOC_FIELD_RULES_IMPORTED` and `DOC_VALIDATED`, also listed in `docs/security/audit-events.md`.

## 1. Database (ws33, V320–V329)

| Migration | Change |
|---|---|
| **V320** | `DocTemplateRegion.FieldPropsJson text NULL`: the `FieldProps` JSON (§2). Null means no properties are set. |
| **V321** | New table `DocFieldRule` (columns below) and `DocBlueprint.RulesRevision int NOT NULL DEFAULT 0`. Backfill: for every region of each blueprint's current version with `Required = true`, add one `REQUIRED` rule with `Origin = 'REGION_FLAG'`. |
| **V322** | `DocInstance` gains `ValidatedAt`, `ValidatedByUserId`, `ValidatedHash varchar(64)`, `ValidatedRulesRevision` and `ValidationJson text`. The CHECK `ck_docinstance_status` now also allows `VALIDATED`. |

The `DocFieldRule` columns are:
- `DocFieldRuleId`, `BankId`, `BlueprintId` (cascade on delete), `RegionKey`
- `RuleType`: the `DocRuleType` name
- `ConfigJson`: at most 4 kB
- `Message`: at most 300 characters, null for the default text
- `Severity`: `ERROR` or `WARNING`
- `Origin`: `MANUAL`, `IMPORTED` or `REGION_FLAG`
- `SourceTemplateId` and `SourceFieldName`
- `SortOrder` and `UpdatedAt`

The new table carries `BankId`, so row-level security covers it, and it must be added to the `RlsPolicyCoveragePgIT` inventory. Deleting a blueprint also deletes its rules: the database cascades, and the use case deletes them explicitly as well.

## 2. Field properties and the new CHOICE kind (BE-B)

### Properties on `RegionDto`

`RegionDto` gains a last component, `FieldProps props`, which may be null. It is used both in responses and as input to `PUT /blueprints/{id}/overlay`.

```json
"props": {
  "placeholder": "e.g. Jane Doe",
  "helpText": "As on the ID card",
  "defaultValue": "Mauritius",
  "options": ["Single", "Married"],
  "dateFormat": "dd/MM/yyyy",
  "numberFormat": { "decimals": 2, "thousands": true, "prefix": "Rs ", "suffix": null },
  "maxLength": 40
}
```

`OverlayMapper.fromDtos` checks the properties. Each failure is an `IllegalArgumentException`, which becomes a 400. The message starts with `Field "<key>": `.

| Property | Rule |
|---|---|
| `placeholder` | at most 200 characters |
| `helpText` | at most 500 characters |
| `defaultValue` | at most 2000 characters |
| `options` | at most 200 entries, each 1–200 characters after trimming, no duplicates; **required, with at least one entry, for CHOICE**; ignored (stored as null) for other kinds |
| `dateFormat` | one of `FieldProps.DATE_FORMATS`; DATE fields only |
| `numberFormat` | `decimals` 0–6, `prefix` and `suffix` at most 5 characters each; NUMBER fields only |
| `maxLength` | 1–20000; text kinds only (TEXT, MULTILINE, CHOICE) |

Other rules:
- A property that doesn't fit the field's kind is dropped (stored as null), not refused. The exception is CHOICE without options, which is refused.
- A `defaultValue` must pass the built-in kind checks (§4.2). For example, a DATE default must be an ISO date and a CHOICE default must be one of the options.
- The properties are stored as `FieldPropsJson`. An empty `FieldProps` is stored as null.
- The response always includes `props`, or null when no properties are set.

### The CHOICE kind
- `RegionKind` gains `CHOICE`, a dropdown. `Kind` is `varchar(16)` with no CHECK, so no migration is needed.
- `maxLines` is forced to 1, as for TEXT.
- `PdfTemplateFiller` draws a CHOICE value as single-line text, the same as TEXT.
- **AcroForm fields:** a `PDChoice` field gets a value only when the value is one of its options. Otherwise the drawn-text path is used. The clear-on-failure fallback also clears `PDChoice` fields.
- `PdfAnalyzer` turns a `PDComboBox` or `PDListBox` widget into a CHOICE region, with `props.options` taken from the widget's options. It previously became TEXT.

### Fonts and colour
- The font name is either one of the bundled Liberation fonts or the name detected at upload. `GET /blueprints/{id}` gains `fonts: [ ... ]`, the names a user may pick (the bundled list).
- The font size is 0–144, where 0 means auto (unchanged).
- The colour is `#RRGGBB` (unchanged).

### Formatting values in the PDF (BE-A, in `DocInstanceUseCase.fill()`)
- A new `doccreation/fields/FieldValueFormatter` runs after PII masking. If masking changed a value, that value is drawn as it is.
- DATE values are stored as ISO `yyyy-MM-dd` and drawn with `props.dateFormat` (English locale). A value that doesn't parse is drawn as typed.
- NUMBER values are drawn with `props.numberFormat`: rounding is HALF_UP, `thousands` groups digits with `,`, and the prefix and suffix are added. A value that doesn't parse is drawn as typed.
- Preview pages, generate and download all use the same formatting.

### Default values
- `DocInstanceUseCase.create` writes each region's `defaultValue` into the values.
- `upgrade` writes defaults only for keys the old version did not have.

## 3. Field rules (BE-B)

### Rule types

Rule types are `DocRuleType` names. Config keys are case-sensitive, as written below.

| Type | Config | Fails when (non-blank value, unless noted) | Default message |
|---|---|---|---|
| `REQUIRED` | `{}` | the value is blank (or, for IMAGE/SIGNATURE, no photo is placed) | `"<Label> is required."` |
| `REGEX` | `{"Pattern": "..."}` | `SafeRegex` unanchored `find()` doesn't match (200 ms limit; a timeout fails) | `"<Label> is not in the expected format."` |
| `EMAIL` | `{}` | not `^[^@\s]+@[^@\s]+\.[^@\s]+$` | `"<Label> must be an e-mail address."` |
| `LENGTH` | `{"Min": n?, "Max": n?}` | text length (code points, trimmed) is outside Min..Max | `"<Label> must be between Min and Max characters."` (or "at least" / "at most") |
| `RANGE` | `{"Min": n?, "Max": n?}` | not a number (BigDecimal, "," removed), or outside Min..Max | `"<Label> must be a number between Min and Max."` |
| `ALLOWED_VALUES` | `{"Values": [..], "IgnoreCase": bool?}` | not in the list | `"<Label> must be one of: a, b, c."` (at most 10 listed, then "…") |
| `DATE_FORMAT` | `{"Formats": ["dd/MM/yyyy", ..]?}` | doesn't parse (strict) with any listed format; the default is ISO | `"<Label> must be a date like 31/12/2026."` |
| `DATE_RANGE` | `{"Min": d?, "Max": d?}` | not a date, or outside Min..Max | `"<Label> must be on or after … / on or before …"` |
| `EXPIRY_DATE` | `{}` | the date is not after today | `"<Label> must be a future date."` |
| `REQUIRED_IF` | `{"Field": key, "Equals": v?}` | blank while the other field is filled (or, with `Equals`, equals `v` after trimming, ignoring case) | `"<Label> is required when <Other> is filled."` (or `"… is <v>."`) |
| `COMPARE` | `{"Field": key, "Op": "<\|<=\|=\|!=\|>=\|>"}` | this value `Op` the other field's value is false. Both are compared as dates if both parse as dates, else as numbers if both parse as numbers, else as trimmed strings (only `=` and `!=`). Skipped when the other field is blank | `"<Label> must be <op word> <Other>."` |

Rules for evaluating:
- Every type except `REQUIRED` and `REQUIRED_IF` passes a blank value (`DocRuleType.checksBlank()`).
- Dates are ISO, or `today`, or relative values `±N(d|w|m|y)`, using the injected `Clock` in the bank's zone (UTC by default).
- IMAGE and SIGNATURE fields support `REQUIRED` only.
- A CHECKBOX field supports `REQUIRED` (it must be ticked) and `REQUIRED_IF`.
- A rule that doesn't fit its field's kind is kept, skipped when validating and reported as a **setup warning** (§4.2).
- The default messages use the field's label. A rule's own `Message` replaces the default text.
- **Messages never contain the value.**

### Checks when rules are saved (400)
- The type must be known.
- The region key must exist in the blueprint's **current** version. On import, keys that don't exist are skipped and reported.
- `REGEX` patterns go through `RegexPatternChecker` (2000-character cap, must compile).
- `Min` must not be greater than `Max`.
- `Values`: at most 200 entries, each at most 200 characters.
- Formats must be valid `DateTimeFormatter` patterns, at most 20.
- `Field` (for `REQUIRED_IF` and `COMPARE`) must be another existing key, never the same field.
- `Op` must be one of the six operators.
- `Message` is at most 300 characters; `Severity` is `ERROR` or `WARNING`.
- At most 50 rules per field and 2000 per blueprint.

### Routes

All routes are under `/api/doccreation/blueprints/{id}`. The blueprint must be readable by the caller in the caller's bank; changes need ownership (`DocCreationOwnership`, the same as the overlay).

| Route | Permission | Body / reply |
|---|---|---|
| `GET /rules` | `DOC_READ` | `RulesDto` |
| `PUT /rules` | `DOC_WRITE` | `{baseRevision, rules: [RuleInput]}` replaces the whole set and returns `RulesDto`. A stale `baseRevision` returns **409**. `RulesRevision` goes up by 1. Audit `DOC_FIELD_RULES_SAVED`. |
| `POST /rules/test` | `DOC_READ` | `{values: {key: value}, rules?: [RuleInput]}` returns `ValidationResultDto` (§4.2). Nothing is stored. When `rules` is present it is tested instead of the saved rules (for testing before saving). |
| `GET /rules/import-preview?templateId=` | `DOC_WRITE` + `VALIDATION_READ` | `ImportPreviewDto` |
| `POST /rules/import` | `DOC_WRITE` + `VALIDATION_READ` | `{baseRevision, templateId?, mapping: {regionKey: fieldName}, mode: "MERGE"\|"REPLACE"}` returns `RulesDto`. Audit `DOC_FIELD_RULES_IMPORTED`. |

```json
RulesDto = { "revision": 3,
  "rules": [{ "id": 12, "regionKey": "dob", "ruleType": "DATE_RANGE",
              "config": {"Max": "-18y"}, "message": null, "severity": "ERROR",
              "origin": "IMPORTED", "sourceTemplateId": 4, "sourceTemplateName": "Customers",
              "sourceFieldName": "date_of_birth", "sortOrder": 0, "orphan": false }],
  "fields": [{ "key": "dob", "label": "Date of birth", "kind": "DATE", "page": 1 }],
  "ruleTypes": [{ "type": "DATE_RANGE", "kinds": ["DATE","TEXT"], "configKeys": ["Min","Max"] }] }

RuleInput = { "regionKey", "ruleType", "config": {..}, "message"?, "severity"?,
              "origin"?, "sourceTemplateId"?, "sourceFieldName"? }   // sortOrder = list order per field
```

- `orphan` is true when the region key is not in the current version.
- `fields` lists the current version's regions, in sort order.
- `ruleTypes` lists, for each type, the kinds it fits and its config keys. The UI uses it to build its forms.

### Importing from a flow template
- **Data sources.** With no `templateId`, the import uses the blueprint's saved data sources: `DocBlueprintSource.MappingJson`, for every source. `mapping` from the client may narrow it.
- **A chosen template.** With a `templateId`, `TemplateOwnership.requireReadable(templateId)` is checked, and the mapping is auto-matched: for each region, the best field by `ColumnNameSimilarity` (label and key, the higher score counting), one-to-one, threshold 0.7. A score of 1.0 is `sameName`. The client's `mapping` replaces the auto-match.

Flow rules are converted one by one (`ValidationRules` rows of each mapped field):

| Flow rule | Becomes |
|---|---|
| `REQUIRED` | `REQUIRED` |
| `REGEX {Pattern}` | `REGEX {Pattern}`, or `EMAIL` when the pattern equals the flow UI's EMAIL preset |
| `RANGE {Min,Max}` | `RANGE` |
| `DATE_FORMAT` | `DATE_FORMAT {Formats: ["yyyy-MM-dd"]}` |
| `EXPIRY_DATE` | `EXPIRY_DATE` |
| `CONDITIONAL_REQUIREMENT`, `TYPE`, `CUSTOM` | skipped, with the reason "can't be imported" |

Imported rules get:
- `Origin = IMPORTED`, with `SourceTemplateId` and `SourceFieldName`.
- For REGEX, `Message` set from `RegexValidationErrorMessages` with the **source field name**, when that gives specific text (not the generic "Validation error for X.").

The two modes:
- `MERGE` replaces only existing rules with the same (regionKey, ruleType, sourceTemplateId) and keeps everything else.
- `REPLACE` deletes all IMPORTED rules of the mapped region keys first, then adds the new ones. MANUAL and REGION_FLAG rules are kept.

```json
ImportPreviewDto = { "source": "DATA_SOURCES" | "TEMPLATE", "templateId": 4, "templateName": "Customers",
  "fields": [{ "regionKey": "dob", "label": "Date of birth", "fieldName": "date_of_birth",
               "score": 0.92, "sameName": false,
               "rules": [{ "ruleType": "DATE_FORMAT", "config": {...}, "message": null }],
               "skipped": [{ "flowRuleType": "CUSTOM", "reason": "can't be imported" }] }],
  "templateFields": ["date_of_birth", "full_name"] }
```

With `DATA_SOURCES`, `fields` covers every mapped region of every source, and `templateId`/`templateName` are those of each field's source (one entry per region and source).

### Region `required` flag
The rules are the single source of truth.
- The overlay save still writes the region's `required` column, for rollback.
- `RegionDto.required` in responses is derived: true when the blueprint has a REQUIRED rule for that key.
- The editor's Required toggle edits the rules (FE: `PUT /rules`).

## 4. Validating a document (BE-A)

### 4.1 Status flow

```
DRAFT --validate ok--> VALIDATED --generate--> GENERATED --submit--> SUBMITTED --release--> RELEASED
  ^                        |                        |                     |
  +---- real change -------+------- real change ----+          reject → GENERATED
```

- **A failed validate** leaves a DRAFT as a DRAFT. On a VALIDATED or GENERATED document it moves the status back to DRAFT. In both cases `ValidationJson` keeps the errors.
- **A real change** sends a VALIDATED or GENERATED document back to DRAFT and clears `ValidatedAt` and `ValidatedHash`. A real change is:
  - `updateValues` or `fillValues` that changes the stored values (compare the map before and after the merge);
  - `upgrade` that changes the version;
  - `placeImage` and `deleteImage` of a placed photo;
  - `uploadImages` with autoFill that placed at least one photo.

  Renaming and uploading to the tray are not real changes. `rename` no longer calls `touch()`.
- **Locking:** `validate` and every content-changing call lock the document row (`PESSIMISTIC_WRITE`, through a new repository method `findForUpdate`). That way a validate and an autosave can't interleave.
- **SUBMITTED and RELEASED** documents stay locked against changes (409, unchanged). `validate` on them also returns 409.

### 4.2 `POST /api/doccreation/instances/{id}/validate`
- Needs `DOC_WRITE`; the document must be the caller's own (owner-scoped, as for `updateValues`).
- It runs:
  - **The built-in checks** on every region of the document's version:
    - DATE values must be ISO `yyyy-MM-dd`, otherwise code `DATE_INVALID`.
    - NUMBER values must parse as a BigDecimal after removing `,`, otherwise `NUMBER_INVALID`.
    - CHOICE values must be one of the options, otherwise `CHOICE_INVALID`.
    - `maxLength`, otherwise `TOO_LONG`.
    - CHECKBOX values must be `true` or empty, otherwise `CHECKBOX_INVALID`.
  - **The blueprint's rules** for the keys in this version.
- **If there are no ERRORs:** status VALIDATED; `ValidatedAt` = now, `ValidatedByUserId` = the caller, `ValidatedRulesRevision` = the blueprint's `RulesRevision`, and `ValidatedHash` = sha256 hex of the canonical JSON `{"v": sortedValues, "bv": blueprintVersion, "img": sorted "regionKey=imageId" of placed photos}`.
- **Otherwise:** status DRAFT, and the hash and validated fields are cleared.
- **Always:** `ValidationJson` = the result.
- **Audit `DOC_VALIDATED`**, SUCCESS or FAILED: error and warning counts, the keys with errors, the rules revision.
- **The reply is `InstanceDto`**, with `validation` filled in.

`ValidationResultDto`, as used in the `/rules/test` reply and in `InstanceDto.validation`:

```json
{ "state": "VALIDATED" | "DRAFT",
  "validatedAt": "2026-10-16T10:00:00" | null,
  "rulesRevision": 3,
  "rulesChangedSinceValidation": false,
  "errors":   [{ "regionKey": "dob", "label": "Date of birth", "ruleId": 12, "code": "DATE_RANGE", "message": "..." }],
  "warnings": [{ ... same shape ... }],
  "setupWarnings": [{ "ruleId": 40, "regionKey": "photo", "message": "RANGE does not apply to a photo field; skipped." }],
  "checked": true }
```

- `code` is the rule type, or a built-in code.
- `ruleId` is null for built-in checks.
- `checked` is false while the document has never been validated. `errors` are then empty and the UI shows only its live hints.
- `rulesChangedSinceValidation` is true when the blueprint's `RulesRevision` is not equal to `ValidatedRulesRevision`. This does not change the status.

### 4.3 Gates
- **`generate` and `download`** check the following before the stale-file check:
  1. The status must be VALIDATED, GENERATED, SUBMITTED or RELEASED, otherwise `DocCreationException.notValidated()` (409, `NOT_VALIDATED`).
  2. For VALIDATED and GENERATED documents, the current content hash must equal `ValidatedHash`, otherwise the same 409.
- **Existing documents:** GENERATED documents from before V322 have a null `ValidatedHash`. Treat null as "validated before ws33" and allow it, but **only for documents whose `ValidatedAt` is null and whose status is GENERATED**. The first real change sends them to DRAFT as usual.
- **`generate`** promotes VALIDATED → GENERATED. It re-reads the document before saving: if `UpdatedDate` changed while rendering, it doesn't promote and returns 409 "The document changed while it was being generated; generate again."
- **SUBMITTED and RELEASED** documents are allowed to download and regenerate as before.
- **`submit`** still requires GENERATED and not stale.
- **`reject`** returns the document to GENERATED (unchanged).
- **The preview pages** (`GET /instances/{id}/pages/{n}`) always work.

### 4.4 `InstanceDto` and `InstanceSummaryDto`
- `InstanceDto` gains as its last components:
  - `ValidationResultDto validation`
  - `Map<String, FieldProps> props`, the properties of each region key of the document's version
- `RegionDto` inside `InstanceDto.regions` also carries `props`.
- `InstanceSummaryDto` (the list of my documents) has its status updated as is; the frontend shows a Validated badge for `VALIDATED`.
- `GET /instances?status=` gains an optional status filter.

## 5. Frontend (FE)

**API modules:**
- `api/docRules.js`: `getRules`, `saveRules`, `testRules`, `importPreview`, `importRules`.
- `api/doccreation.js`: `validateInstance`, and `listInstances({status})`.

**Template editor** (`BlueprintDetailPage`):
- A shared hook `components/pdf/useOverlayEditor.js`: undo/redo, multi-select, duplicate, copy/paste, align/distribute/same size, snap with guides, zoom 50–200 %, nudge.
- A toolbar.
- A field list with search, filters and reordering; drag and the Move up/down buttons set `sortOrder`.
- `RegionPropertiesPanel` in sections (Basics, Appearance, Input, Rules), with multi-edit.
- A new **Field rules** tab, covering add/edit/delete rules, import (data sources or a template), and test with sample values.

**Builder** (`DocBuilderPage`), split first into `components/doc/*`:
- **Save** flushes the autosave first; if that failed, it stops. Then it calls `validateInstance`.
- An Issues panel, errors shown on each field, and a progress line.
- Generate, Download and Submit are disabled while the status is DRAFT, with the reason "Save and validate first".
- Placeholder, help text and options are used in the inputs; CHOICE is a `<select>`.

**My documents** (`DocInstancesPage`): a status filter.

**Badges:** `components/ui.jsx` gets a Badge colour for `VALIDATED`.

## 6. Ownership

| Stream | Owns |
|---|---|
| **BE-A** | `DocInstanceUseCase` (validate, gates, reset to DRAFT, locking, defaults on create/upgrade, formatting in `fill()`), `DocReleaseUseCase` touch-ups, `DocInstanceController` (validate route, status filter), `InstanceDto`/`InstanceSummaryDto`, `doccreation/fields/FieldValueFormatter`, `doccreation/rules/DocRuleEvaluator` (+ `ValidationResultDto`), `DocInstanceJpaRepository.findForUpdate`. Tests for all of these, and updates to existing tests that generate documents. |
| **BE-B** | `RegionDto.props` + `OverlayMapper` (properties, CHOICE, derived `required`), `RegionKind.CHOICE` + `PdfTemplateFiller`/`PdfAnalyzer` CHOICE, `GET /blueprints/{id}` `fonts`, the rules routes (`DocFieldRuleController`, `DocFieldRuleUseCase`, import, test via BE-A's `DocRuleEvaluator` interface), blueprint delete cleanup, `docs/api/openapi-routes.txt`, the `RlsPolicyCoveragePgIT` inventory, README, CHANGELOG. |
| **FE** | `frontend/src/**`. |
| **QA (wave 2)** | the E2E specs: updated PDF/REC specs, plus a new `e2e/tests/phase2/doc-validation.spec.js` (`@validation`). |

**`DocRuleEvaluator` interface**, which BE-A owns and BE-B calls:

```java
ValidationResultDto evaluate(List<RegionView> regions, Map<String,String> values,
                             Set<String> placedImageKeys, List<DocFieldRule> rules, int rulesRevision);
// RegionView = (key, label, kind, FieldProps props)
```

BE-A publishes this signature first (a stub is fine). BE-B's `/rules/test` calls it, with no placed images.
