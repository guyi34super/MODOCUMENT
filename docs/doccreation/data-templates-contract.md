# Document creation flow: contract (ws34)

Document creation follows the Document Flow idea, in the user's order. The four steps are the numbered entries under "Document Creation" in the sidebar:

1. **Upload document** (`/creation/blueprints`): upload a PDF and confirm it. This is the existing behaviour.
2. **Validation** (`/creation/data-templates`): a reusable **data template** holds the fields a document collects and their rules. It can be made from scratch, or copied from a flow template's fields and rules and then changed.
3. **Map fields** (`/creation/mapping`): each PDF template chooses a data template, then maps its fields onto the data fields in a two-column matcher.
4. **Fix documents** (`/creation/documents`): a document goes through guided steps. **It can only be saved as final (Validated) or downloaded once its data passes the validation**, meaning the data template's rules plus the PDF's own extra rules.

This contract builds on `field-validation-contract.md` (ws33). The BE-A, BE-B, FE and QA streams build against it, and shapes change only through the Supervisor.

Wave 0 put these on the branch:
- migrations V330–V332;
- the entities `DocDataTemplate`, `DocDataField` and `DocDataFieldRule`, plus `DocBlueprint.dataTemplateId`/`dataMappingJson` and `DocBlueprintSource.managed`;
- the repositories `DocDataTemplateJpaRepository`, `DocDataFieldJpaRepository` and `DocDataFieldRuleJpaRepository`;
- the audit actions `DOC_DATA_TEMPLATE_SAVED`, `DOC_DATA_TEMPLATE_DELETED`, `DOC_DATA_TEMPLATE_COPIED` and `DOC_BLUEPRINT_DATA_MAPPING_SAVED`, also listed in `audit-events.md`;
- the RLS inventory and the RANGES ws34 row.

## 1. Database (ws34, V330–V339)

### V330: `DocDataTemplate`

| Column | Notes |
|---|---|
| `DocDataTemplateId` | int, identity |
| `BankId`, `OwnerUserId` | |
| `Name` | 1–200 characters |
| `Description` | at most 1000 characters |
| `SourceTemplateId` | the flow template it was copied from, or null |
| `RulesRevision` | goes up by 1 on every save of fields or rules |
| `CreatedAt`, `UpdatedAt` | |

### V330: `DocDataField`

| Column | Notes |
|---|---|
| `DocDataFieldId`, `BankId`, `DataTemplateId` | cascade on delete |
| `FieldName` | `[a-z0-9_]{1,64}`, unique per template |
| `Label` | 1–200 characters |
| `Kind` | `TEXT`, `MULTILINE`, `DATE`, `NUMBER`, `CHOICE`, `CHECKBOX` or `IMAGE` |
| `PiiKind` | the flow `PiiKind` name, or null |
| `PropsJson` | `FieldProps`, the same rules as a region's (`field-validation-contract.md` §2) |
| `SourceFieldName` | the flow field it was copied from |
| `SortOrder` | |

### V330: `DocDataFieldRule`

The `DocFieldRule` columns, keyed by (`DataTemplateId`, `FieldName`). `Origin` is `MANUAL` or `IMPORTED`.

### V331 and V332
- **V331:** `DocBlueprint.DataTemplateId` (FK, nullable) and `DocBlueprint.DataMappingJson` (region key → data field name, as JSON).
- **V332:** `DocBlueprintSource.Managed` (boolean, default false).

## 2. Data templates API (BE-A)

All routes are under `/api/doccreation/data-templates`. Reading needs `DOC_READ` and changing needs `DOC_WRITE`. Data templates are **owner-scoped** like blueprints, so another user, or another bank, gets a 404.

