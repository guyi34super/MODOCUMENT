# DPIA — MoDocument document ingestion and document creation

Data Protection Impact Assessment completed on the template `docs/security/dpia-template.md`
(control **PRIV-004**, gate items C2 and C9). Standing DPIAs (1) document ingestion & LLM
extraction and (3) document creation & release from the template's list, assessed together because
document creation is filled from the records ingestion produces.

**Status: draft for sign-off.** Facts come from the repository on 2026-10-19 and carry their
proof path. Ratings and the decision belong to the DPO and the risk owner and are left open; the
DPO sign-off is `TO CONFIRM (organisational)`.

## 1. Processing description

| Field | Answer |
|---|---|
| Name of processing | Ingestion of bank customer documents into validated records, and creation of PDF documents from those records |
| Date / assessor | 2026-10-19 / drafted by Agent A6 for Software Engineering (Bhavish Nobeen); DPO review `TO CONFIRM (organisational)` |
| Purpose | A bank uploads customer files (spreadsheets, PDFs, scans, Word, CSV and similar; allow-list in `src/main/java/com/modocument/infrastructure/file/SupportedDocumentTypes.java`) or sends records over the integration API. MoDocument extracts tables (code first, a language model only where code cannot), maps columns to the bank's template, validates every row and exports the result. Validated rows become searchable flow records (`TemplateRecord`, `src/main/resources/db/migration/V310__ws32_template_record_index.sql`) that fill PDF templates; a generated PDF is reviewed and released under four-eyes |
| Lawful / contractual basis | The bank is the controller and MoDocument processes on its instructions; the contract and data-processing terms are `TO CONFIRM (organisational)` |
| Data categories | Customer values inside the uploaded files and rows: national identity numbers (NIC), account and card numbers, names, e-mail addresses, phone numbers, addresses, amounts and dates. Bank staff accounts (e-mail, display name, role). Mapped to inventory rows 2, 5–8 and 13 of `docs/security/data-inventory.md` |
| Data subjects | The bank's customers named in the documents; the bank's staff who use MoDocument |
| New categories not in the inventory | Yes — the inventory was last reviewed on 2026-10-09 and lacks: flow records (`TemplateRecord`, ws32), PDF-template document values and photos (`DocInstance.ValuesJson`, `DocInstanceImage`, ws31), the AI result cache (`AiResultCache`, V286) and the integration hub stores (V250–V261). They must be added to `docs/security/data-inventory.md` (owner of that file) |

## 2. Flows and recipients

| Field | Answer |
|---|---|
| Where the data enters, moves and rests | Browser or bank system → TLS at CloudFront/ALB → nginx → app → PostgreSQL with row-level security per bank (rows, records, document values) and the file store (local volume or S3 with KMS, `infra/terraform/modules/file-store/main.tf`) for originals, exports and generated PDFs |
| Third-party recipients | (a) The language-model endpoint, through `AiGateway` only (`docs/security/ai-controls.md`); the shipped default is a local Ollama endpoint (`src/main/resources/application.yml`); the production model provider is `TO CONFIRM (organisational)`. (b) The bank's own systems when the bank configures exports, webhooks or connectors. (c) Entra ID / Keycloak for sign-in only (no customer data) |
| LLM exposure | Extraction, mapping, chat edits and PDF-template field labelling send text through `AiGateway`: one bank per prompt, identifiers replaced by placeholders before sending (`src/main/java/com/modocument/application/privacy/PiiTokenizer.java`), and the prompt is re-scanned and **refused** if a raw NIC, account number or e-mail is still present (fail-closed, `AI_PROMPT_BLOCKED_PII`). PDF-template labelling sends a masked listing (`src/main/java/com/modocument/doccreation/pdftemplate/AiRegionAdvisor.java`). Raw-content path: vision OCR sends page images that cannot be pseudonymised and is **off by default** (`docs/security/ai-controls.md`) |
| Cross-border transfer | Primary region af-south-1; backup copies to ap-southeast-2 (`infra/terraform/envs/production/providers.tf`). The model provider's region adds a transfer if it is not local — `TO CONFIRM (organisational)` |

## 3. Necessity and proportionality

- [x] **Less data:** displays and reports mask identifiers for every role; exports are masked for
  every role (`docs/security/masking-matrix.md`, `MaskingGateC2Test`). Fields are tagged with a
  PII kind ([pii-tagging-convention.md](pii-tagging-convention.md)). Tokens carry no e-mail or
  name (commit `1035294`). Document records keep record ids only (`DocInstance.SourceRecordsJson`).
  Removed sample values of an uploaded filled-in PDF are never stored past the review.
- [x] **Retention:** customer data is deleted after 30 days (bank setting 1–30) by
  `RetentionPurgeJob`, audited per bank (`docs/security/retention-and-dsr.md`). Exception: document
  titles stay with the document shell (see risk 3).
- [x] **Access:** existing permissions only (`ingestion:write`, `export:run`, document creation and
  release permissions, `USER_ADMIN` for DSR); release needs a second person
  (`docs/security/role-catalogue-and-sod.md`). No new blanket grant.
- [x] **DSR coverage (gate C12):** search, export and erase reach rows, files, records, document
  values and caches; titles and document photos are not reached yet (PRIV-013).

## 4. Risks

Likelihood and impact are rated by the DPO at sign-off (not rated in this draft).

| Risk | Likelihood (L/M/H) | Impact (L/M/H) | Existing controls | Added controls needed |
|---|---|---|---|---|
| Unauthorised access (cross-bank or insider) | DPO | DPO | Row-level security per bank, owner scoping, deny-by-default permissions, MFA for admins, audit trail (`docs/security/threat-control-matrix.md`) | Step-up MFA for bulk export (SEC-014); evidence of second-person code review (SEC-010) |
| Leak through an LLM prompt or the logs | DPO | DPO | `AiGateway` one-bank rule, tokenisation and fail-closed re-scan; vision OCR off; log redaction with a canary test (`LogRedactionCanaryTest`) | Confirm the production model provider, its region and its no-retention terms |
| Retention over-run or failure to erase | DPO | DPO | 30-day purge; DSR erase with closure search; retried file deletes | Erase and purge document titles and photos (PRIV-013) |
| Integrity: wrong data in a generated document | DPO | DPO | Template validation rules, "validated before download" gate when validation is on, four-eyes release | Validation can be switched off per template or document (V334) — record who may do so and review its use |
| Availability: loss of the only copy | DPO | DPO | Originals are the bank's; backups 90 days cross-region (IaC) | Restore test after deployment (`NOT IN REPO (live cloud)`) |

## 5. Decision

| Field | Answer |
|---|---|
| Residual risk | `TO CONFIRM (organisational)` — set by the DPO and the risk owner |
| Measures to implement before go-live of this processing | Update the data inventory with the stores in §1; confirm the model provider and transfer terms; extend DSR erase and retention to document titles and photos; restore CI so the masking, redaction and DSR tests run on every change |
| Risk owner sign-off (name, date) | Reezvi Pydiah, `TO CONFIRM (organisational)` |
| DPO sign-off (name, date) | the DPO, `TO CONFIRM (organisational)` |
| Review date | 12 months after sign-off, or earlier on a new data category, recipient, model or region |
