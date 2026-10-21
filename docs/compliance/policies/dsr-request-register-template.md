# Data-subject request register — template

Control **PRIV-013** (full data-subject rights functionality). Gate item C12. Use with the DSR
runbook in `docs/security/retention-and-dsr.md`.

One register per bank. The bank is the controller of its customers' data and decides on each
request; MoDocument staff (a bank Admin with `USER_ADMIN`, or MoDocument support acting on the
bank's written instruction) carry out the mechanical steps. **Never write the subject's
identifier, name or any of their values in the register.** Use the subject reference that the
audit trail uses: the first 16 hexadecimal characters of the SHA-256 of the lower-cased identifier, as written
in the `DSR_SEARCH` / `DSR_EXPORTED` / `DSR_ERASED` audit rows
(`src/main/java/com/modocument/application/usecase/DataSubjectUseCase.java`).

Where the register is kept (ticketing tool or controlled spreadsheet) is
`TO CONFIRM (organisational)`.

## Register columns

| Column | What to record |
|---|---|
| Request ID | Bank ticket number |
| Received (date) | Date the bank received the request |
| Statutory due date | Received date + the statutory period. The workbook names "without undue delay, within one month" (Mauritius Data Protection Act 2017); the confirmed period is `TO CONFIRM (organisational)` (pack 09) |
| Right exercised | Access · Portability · Rectification · Erasure · Restriction · Objection |
| Identity verified by (role, date) | The bank's own check; MoDocument does not verify identity |
| Bank instruction reference | The written instruction from the bank's DPO or delegate |
| Subject reference | SHA-256 prefix from the audit row, never the identifier |
| Search done (date, audit event id) | `POST /api/admin/dsr/search`, row `DSR_SEARCH`; per-store hit counts |
| Export done (date, audit event id) | `POST /api/admin/dsr/export`, row `DSR_EXPORTED`; how the ZIP was delivered to the bank |
| Erasure done (date, audit event id) | `POST /api/admin/dsr/erase`, row `DSR_ERASED`; per-store counts |
| Closure check (date, result) | Repeat the search; it must return 0 matches |
| Manual steps (Y/N, what) | Items not reached automatically — see "Erasure propagation checklist" in the runbook (document titles, document photos, outbound copies already delivered to bank systems) |
| Backup note | "Live data erased; backups expire within 90 days; any restore must be re-erased before use" |
| Response sent (date, by) | Bank's response to the subject |
| Within deadline (Y/N) | Response date ≤ due date |
| Notes | No personal data |

## Example row (synthetic)

| Request ID | Received | Due | Right | Verified by | Instruction | Subject ref | Search | Export | Erasure | Closure | Manual | Backup | Response | In time | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| DSR-2026-001 | 2026-10-19 | `TO CONFIRM` | Erasure | Bank DPO, 2026-10-19 | DPO e-mail ref. 001 | `3f9a…` (16 hex) | 2026-10-19, audit #… | — | 2026-10-19, audit #… | 2026-10-19, 0 matches | Y: one document title edited | standard note | 2026-10-20 | Y | synthetic example |

## Review

The bank's DPO reviews the register monthly and the runbook once a year (workbook frequency:
event-based per request, annual runbook test).
