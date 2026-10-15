# Flow records in document creation: contract

This contract covers two things. First, a document can be filled with a person (a validated record) taken from the data flow. Second, a blueprint keeps saved mappings to one or more flow templates. On top of that, the builder can move and add fields and save the layout to the blueprint.

The backend (BE) and frontend (FE) streams both build against this contract. Shapes are changed only through the Supervisor.

## 1. Database (range ws32, V310–V319)

### V310: `TemplateRecord`, the searchable record index

| Column | Type | Notes |
|---|---|---|
| `TemplateRecordId` | bigint | identity, primary key |
| `BankId` | int | not null |
| `TemplateId` | int | not null |
| `IngestionJobId` | int | not null |
| `RowIndex` | int | not null, 1-based |
| `OwnerUserId` | int | not null |
| `ValuesJson` | text | not null; field name → value |
| `SearchText` | text | not null; lower-cased, accent-folded values joined with `" "` |
| `CreatedAt` | timestamp | not null |

- Unique on (`IngestionJobId`, `RowIndex`).
- Indexed on (`BankId`, `TemplateId`, `CreatedAt` desc).

Also in V310:

- **`TemplateRecordKey`**: (`TemplateRecordId`, `BankId`, `FieldName`, `KeyValue`), where `KeyValue` is the normalised value: trimmed, lower-cased, whitespace and punctuation removed.
  - Indexed on (`BankId`, `FieldName`, `KeyValue`).
  - It is written for every field of every record, so any field can serve as a join key.
- **`DocInstance.SourceRecordsJson text`**: the records used to fill the document, as `[{recordId, templateId, role}]`.

### V311: `DocBlueprintSource`

| Column | Type | Notes |
|---|---|---|
| `DocBlueprintSourceId` | bigint | primary key |
| `BankId` | int | |
| `BlueprintId` | int | not null |
| `TemplateId` | int | not null |
| `Role` | varchar(16) | `PRIMARY` or `LINKED` |
| `LinkFieldPrimary` | varchar(200) | null for PRIMARY |
| `LinkFieldThis` | varchar(200) | null for PRIMARY |
| `MappingJson` | text | not null; region key → template field name |
| `SortOrder` | int | |
| `UpdatedAt` | timestamp | |

- Unique on (`BlueprintId`, `TemplateId`).
- At most one PRIMARY per blueprint, enforced by a partial unique index.

### General rules

- Every table carries `BankId`, so row-level security comes from `afterMigrate`.
- The new tables go into the `RlsPolicyCoveragePgIT` inventory.
- `RANGES.md` gets a ws32 row.
- The migrations are expand-only.

## 2. Which records are visible

A user sees a template's records when both of these hold:

- `TemplateOwnership.requireReadable(templateId)` passes for the template (the user owns it, or it is shared with the bank).
- The record's job is the user's own, **or** the template is shared with the bank (`Visibility=BANK`).

Records of other banks are never visible, both by row-level security and by explicit filters.

Required permissions:

- Searching needs `INGESTION_READ`.
- Filling a document needs `DOC_WRITE` as well.

Values are masked per `MaskingPolicy.screenChannel(role)` using `PiiMaskingService` field kinds for that template. A field is masked when its kind is NIC, ACCOUNT_NUMBER, EMAIL, PHONE or NAME and the channel says to mask. Values written into a document carry the same masking as the user sees. The REPORT masking is applied again when the PDF is generated.

## 3. When the index is filled

- **Indexing:** when a job's step becomes VALIDATED or EXPORTED, its valid rows are indexed. Valid rows are the `MappedRowsJson` rows that have no entry in `ValidationResultJson`. Indexing runs after the commit.
- **Re-indexing:** remap, row edit and re-validate re-index the job: delete its rows, then insert again.
- **Deletion:** deleting a job deletes its records.
- **Retention:** `RetentionPurger` deletes the records and keys of jobs it purges.
- **Data subject requests:** they find and erase records through a `templateRecords` store. Erasing removes the matching record rows.
- **Backfill:** a one-off `ApplicationRunner` indexes the existing VALIDATED/EXPORTED jobs that still have row data. It runs per bank as the system user, and that call site is registered.

## 4. HTTP API

### Records

Both routes are under `/api/doccreation`.

**`GET /records?templateId=&q=&field=&page=0&size=20`**: needs `INGESTION_READ`. `size` is at most 100.

```json
{ "items":[{ "recordId":12, "templateId":3, "templateName":"Customers", "jobId":40, "rowIndex":2,
             "values":{"full_name":"Jane Doe","nic":"J1234567"}, "createdAt":"…" }],
  "page":0, "size":20, "total":1 }
```

- `q` matches `SearchText` with case-insensitive contains, on all its words.
- When `field` is given, the search matches that one field's value only.
- Values are masked as described in section 2.
- Audit: `TEMPLATE_RECORDS_SEARCHED`, with the query's SHA-256 prefix and the result count, never values.

**`GET /records/{id}`**: needs `INGESTION_READ` and returns a single record in the same shape. It writes `SENSITIVE_DATA_VIEWED` with entity `TemplateRecord`, deduplicated per hour.

### Blueprint sources and mappings

These routes need `DOC_READ` to read and `DOC_WRITE` to change. The user must also own the blueprint and be able to read the template.

**`GET /blueprints/{id}/sources`** returns the list:

