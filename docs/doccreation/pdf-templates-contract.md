# Exact PDF templates: contracts

This is the **Wave 0 contract** shared by the three parallel workstreams:

- **BE-1, engine:** `doccreation/pdftemplate/**`, plus the corpus.
- **BE-2, application:** DB, use cases, API, legacy purge, and removal of the Word path.
- **FE, frontend:** the editor, builder and list pages.

Change a shape here only through the Supervisor.

## 1. Principles

- **The template is the original PDF.**
  - Blank flow: the template PDF is the same bytes as the original.
  - With-data flow: the template PDF is the original with the data removed.
  - Neither flow ever converts to Word.
- **Exact means:** every page is rendered at 150 DPI.
  - Outside the regions and edit boxes, the output is pixel-identical (0 differing pixels) to its base.
  - Inside a REMOVE box, nothing of the original data remains, only background.
- **Code always works. AI only adds.** Every AI step has a code path that delivers the feature on its own, and the AI output is validated against real geometry and text runs.
- **Output is PDF only.**

## 2. Coordinates

All boxes use `PdfBox`:

- `page` is 0-based.
- `x`, `y`, `w`, `h` are in PDF points, in the **unrotated cropBox user space**, with the origin at the cropBox's bottom-left corner.
  - `x` and `y` are the lower-left corner.
- The frontend works with **top-left page fractions** for drawing. These are converted by the API layer only, using `PageInfo`:
  - `fx = x / width`
  - `fy = 1 - (y + h) / height`
  - `fw = w / width`
  - `fh = h / height`
- `PageInfo` carries `width`, `height` (cropBox, in points) and `rotation` (0, 90, 180 or 270).
  - The frontend shows page images that the backend has already rotated for display.
  - The API layer also maps fractions through the rotation, so the frontend never deals with rotation.

## 3. Engine API (BE-1 implements; BE-2 consumes)

Package `com.modocument.doccreation.pdftemplate`.