| Route | Body / reply |
|---|---|
| `GET /` | `[DataTemplateSummaryDto]` |
| `POST /` | `{name, description?}` → 201 with `DataTemplateDto` |
| `GET /{id}` | `DataTemplateDto` |
| `PUT /{id}` | `{name, description?}` → `DataTemplateDto` |
| `DELETE /{id}` | 204. **409** while a PDF template uses it, with a message naming how many. |
| `PUT /{id}/fields` | `{baseRevision, fields:[FieldInput]}` replaces the field set and returns `DataTemplateDto`. Rules on removed fields are deleted. |
| `PUT /{id}/rules` | `{baseRevision, rules:[RuleInput]}` replaces the rule set and returns `DataTemplateDto` |
| `POST /{id}/rules/test` | `{values:{fieldName: value}, rules?:[RuleInput]}` → `ValidationResultDto`. The keys are field names. |
| `GET /copy-preview?templateId=` | `CopyPreviewDto`. Needs `TEMPLATE_READ` and `VALIDATION_READ`, and the flow template must pass `TemplateOwnership.requireReadable`. |
| `POST /from-flow-template` | `{templateId, name, fields:[flowFieldName]}` → 201 with `DataTemplateDto`. Same checks as copy-preview. |
| `POST /from-flow-templates` | **(follow-up)** `{name, sources:[{templateId, fields:[flowFieldName]}]}` → 201 with `DataTemplateDto`. Builds one template from the chosen fields of **several** flow templates. Each template is checked as for copy-preview. |
| `POST /{id}/add-from-flow-templates` | **(follow-up)** `{baseRevision, sources:[{templateId, fields}]}` → `DataTemplateDto`. Adds the chosen flow fields and their rules to an existing template. A stale revision returns 409. |

### Shapes

```json
DataTemplateSummaryDto = { "id", "name", "description", "fieldCount", "ruleCount",
  "sourceTemplateId", "sourceTemplateName", "usedBy", "updatedAt" }

DataTemplateDto = { "id", "name", "description", "revision",
  "sourceTemplateId", "sourceTemplateName",
  "fields": [{ "name", "label", "kind", "piiKind", "props": FieldProps|null, "sourceFieldName", "sortOrder" }],
  "rules":  [{ "id", "fieldName", "ruleType", "config", "message", "severity", "origin",
               "sourceTemplateId", "sourceTemplateName", "sourceFieldName", "sortOrder" }],
  "usedBy": [{ "blueprintId", "name" }],
  "ruleTypes": [ ...as RulesDto.ruleTypes in ws33 ] }

FieldInput = { "name", "label", "kind", "piiKind"?, "props"?, "sourceFieldName"? }
RuleInput  = { "fieldName", "ruleType", "config", "message"?, "severity"?, "origin"?,
               "sourceTemplateId"?, "sourceFieldName"? }

CopyPreviewDto = { "templateId", "templateName",
  "fields": [{ "name", "dataType", "kind", "piiKind",
               "rules": [{ "ruleType", "config", "message" }],
               "skipped": [{ "flowRuleType", "reason" }] }] }
```

### Field checks (400)
- Field names must be unique and match `[a-z0-9_]{1,64}`.
- The label is 1–200 characters, and the kind must be one of those listed.
- `props` are checked as for regions (`OverlayMapper` property rules, with CHOICE needing options). Reuse the same checker.
- At most 500 fields.

### Rule checks (400)
- The same checks as the ws33 rule save (`FieldRuleChecks`), with **field names** instead of region keys and the data field kinds instead of region kinds.
- `REQUIRED_IF` and `COMPARE` name another field of the same data template.

### Revision and side effects
- A stale `baseRevision` returns **409**. Each save raises `RulesRevision` by 1.
- **Every save of fields or rules also raises `DocBlueprint.RulesRevision` of each PDF template that uses this data template**, by 1. Documents validated earlier then see that the rules changed (§5).
- Audit: `DOC_DATA_TEMPLATE_SAVED` (`{change: CREATED|DETAILS|FIELDS|RULES, fields, rules, revision}`) and `DOC_DATA_TEMPLATE_DELETED`.

### Building from several flow templates (follow-up)
**Merging the fields**
- `from-flow-templates` and `add-from-flow-templates` copy fields and rules exactly as the single copy below does.
- **Name clashes:** a field name that already exists (from an earlier source, or in the template you add to) is given the suffix `_` + the slug of its flow template's name. If that name is also taken, a number is added: `_2`, `_3` and so on.
- **Labels** stay as the flow field names.
- **Each field's origin:** `DocDataField.SourceTemplateId` (V333) records the flow template the field came from.

**The main flow template**
- `DocDataTemplate.SourceTemplateId` is set to the flow template that gave the most fields. On a tie, the first in the request wins.
- When adding to an existing template, it is set only if it was empty.
- The managed data source (§3) is built from the main template.

