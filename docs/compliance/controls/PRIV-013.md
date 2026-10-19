# PRIV-013 — Full Data Subject Rights functionality

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 03 Software Engineering | Software Engineering | P1 | Partially Implemented | PRIV-013-E01 | PRIV-013-T01 | R-PRIV-013 |

## Requirement
Implement each data-subject right (access, rectification, erasure, restriction, objection,
portability) as a real workflow with deadlines. Gate C12 acceptance: a full DSR round trip executed
against a test subject.

## How MoDocument meets it today
- **Search → export → erase**, bank-scoped, `USER_ADMIN` only, rate-limited, audited
  (`DSR_SEARCH`, `DSR_EXPORTED`, `DSR_ERASED`, subject referenced by a SHA-256 prefix):
  `POST /api/admin/dsr/search|export|erase`
  (`src/main/java/com/modocument/api/controller/DataSubjectController.java`,
  `src/main/java/com/modocument/application/usecase/DataSubjectUseCase.java`) and the
  *Privacy & retention* admin screen.
- **SAR export:** a ZIP with `dsr-export.json` (machine-readable, every matching row in full, per
  store — also the portability format) and `dsr-export.txt` (human-readable).
- **Erasure propagation (verified in code, including the newer tables):** ingestion job rows,
  original uploads and export copies of matched jobs, import payloads, extraction raw and processed
  records, name-matched uploads, legacy document blocks and their assets, **PDF-template document
  values and the generated PDF** (`DocInstance.ValuesJson`, ws31), **flow records**
  (`TemplateRecord`/`TemplateRecordKey`, ws32), and the bank's AI result cache. File deletes run
  after commit and are retried until they succeed
  (`src/main/java/com/modocument/infrastructure/retention/AfterCommitFileDeleter.java`).
- **Not reached by erase (found in this review):** document titles (`DocInstance.Title`), photos
  attached to PDF-template documents (`DocInstanceImage`), the scale build's MongoDB extraction
  store, and copies already delivered to bank systems. The runbook now lists them as manual checks.
- **Retention backstop:** customer data is purged after 30 days (1–30 per bank), so anything not
  reached falls away at the purge — except document titles, which stay with the instance shell
  (`src/main/java/com/modocument/infrastructure/retention/RetentionPurger.java`).
- **Runbook, propagation checklist and rights table:** `docs/security/retention-and-dsr.md`.
  **Register template:** [dsr-request-register-template.md](../policies/dsr-request-register-template.md).
- **Rectification** uses the normal edit flows (audited by counts, without values); **restriction
  and objection** have no feature; the statutory deadline is tracked in the register and the
  period is not yet confirmed (pack 09).

## Questionnaire answer (final)
Yes, for access, portability and erasure; rectification uses the normal editing functions. On the
bank's instruction, a bank administrator can search all of MoDocument's stores for one person
(by national ID, account number, e-mail or name), export everything found as a machine-readable
JSON file plus a readable text copy, and erase it. Erasure reaches the uploaded files, extracted
rows, generated documents, the searchable record index and caches; a repeated search returning
nothing is the closure evidence, and every step is audited without recording the person's
identifier. Restriction and objection are handled by the bank stopping or deleting the processing;
there is no separate switch. Backups that still hold erased data expire within 90 days and are
re-erased if ever restored. All customer data is deleted automatically after at most 30 days.
Evidence: PRIV-013-E01.

## Evidence PRIV-013-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| DSR runbook, rights table, erasure-propagation checklist | `docs/security/retention-and-dsr.md` | repo |
| Register template | `docs/compliance/policies/dsr-request-register-template.md` | repo |
| Sample SAR export | Produced and asserted in `DataSubjectRightsGateC12Test#searchExportEraseRoundTrip` (synthetic subject: export contains `ingestionJobs`, `importDetails`, extraction, upload, `docContentBlocks`, `docValues` stores) | run |
| Round-trip runs, 2026-10-19 | `docs/compliance/evidence/A6-test-runs.md` (batch 3) | run |
| Statutory response period | pack 09 | to confirm |

## Test PRIV-013-T01
- **Procedure:** run `DataSubjectRightsGateC12Test` (seed one synthetic subject across every
  store, search, export, erase, re-search for 0; AI cache cleared for the caller's bank only; routes
  need `USER_ADMIN`), `FlowRecordsApiTest` (flow records searchable and erased),
  `RetentionPurgeGateC5Test` and `ApiRateLimitIntegrationTest` (DSR bucket); review
  `DataSubjectUseCase` against every table that holds customer values (migrations V1–V334).
- **Expected:** full round trip; every store reached.
- **Actual (2026-10-19):** DataSubjectRightsGateC12Test 3/3, FlowRecordsApiTest 11/11,
  RetentionPurgeGateC5Test 2/2 (batch 3), ApiRateLimitIntegrationTest 16/16 (batch 2) — all pass.
  Review found four places the erase does not reach (document titles, PDF-template document photos,
  MongoDB store in the scale build, agent staging buffer while a run is open).
- **Result:** Partial.
- **Tester:** Agent A6 (automated re-performance); human sign-off required. **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| Document titles are not searched or erased and survive the retention purge | Build | Include `DocInstance.Title` in search/export/erase (contains match) and clear it at the content purge | Software Engineering — Bhavish Nobeen | 2026-11-20 (proposed) | Day 60 |
| Photos of a PDF-template document whose values name the subject are not erased; photo names are not searched | Build | Delete the document's images when its values match, and search `OriginalName` | Software Engineering — Bhavish Nobeen | 2026-11-20 (proposed) | Day 60 |
| MongoDB extraction store (scale build) outside DSR and retention | Build | Add the Mongo adapter to both, or forbid `EXTRACTION_STORE=mongo` in production | Software Engineering — Bhavish Nobeen | 2027-01-15 (proposed) | Day 90 |
| No restriction/objection feature | Org | Bank procedure (stop submitting, delete jobs) documented in the bank's DSR procedure | the DPO | `TO CONFIRM (organisational)` | Day 60 |
| Statutory period not confirmed | Org | Confirm with pack 09 and put it in the register | the DPO | `TO CONFIRM (organisational)` | Day 30 |

## Risk R-PRIV-013
A single unanswered subject request becomes a complaint to the Data Protection Commissioner naming
the bank. Cause in the workbook: erasure does not propagate to indexes, caches, analytics and
backups. Indexes (flow records) and caches are now reached; the remaining propagation gaps are
listed above and in the runbook checklist. Likelihood, impact and residual are set in MO-031.

## To confirm
- `TO CONFIRM (organisational)`: statutory response period (pack 09).
- `TO CONFIRM (organisational)`: where the DSR register is kept and who reviews it.
- `TO CONFIRM (organisational)`: the bank-side procedure for restriction and objection.