```java
public record PdfBox(int page, float x, float y, float w, float h) {}
public record PageInfo(int index, float width, float height, int rotation) {}

public enum UploadFlow { BLANK, WITH_DATA, AUTO }          // AUTO: engine decides (looksFilled)
public enum RegionKind { TEXT, MULTILINE, DATE, NUMBER, CHECKBOX, IMAGE, SIGNATURE, CHOICE }  // ws33: CHOICE = dropdown
public enum RegionSource { ACROFORM, DETECTED, DATA_REMOVED, AI, MANUAL }
public enum Align { LEFT, CENTER, RIGHT }
public enum EditKind { REMOVE, REPLACE_TEXT, ADD_TEXT, ADD_IMAGE }
public enum EditSource { DATA_REMOVAL, USER }

public record TemplateRegion(
    String key,            // stable field key, [a-z0-9_]{1,64}, unique per version
    String label,          // human label, ≤200
    String group,          // section/group name, ≤120, nullable
    RegionKind kind,
    PdfBox box,
    String fontName,       // PDF base font name or a bundled family: "LiberationSans" | "LiberationSerif" | "LiberationMono" (+ "-Bold"/"-Italic"/"-BoldItalic")
    float fontSize,        // pt; 0 = auto
    String color,          // "#RRGGBB"
    Align align,
    int maxLines,          // 1 for TEXT/DATE/NUMBER; ≥1 for MULTILINE; 0 for CHECKBOX/IMAGE/SIGNATURE
    String acroFieldName,  // nullable
    boolean required,
    int sortOrder,         // reading order
    RegionSource source,
    double confidence,     // 0..1
    String sampleValue     // WITH_DATA: the removed value (shown in review only, never stored past review), else null
) {}

public record TemplateEdit(
    String id,             // uuid
    EditKind kind,
    PdfBox box,
    String text,           // REPLACE_TEXT / ADD_TEXT
    String fontName, Float fontSize, String color,
    String imageKey,       // ADD_IMAGE: key into the images map passed to applyEdits
    EditSource source,
    double confidence,
    String removedText,    // REMOVE: the text that was removed (review only)
    String regionKey       // DATA_REMOVAL: key of the DATA_REMOVED/AI region proposed for the same value, else null
) {}                       // (an 11-argument constructor without regionKey remains for user edits)

public record AnalysisOptions(boolean aiAllowed, String locale) {}
public record AnalysisResult(
    int pageCount, List<PageInfo> pages,
    UploadFlow flowUsed,             // BLANK or WITH_DATA (AUTO resolved)
    boolean hasAcroForm, boolean looksFilled,
    List<TemplateRegion> regions,    // proposed fill-in regions
    List<TemplateEdit> removals,     // WITH_DATA: proposed REMOVE edits (+ AcroForm clears are implicit)
    List<String> warnings,           // human-readable
    String detectedBy) {}            // "CODE" | "CODE+AI" (only when the AI changed a label, group, kind or the data classification)

public record FillOptions(float minFontSize, boolean continuationPages, boolean verify) {}
public record ExactnessReport(boolean exact, long differingPixels, List<Integer> pagesWithDiffs, int dpi) {}
public record FillResult(byte[] pdf, List<String> continuedKeys, int addedPages, ExactnessReport report) {}

public interface PdfTemplateEngine {
    /** Reads a PDF. Throws PdfTemplateException(code) for: ENCRYPTED, TOO_MANY_PAGES (>500), NOT_A_PDF, UNREADABLE. */
    AnalysisResult analyze(byte[] pdf, UploadFlow flow, AnalysisOptions options);
    /** Applies REMOVE/REPLACE_TEXT/ADD_TEXT/ADD_IMAGE edits (and clears AcroForm values when clearAcroForm) -> template PDF. Deterministic. */
    byte[] applyEdits(byte[] originalPdf, List<TemplateEdit> edits, Map<String, byte[]> images, boolean clearAcroForm);
    /** Writes values into regions: text (auto-fit to minFontSize, then "(continued on page N)" + continuation page), checkbox ("true"/"x"), images (fit, keep aspect); fills+flattens AcroForm fields. */
    FillResult fill(byte[] templatePdf, List<TemplateRegion> regions, Map<String, String> values, Map<String, byte[]> images, FillOptions options);
    List<PageInfo> pages(byte[] pdf);
    /** PNG of one page at dpi (≤200), rotated for display. */
    byte[] renderPage(byte[] pdf, int page, int dpi);
    /** Pixel diff outside allowedAreas (boxes in PDF points). */
    ExactnessReport verify(byte[] base, byte[] candidate, List<PdfBox> allowedAreas, int dpi);
}
```

- `PdfTemplateException extends RuntimeException` and carries `code()`.
- The engine is a Spring `@Component`: `DefaultPdfTemplateEngine`.
- AI calls go through `AiGateway` with the new `AiPurpose.DOC_PDF_REGIONS` and `AiPurpose.DOC_PDF_DATA`. They use the tokenizer mask and the LLM quota, and run **only when** `options.aiAllowed()`.
- The engine has **no** repository or HTTP dependencies.

## 4. Database (BE-2): range ws31, V300–V309

| Version | Contents |
|---|---|
| **V300** | `DocBlueprint`: `Kind varchar(16) NOT NULL DEFAULT 'DOCX_LEGACY'` (new rows are `PDF_OVERLAY`), `OriginalFileKey varchar(512)`, `PageCount int`, `UploadFlow varchar(16)`, `PagesJson text` (the `PageInfo` list). `DocBlueprintVersion`: `TemplatePdfKey varchar(512)`. |
| **V301** | `DocTemplateRegion`: Id, BankId, BlueprintId, Version, `RegionKey`, Label, GroupName, Kind, Page, X, Y, W, H (`double precision`), FontName, FontSize, Color, Align, MaxLines, AcroFieldName, Required, SortOrder, Source, Confidence. Unique key (BlueprintId, Version, RegionKey). Indexed on (BlueprintId, Version). |
| **V302** | `DocTemplateEdit`: Id, BankId, BlueprintId, Version, EditId, Kind, Page, X, Y, W, H, Text, FontName, FontSize, Color, ImageKey, Source. `DocInstance`: `ValuesJson text` (map key → value) and `GeneratedPdfKey`. `DocInstanceImage`: Id, BankId, InstanceId, RegionKey (nullable means the tray), AssetId, SortOrder, OriginalName. |