**Other rules**
- **Empty requests:** an empty `sources` list, or a source with an empty `fields` list, is a 400. Leaving out `fields` copies all of that template's fields.
- **Audit:** `DOC_DATA_TEMPLATE_COPIED` with `{templates:[ids], fields, rules, skipped, added: true|false}`.
- **`DataTemplateDto.fields[]`** gains `sourceTemplateId` and `sourceTemplateName`.

### Copying from a flow template
For each chosen flow field:

**The field:**
- `name` is the flow field name, slugged to `[a-z0-9_]` and made unique.
- `label` is the flow field name.
- `kind` comes from the data type: Number → NUMBER, Date → DATE, otherwise TEXT.
- `piiKind` is kept, and `sourceFieldName` is the flow field name.

**Its rules** go through `DocFieldRuleUseCase.convert` (make it reusable, e.g. a package-level static in a shared `FlowRuleConverter`). They become `Origin=IMPORTED` with the source template and field. Rules that can't be converted are counted as skipped.

**After the copy:**
- `SourceTemplateId` is set.
- Everything stays editable.
- Audit `DOC_DATA_TEMPLATE_COPIED` (`{templateId, fields, rules, skipped}`).

### Implementation notes (BE-A)
These pin down what the table above leaves open. The shapes are unchanged.
- **Code:** `DataTemplateController` and `DataTemplateUseCase`. The Java records are `DataFieldInput` and `DataRuleInput` (the JSON `FieldInput` and `RuleInput`), because ws33 already has a `RuleInput` keyed by `regionKey`. The conversion lives in `FlowRuleConverter`, which the ws33 import uses too.
- **`DataTemplateSummaryDto.usedBy`** is a count. `DataTemplateDto.usedBy` lists the PDF templates.
- **Revision:** creating, copying or renaming doesn't change `revision`. A new or copied data template starts at 0. A missing `baseRevision` skips the check, as in ws33.
- **Field checks:**
  - The label and the kind are required.
  - `piiKind` must be a `PiiKind` name.
  - The props errors are the region ones (`Field "<name>": …`).
  - `fields` must be present: send `[]` to remove every field.
- **Rule checks:**
  - A rule that doesn't fit its field's kind is refused (400), for example EMAIL on a NUMBER field. ws33 keeps it as a setup warning instead.
  - The origin is `MANUAL` or `IMPORTED`. `REGION_FLAG` is refused.
- **Saving fields:**
  - Removing a field deletes its rules, and also the `REQUIRED_IF`/`COMPARE` rules that name it.
  - Changing a kept field's kind so that one of its rules no longer fits is refused (400). Remove the rule first.
- **`rules/test`:** an IMAGE field counts as holding a photo when its sample value is not blank.
- **Copying:**
  - `copy-preview` needs `DOC_READ`, and `from-flow-template` needs `DOC_WRITE`. Both also need `TEMPLATE_READ` and `VALIDATION_READ` (403).
  - In `from-flow-template`, `fields` omitted copies every field. An unknown flow field name is a 400.
  - Fields keep the flow template's order.
  - A converted rule that doesn't fit the field's kind is skipped with the reason "<TYPE> does not apply to a <kind> field".
  - Names are `OverlayMapper.slug` of the flow name ("field" when empty), made unique with `_2`, `_3` and so on.

## 3. PDF template ↔ data template (BE-B)

All routes are under `/api/doccreation/blueprints/{id}`. The template must be owned by the caller (`DocCreationOwnership.requireTemplate`); reading needs `DOC_READ` and changing needs `DOC_WRITE`. The chosen data template must be readable by the caller (owner-scoped, same bank), otherwise 404.

| Route | Body / reply |
|---|---|
| `PUT /data-template` | `{dataTemplateId \| null}` sets or clears the choice and returns `DataMappingDto`. Choosing a template pre-fills the mapping from the suggestions: same name, or a score ≥ 0.7, one-to-one, kind-compatible. Clearing it also clears the mapping. |
| `GET /data-mapping` | `DataMappingDto` |
| `PUT /data-mapping` | `{mapping: {regionKey: fieldName}}` → `DataMappingDto`. A 400 for an unknown region key or field name, or an incompatible kind. A field may be mapped to several regions. Raises `DocBlueprint.RulesRevision` by 1. |