```json
[{ "id":5, "templateId":3, "templateName":"Customers", "role":"PRIMARY",
   "linkFieldPrimary":null, "linkFieldThis":null,
   "mapping":{"full_name":"full_name","id_number":"nic"},
   "fields":[{"name":"full_name","dataType":"Text"}],
   "suggestions":[{"regionKey":"date_of_birth","fieldName":"dob","score":0.95,"sameName":false}] }]
```

- `suggestions` covers regions that are not mapped yet and have a candidate.

**`POST /blueprints/{id}/sources`**, body `{templateId, role, linkFieldPrimary?, linkFieldThis?}`:

- Returns `201` with the source. Its `mapping` is pre-filled by auto-mapping: every same-name match, plus suggestions scoring ≥ 0.7 assigned one-to-one.
- Returns `409` for a second PRIMARY or a duplicate template.
- Returns `400` when a LINKED source has no link fields, or the fields are unknown.

**`PUT /blueprints/{id}/sources/{sourceId}`**, body `{mapping, linkFieldPrimary?, linkFieldThis?}`:

- Saves the source and audits `DOC_BLUEPRINT_MAPPING_SAVED` with counts only.
- Returns `400` for unknown region keys, unknown template fields, or an IMAGE or SIGNATURE region. Mapping one template field to several regions is allowed.

**`DELETE /blueprints/{id}/sources/{sourceId}`**: removing the PRIMARY is refused while LINKED sources exist (`409`).

### Auto-mapping (code only)

The score is `ColumnNameSimilarity.score(region label, fieldName, fieldAliases, bankAliases)`, and also the same scored against the region key; the higher of the two counts.

- A field whose normalised name equals the region key or label scores `1.0` (`sameName:true`).
- A field and a region are compatible when:
  - the region is DATE and the field's data type is date-like, or the field type is Text;
  - the region is NUMBER and the field is numeric or Text;
  - CHECKBOX matches any field;
  - IMAGE and SIGNATURE are never mapped.
- Assignment is one-to-one, best score first, with threshold 0.7 (`mapping.confidence-threshold`).

### Filling a document

Both routes need `DOC_WRITE`.

**`GET /instances/{id}/fill-preview?recordId=`**:

```json
{ "recordId":12, "templateId":3, "mappingSource":"SAVED|AUTO",
  "linked":[{"templateId":7,"templateName":"Accounts","matchedBy":"nic","recordId":88,"alternatives":[91,93]}],
  "fields":[{ "regionKey":"full_name","label":"Full name","value":"Jane Doe","currentValue":"",
              "sourceTemplateId":3,"sourceField":"full_name","willOverwrite":false,"sameName":true }] }
```

How the preview is built:

1. If the blueprint has saved sources, the PRIMARY mapping applies to the record's template. For each LINKED source, the record matching on `KeyValue(linkFieldThis) == normalised(primaryRecord[linkFieldPrimary])` is looked up among the visible records, and the newest wins.
2. If the record's template is not a source of this blueprint, its mapping is built on the fly by auto-mapping (`mappingSource:"AUTO"`).

`fields` lists only the regions that received a value.

**`POST /instances/{id}/fill-from-record`**, body `{recordId, linkedRecordIds?:{"7":91}, fields?:["full_name",…], overwrite:"EMPTY_ONLY"|"ALL"}`:

- Applies the preview with the user's choices. `fields` defaults to all of them.
- Merges the values through the existing instance-value merge, so length caps and the SUBMITTED/RELEASED 409 still apply.
- Appends to `SourceRecordsJson` and audits `DOC_FILLED_FROM_RECORD` with region keys and record ids only.
- Returns the updated `InstanceDto`, which gains `sourceRecords:[{recordId, templateId, templateName, role}]`.

### Moving a document to the current blueprint version

**`POST /instances/{id}/upgrade`**: needs `DOC_WRITE` and returns `InstanceDto` plus `"droppedKeys":[…]`.

- The document moves to the blueprint's `currentVersion`.
- Values are kept for region keys that still exist; the others are reported in `droppedKeys` and removed. The same applies to images on removed IMAGE regions, which go back to the tray.
- Returns `409` when the document is SUBMITTED or RELEASED.

### Layout from the builder

The builder needs no new endpoint. It calls the existing `PUT /blueprints/{id}/overlay` with `baseVersion` set to the blueprint's current version, the full current region list including the changes, the current edits unchanged, `note`, and `confirm:true`. It then calls `/instances/{id}/upgrade`.

- `InstanceDto` gains `canEditLayout`, which is true when the caller owns the blueprint and has `DOC_WRITE`.
- `InstanceDto` gains `blueprintEdits` (the current edits, in the same shape as the template editor) so the builder can resend them unchanged.

## 5. Ownership

| Stream | Owns |
|---|---|
| **BE** | everything under `src/main/**` and `src/test/**`, the migrations, docs (`audit-events.md`, `retention-and-dsr.md`, README, CHANGELOG), and `openapi-routes.txt` |
| **FE** | `frontend/src/api/records.js` (new), `api/doccreation.js` additions, `pages/DocBuilderPage.jsx`, `pages/BlueprintDetailPage.jsx` (new "Data sources" tab), and new components under `frontend/src/components/records/` |
| **QA** | the E2E spec `e2e/tests/phase2/flow-records.spec.js` (`@records`), in wave 2 |