- All tables carry `BankId` and get RLS through `afterMigrate`.
- Register every table in `RlsPolicyCoveragePgIT`.
- Add the `RANGES.md` row for ws31. Also correct the ws29 row so it mentions V291/V292.

## 5. HTTP API (BE-2 implements; FE consumes)

All routes are under `/api/doccreation` and use the existing `DOC_READ` / `DOC_WRITE` permissions.

### Blueprints

- **`POST /blueprints`**: multipart `file` (PDF only; magic-byte check), `name?`, `companyId?`, `flow=BLANK|WITH_DATA|AUTO` (default AUTO).
  - Returns `201 BlueprintDto`, with status `DRAFT`, version 1 proposed and not yet confirmed.
  - Errors: `400 NOT_A_PDF`, `400 ENCRYPTED`, `400 TOO_MANY_PAGES`, `413` (too large).
- **`GET /blueprints`** returns `BlueprintSummaryDto[]`: `{id, name, status:"DRAFT"|"READY", currentVersion, pageCount, flow, documentCount, companyId, createdDate}`.
- **`GET /blueprints/{id}?version=N`** returns `BlueprintDto`:

```json
{ "id":1, "name":"…", "status":"DRAFT|READY", "flow":"BLANK|WITH_DATA", "version":1, "currentVersion":1,
  "pages":[{"index":0,"width":595.3,"height":841.9,"rotation":0}],
  "regions":[{ "key":"full_name","label":"Full name","group":"Applicant","kind":"TEXT",
               "page":0,"fx":0.1,"fy":0.2,"fw":0.3,"fh":0.02,
               "fontName":"Helvetica","fontSize":10,"color":"#000000","align":"LEFT","maxLines":1,
               "acroFieldName":null,"required":false,"sortOrder":0,"source":"DETECTED","confidence":0.9,
               "sampleValue":"John Doe", "suggested":false,
               "props":{"placeholder":"e.g. Jane Doe","maxLength":40} }],
  "edits":[{ "id":"…","kind":"REMOVE","page":0,"fx":…,"fy":…,"fw":…,"fh":…,"text":null,"fontName":null,"fontSize":null,
             "color":null,"source":"DATA_REMOVAL","confidence":0.8,"removedText":"John Doe",
             "applied":true, "regionKey":"full_name" }],
  "warnings":["…"], "detectedBy":"CODE|CODE+AI", "documentCount":0,
  "fonts":["LiberationSans","LiberationSans-Bold","…"] }
```

- **ws33 (field-validation-contract.md §2/§3).** Each region carries `props` (placeholder, help text, default
  value, CHOICE options, date/number output formats, max length; null when none), also accepted by
  `PUT /overlay` and stored as `DocTemplateRegion.FieldPropsJson`. Kind `CHOICE` is a one-line dropdown whose
  options are `props.options` (required); AcroForm combo and list boxes are proposed as CHOICE with their options.
  `required` is derived from the template's REQUIRED field rules; the overlay save keeps those rules in step with
  it. `fonts` lists the bundled font names a region may use.

- `sampleValue` and `removedText` are only returned while the blueprint is `DRAFT`. They are cleared once it is confirmed.
- **Low-confidence guesses are suggested, not applied (BUG-PDF-01).** On upload (WITH_DATA), only proposed removals with `confidence ≥ 0.6` (`RemovalPolicy.APPLY_THRESHOLD`) are applied to the draft's template PDF and stored as edits.
  - A removal below the threshold is a **suggestion**: returned in `edits` with `"applied":false`. Every other edit, and every edit of a confirmed template, has `"applied":true`.
  - Its linked `DATA_REMOVED` region is returned in `regions` with `"suggested":true`; all other regions have `"suggested":false`. It is not part of the template.
  - Suggestions and their regions, with their `removedText` / `sampleValue`, live only in the draft review data (`draft.json`), like the other review data. They never reach a database row unless the client sends them back as applied or kept.