Both changing routes audit `DOC_BLUEPRINT_DATA_MAPPING_SAVED`.

```json
DataMappingDto = { "dataTemplateId", "dataTemplateName", "revision",
  "mappedCount", "mappableCount",
  "rows": [{ "regionKey", "label", "kind", "page", "fx", "fy", "fw", "fh",
             "mappedField": "full_name" | null,
             "suggestion": { "field", "score", "sameName" } | null }],
  "fields": [{ "name", "label", "kind" }] }
```

**Compatible kinds:**

| Region kind | Data field kinds it accepts |
|---|---|
| TEXT | TEXT, CHOICE, NUMBER, DATE |
| MULTILINE | TEXT, MULTILINE |
| DATE | DATE, TEXT |
| NUMBER | NUMBER, TEXT |
| CHOICE | CHOICE, TEXT |
| CHECKBOX | CHECKBOX |
| IMAGE, SIGNATURE | IMAGE |

`mappableCount` counts the regions that have at least one compatible field.

**Suggestions:** `BlueprintMappingSuggester`, fed with the data fields: name = field name, aliases = [label], data type from the kind.

**`GET /api/doccreation/blueprints` entries** gain `dataTemplateId`, `dataTemplateName`, `mappedCount` and `mappableCount`, for the step 3 list.

**Managed data source:**
- **When it is kept:** while the data template has a `SourceTemplateId`, every save of the choice or the mapping keeps one **managed PRIMARY `DocBlueprintSource`** for that flow template.
- **Its mapping:** region → the flow field (`sourceFieldName`) of the data field mapped to the region, for fields that have one.
- **When it is created or updated:**
  - it is created when there's no PRIMARY yet;
  - it is updated when the existing PRIMARY is managed;
  - **a PRIMARY the user made by hand is never touched.**
- **When it is deleted:** when the data template changes or loses its source.

## 4. Effective rules (BE-B)

`EffectiveRules.of(DocBlueprint)` returns `List<DocFieldRule>`:
- **The data template's rules, re-keyed:** for every region mapped to a field, a transient `DocFieldRule` copy of that field's rules.
  - It has `regionKey` set to the region, `origin = "DATA_TEMPLATE"`, and `sourceTemplateId`/`sourceFieldName` from the data rule.
  - Its id is the negative of the data rule's id, so the client never confuses it with a PDF rule.
  - `REQUIRED_IF`/`COMPARE` `Field` values are re-keyed to the region mapped to that field. If there are several, the first in sort order. If there is none, the rule is a setup warning.
- **The PDF's own extra rules** (`DocFieldRule`, unchanged).

It replaces the rule lookup in:
- `DocInstanceUseCase.validate`;
- `DocInstanceUseCase.toDto` (required keys and `fieldChecks`);
- `DocFieldRuleUseCase.test` and `requiredKeys`.