- **Linkage:** `edit.regionKey` names the `DATA_REMOVED` region that takes a data removal's place (the field for the removed value). It is returned while `DRAFT` only (it is review data and is not stored in a row); it is null for user edits.
  - Restoring a removal is expected to drop its linked region; applying a suggestion adds its region.
- **`GET /blueprints/{id}/pages/{page}?variant=original|template&version=N&dpi=110`** returns `image/png`.
  - `template` renders that version's template PDF.
  - `original` renders the uploaded PDF.
- **`PUT /blueprints/{id}/overlay`** with body `{baseVersion, regions:[…same shape as above, fractions…], edits:[…], note?, confirm:true}`.
  - It rebuilds the template PDF (`applyEdits` on the original) and saves version N+1, or version 1 when confirming a draft. Status becomes `READY`.
  - Returns `200 BlueprintDto`.
  - `409` when `baseVersion` is stale; `400` for invalid geometry or duplicate keys.
  - The server applies **exactly** the applied edits and kept regions the client sends. An edit sent with `"applied":false` and a region sent with `"suggested":true` are **ignored** (not validated, not applied, no 400). So confirming a draft "as received" keeps the low-confidence guesses in the template.
  - To apply a suggestion, the client sends it with `"applied":true` (or no `applied`); to keep its field, it sends the region with `"suggested":false` (or no `suggested`). `applied` and `suggested` default to applied / kept when absent, so older clients are unaffected.
  - `regionKey` on input is ignored.
  - Confirming deletes the remaining suggestions with the rest of the review data. A `confirm:false` draft save keeps them, so a suggestion that was only dismissed in the editor shows again after reloading such a draft.
- **`POST /blueprints/{id}/edit-images`**: multipart `file` (png or jpg, for an `ADD_IMAGE` logo). Returns `{imageKey}`.
- **`GET /blueprints/{id}/versions`**: same shape as today.
- **`GET /blueprints/{id}/file?variant=original|template&version=N`** returns `application/pdf`.
- **`DELETE /blueprints/{id}`**: same as today.

### Documents (instances)

- **`POST /instances`** with `{blueprintId, title}` → `201 {id}`. A `DRAFT` blueprint gets `409`.
- **`GET /instances`** and **`GET /instances?blueprintId=`**: same summary list as today, plus `pageCount`. ws33: an optional `status=` (DRAFT, VALIDATED, GENERATED, SUBMITTED or RELEASED) keeps one status; any other value is a `400`.
- **`GET /instances/{id}`** returns `InstanceDto`:

```json
{ "id":7,"title":"…","status":"DRAFT|VALIDATED|GENERATED|SUBMITTED|RELEASED","blueprintId":1,"blueprintName":"…",
  "blueprintVersion":2,"blueprintCurrentVersion":3,
  "pages":[…PageInfo…], "regions":[…region shape, no sampleValue…],
  "values":{"full_name":"Jane"}, "images":[{"id":3,"regionKey":"photo_1"|null,"originalName":"a.jpg"}],
  "overflow":["notes"], "lastGeneratedDate":null, "pdfAvailable":false, "reviewerComment":null,
  "validation":{…ValidationResultDto…}, "props":{"full_name":{…FieldProps…}} }
```

- **Statuses (ws33, [field-validation-contract.md](field-validation-contract.md) §4):** `DRAFT → VALIDATED → GENERATED → SUBMITTED → RELEASED`. A real change of the values or placed photos (or an upgrade to a new version) sends a `VALIDATED` or `GENERATED` document back to `DRAFT`; renaming and tray photos do not. A new document starts with its fields' default values.
- **`POST /instances/{id}/validate`** (`doc:write`, owner only) checks the document against its fields and the template's rules. Without errors it becomes `VALIDATED` (a `GENERATED` one stays `GENERATED`); with errors it stays (or becomes) `DRAFT`. Returns `InstanceDto` with `validation` filled in; warnings never block. `SUBMITTED` and `RELEASED` documents get `409`. Audited `DOC_VALIDATED`.