**The ws33 rules API** (`GET /blueprints/{id}/rules`) keeps returning **only the PDF's own rules**, plus a new `inherited: [rule views with origin DATA_TEMPLATE and the data template's name]` list. The template page shows the inherited rules read-only.

**`FieldChecksBuilder`** shows `origin DATA_TEMPLATE` rules with `sourceTemplateName` = the data template's name.

## 5. Validated before Save or Download (BE-B)

This extends ws33 §4.
- **`generate` and `download`** also return 409 `NOT_VALIDATED` when the document is VALIDATED or GENERATED but `ValidatedRulesRevision != DocBlueprint.RulesRevision`, meaning the rules or the mapping changed since validation. The message is "The rules changed since this document was validated; save it again to re-check."
  - SUBMITTED and RELEASED documents are not affected.
  - Pre-ws33 GENERATED documents (null `ValidatedRulesRevision`) are not affected either.
- **`InstanceSummaryDto`** (`GET /instances`) gains `validationState` (`NOT_CHECKED`, `ERRORS`, `VALIDATED` or `RULES_CHANGED`) and `errorCount`.
  - `VALIDATED` covers VALIDATED, GENERATED, SUBMITTED and RELEASED documents that passed.
  - `RULES_CHANGED` is VALIDATED or GENERATED with a stale revision.
  - `ERRORS` is a DRAFT whose last check failed, and `NOT_CHECKED` is a DRAFT never checked or changed since.
- **The fill preview** (`GET /instances/{id}/fill-preview`): each `fields[]` entry gains `wouldFail: [{code, message}]`. These are the checks that value would fail, evaluated with the effective rules on the document's values with that value applied. Messages never contain values.

## 6. Frontend (FE)

**Follow-up: one Validation page.** `/creation/data-templates` (sidebar step 2) is now one page built with the **same controls as Document Flow → Set Field Rules**:
- a Template select, with New template / **Build from Document Flow templates** / Rename / Delete;
- a Fields card;
- an "Add/Update field rules" card with Field, Required or Optional, and Format rule type (with `RegexBuilder` and Min/Max inline, plus the extra Document Creation types), a message, and Warning only;
- a rules table: Field / Required / Rule / Settings / Source / Update / Delete;
- a "Test a sample row" card.

`/creation/data-templates/:id` redirects to `?template=:id`. These templates are only shown in Document Creation.

The details below are the original #64 design.


**Sidebar "Document Creation"** has numbered steps, like Document Flow:
1. "Upload document" (`/creation/blueprints`)
2. "Validation" (`/creation/data-templates`)
3. "Map fields" (`/creation/mapping`)
4. "Fix documents" (`/creation/documents`)

**Validation pages:**
- **List:** Name / Fields / Rules / Based on / Used by, with **New data template** and **Copy from a flow template**.
- **Copy dialog:** pick a flow template, then a checklist of its fields (all ticked) with their rules, then Copy.
- **Editor:** details; a fields table (name, label, kind including Dropdown, PII, a Required toggle, rule chips, Edit rules, Delete, "+ Add field"); a rules drawer using `RuleDialog` and `ruleText`, made generic; Test with sample values.

**Map fields page:** the list of PDF templates with their data template and "x of y mapped". Each row opens the template's step 3.

**PDF template page:** a `Stepper` with "Upload" ✓, "Validation", "Map fields" and "Documents".
- **Validation:** choose or create a data template inline (the copy dialog included), see its summary, and edit the PDF's extra rules (the existing Field rules tab, now with "Inherited from {data template}" read-only rules on top).
- **Map fields:** the two-column matcher.
  - "PDF field" / "Data field" dropdown / match badge.
  - A "same name" (green) or "suggested 0.85" (blue) badge, amber for unmatched rows.
  - "x of y mapped" and **Accept all suggestions**.
  - A page preview that highlights the focused row's box.
  - **Save mapping**.
  - The existing Data sources tab sits below it under "Data from the flow (advanced)".
- **Documents:** "Create a document" and this template's documents.

**Builder:** a `Stepper` under the header, with the step kept in `?step=`:
1. **Choose data:** search inline and preview "document field ← flow field: value", with pre-ticked boxes and **⚠ marks on values in `wouldFail`**. Apply. The step can be skipped.
2. **Fix fields:** the canvas, the Checks panel and the field list.
3. **Save & validate:** Save shows the result, or the errors with Go to field and a way back to step 2.
4. **Download:** Generate / **Download PDF** / Submit.

Step 4 is locked until the document is validated with the current rules, with the reason shown. An edit re-locks it.

**My documents:**
- a **Validation** column: "Not checked", "N errors", "Validated", "Rules changed — save again";
- a **Download** action only for validated documents (otherwise disabled with the reason);
- **Open to fix** for the rest.

**Shared:** `components/Stepper.jsx` is extracted from `IngestionJobPage`'s `WizardStepIndicator`, which then uses it with no change in behaviour.

## 7. Ownership

| Stream | Owns |
|---|---|
| **BE-A** | data templates: the use case, controller, DTOs, the checks, copy-from-flow, the shared `FlowRuleConverter` (moved out of `DocFieldRuleUseCase`), the revision bumps of the PDFs that use a template, audit, tests, contract notes |
| **BE-B** | the PDF ↔ data template routes, `EffectiveRules` and its wiring, the managed source, the list counts, the stricter gate, `validationState`/`errorCount`, `wouldFail`, the `GET /rules` `inherited` list, an RLS PostgreSQL IT for the three tables, `openapi-routes.txt` (both streams' routes), README and CHANGELOG |
| **FE** | `frontend/src/**` |
| **QA** | `e2e/**` (wave 2) |