- **`PUT /instances/{id}/values`** with `{values:{key:value,…}}` **merges** the values. An empty string clears a key; unknown keys get `400`. Returns `InstanceDto`.
- **`POST /instances/{id}/images`**: multipart `files[]`, `autoFill=true|false`.
  - Returns `{assigned:[{imageId,regionKey}], unassigned:[imageId], rejected:[{name,reason}]}`.
  - Auto-fill first matches file-name tokens against region label, key and group; it then fills the remaining IMAGE regions in reading order.
- **`PUT /instances/{id}/images/{imageId}`** with `{regionKey|null}`: drag and drop, or move back to the tray. If the target region is already filled, the two images swap.
- **`DELETE /instances/{id}/images/{imageId}`**
- **`GET /instances/{id}/images/{imageId}/content`** returns the image bytes.
- **`GET /instances/{id}/pages/{page}?dpi=110`** returns a PNG preview with the values filled in (not stored).
- **`POST /instances/{id}/generate`** returns `{pdfReady:true, addedPages, continuedKeys, exact:true, generatedDate}`.
  - When the exactness check fails, it returns `500 EXACTNESS_FAILED` and writes an audit row.
  - ws33: a document that is not validated, or whose content changed since it was validated, gets `409 NOT_VALIDATED` (`{error, code}`). A `VALIDATED` document becomes `GENERATED`; if it changed while rendering, `409` ("generate again").
- **`GET /instances/{id}/download`** returns `application/pdf`, regenerating first if the output is stale. ws33: the same `409 NOT_VALIDATED` gate, checked before the stale check.
- **Drawing (ws33):** DATE values (stored ISO) and NUMBER values are drawn with their field's `dateFormat` / `numberFormat` in previews, generate and download alike.
- **Release:** unchanged (submit, pending, release, reject, review-file), now serving the PDF.

### Removed routes

These go together with the Word path:

- `/blueprints/{id}/paragraphs`
- `/blueprints/{id}/sections`
- `/blueprints/{id}/resection`
- `/blueprints/{bid}/sections/{sid}/keywords`
- `/instances/{id}/fields`
- every route under `/instances/{iid}/sections/…/blocks`
- `/blocks/{id}`
- the old `/instances/{id}/assets*`

### Admin

- **`POST /api/admin/doccreation/purge-legacy`** (permission `USER_ADMIN`; bank-scoped, so it purges only the caller's bank).
  - Deletes the `DOCX_LEGACY` blueprints and their instances, blocks, assets and files.
  - Returns `{blueprints, instances, files}` and audits `DOC_LEGACY_PURGED`.

## 6. Ownership

- **BE-1:**
  - `src/main/java/com/modocument/doccreation/pdftemplate/**`
  - `src/main/resources/fonts/**` (Liberation TTFs, under the OFL)
  - `AiPurpose` entries and the matching rows in `ai-controls.md`
  - `src/test/java/com/modocument/doccreation/pdftemplate/**`, which includes `PdfCorpus`
- **BE-2:**
  - migrations, entities, repositories, DTOs, controllers and use cases under `doccreation/{api,dto,entity,repo,usecase}`
  - the admin purge
  - deleting the Word path: `pdf/PdfBlueprintReflower`, `pdf/LibreOfficeDocxConverter`, `parse/**`, `generate/**`, `template/**`, and `LibreOfficePdfConverter` if it has no other users, plus their tests
  - `openapi-routes.txt` and route coverage
  - audit catalogue and docs
  - settings: `doccreation.pdfMinFontSize`
  - README document-creation section
  - CHANGELOG
- **FE:**
  - `frontend/src/api/doccreation.js`
  - `pages/BlueprintListPage`, `BlueprintDetailPage`, `DocInstancesPage`, `DocBuilderPage` and their tests
  - new components under `frontend/src/components/pdf/`

## 7. Build-time stub

Until BE-1 lands, BE-2 codes against the interface in section 3. Its tests use a hand-written `FakePdfTemplateEngine` in `src/test`. The real engine is wired in at merge.
