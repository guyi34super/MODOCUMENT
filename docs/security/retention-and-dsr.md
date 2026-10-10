# Retention schedule and data-subject rights runbook (ws14)

Scope: customer data uploaded into or produced by MoDocument (gate items
C5 and C12; controls PRIV-013, PRIV-016). Retention is enforced by the
daily `RetentionPurgeJob` (per bank, audited); data-subject requests are
handled by a bank Admin under **Administration → Privacy & retention**
(`/admin/retention`) and over the API under `/api/admin/retention` and
`/api/admin/dsr`.

**Permission decision:** both surfaces require `user:admin` (ADMIN role
only). Data-subject requests and retention are data-governance functions
of the bank administrator, exactly like user administration; no new
permission was minted so the role→permission matrix stays unchanged.

## Retention schedule

| Category | Where it lives | Period | Driver | Destruction method |
|---|---|---|---|---|
| Uploaded files (originals) | `wwwroot/uploads` + `UploadedFile` rows | **30 days** from upload | PRIV-013 / gate C5 | File deleted from disk, ownership row deleted |
| Ingestion job row data | `IngestionJob.ParsedTableJson / MappedRowsJson / ValidationResultJson` | **30 days** from last update | PRIV-013 / gate C5 | Columns nulled, `PurgedAt` stamped; job row kept as an empty report skeleton (README promise: "only empty report structures remain") |
| Export archive copies | `wwwroot/exports/u{owner}/job{id}.{ext}` | **30 days** (with their job) | PRIV-013 / gate C5 | Files deleted from disk |
| Import step payloads | `ImportDetails.RawJson` | **30 days** from the import date (`ImportHistory.ImportDate`) | PRIV-013 / gate C5 | Column nulled; row skeleton (step, ids) kept |
| Extraction raw captures | `ExtractionRawRecord.Body` | **30 days** from fetch | PRIV-013 / gate C5 | Column nulled; fetch metadata (uri, status, hash) kept |
| Extraction processed records | `ExtractionProcessedRecord.FieldsJson` | **30 days** from processing | PRIV-013 / gate C5 | Column nulled; dedup metadata kept |
| Doc-creation content | `DocContentBlock`, `DocImageAsset` (+ files), generated docx/pdf | **30 days** after the instance's last update | PRIV-013 / gate C5 | Block/asset rows and files deleted, `DocInstance.ContentPurgedAt` stamped; instance shell (title, status, dates) kept |
| Doc-creation blueprints/sections | `DocBlueprint`, `DocBlueprintSection` | n/a — **never purged** | — | Configuration (document structure), not customer data |
| Ingestion mapping config | `IngestionJob.MappingJson / SuggestedMappingJson / ExportInfoJson` | n/a — **not purged** | — | Column-name→field mappings and export metadata (file name, format, row count): configuration/metadata, no customer values |
| Audit trail | `AuditEvent` | **7 years** (SEC-033) | Evidence obligation | Append-only; **not** touched by this job |
| Security alerts (ws18) | `AlertLog` | with the audit trail (**7 years**) | Detection evidence (D1/D2, SEC-032) | Not touched by this job; summaries are PII-scrubbed (`PiiPatterns`) and carry ids, never values or export reasons |
| Backups | infrastructure snapshots | **90 days** | Infra policy | Managed by the hosting platform's backup rotation, outside the application |

Configuration: `modocument.retention.customer-data-days`
(`MODOCUMENT_RETENTION_DAYS`, default **30**),
`modocument.retention.enabled` (`MODOCUMENT_RETENTION_ENABLED`, default
**true**; false in the test profile so the purge never races the suite),
`modocument.retention.cron` (daily at 02:41 by default).
Per bank (ws20): the period is also the setting `retention.customerDataDays`
(Data protection tab, maker/checker), limited to **1–30 days** so a bank or
the platform owner can only shorten it; the sweep computes each bank's
cutoff from that bank's value. The on/off switch is locked on in the
settings (the yml flag above still gates the schedule).

### Policy notes

* **Uploads are purged 30 days from upload, unconditionally.** A job
  created later from an existing upload keeps its own parsed checkpoints
  until its own cutoff; the original binary still leaves at day 30.
* Every run writes one `RETENTION_PURGE_COMPLETED` audit event **per
  bank** with counts per category (zero counts included — proof the bank
  was swept) and one `RetentionRun` summary line in the application log.
  That audit event is the gate C5 "job log shows its deletion" evidence
  and feeds `GET /api/admin/retention/status`.
* The manual trigger `POST /api/admin/retention/run` runs the identical
  sweep; it only enforces the configured policy, so running it early is
  always safe. Users can also delete their own jobs ahead of the schedule:
  `DELETE /api/ingestion/jobs/{id}` (owner-scoped, `ingestion:write`)
  removes the job row, its export archive copies and — when no other job
  references it — the uploaded file and its row, audited as `JOB_DELETED`.

## DSR runbook (search → export → erase)

A data-subject request arrives (access or erasure). The handling Admin
works in their own bank only; every step is audited.

1. **Search** — `POST /api/admin/dsr/search {"identifier": "<value>"}`
   (or the *Search* box in the panel). The identifier is any VALUE the
   subject is known by: NIC, account number, email, full name. Matching:
   * structured values (ingestion row cells, import payload values,
     extraction field values) match on **case-insensitive equality** of
     the trimmed value — erasure must not take neighbouring subjects
     with it;
   * unstructured text (extraction raw bodies, document paragraphs and
     captions, file names) matches on **case-insensitive contains**.
   The response is a per-store hit summary (ids and row counts, no
   values). Audited `DSR_SEARCH` (counts only; the subject is referenced
   by a SHA-256 prefix, never by value).
2. **Export** — `POST /api/admin/dsr/export {"identifier": ...}` returns
   a ZIP containing `dsr-export.json` (machine-readable: every matching
   row in full, per store) and `dsr-export.txt` (human-readable rendering
   of the same content). *Format decision:* the human-readable copy is
   plain UTF-8 text, not PDF — the only PDF path in the stack needs
   headless LibreOffice, which most deployments of the API container do
   not have; a text report is readable everywhere and carries identical
   content. Audited `DSR_EXPORTED` (counts, no values).
3. **Erase** — `POST /api/admin/dsr/erase {"identifier": ..., "confirm": true}`
   (the panel requires typing the identifier back). Per store:
   * **Ingestion jobs** — the subject's rows are filtered OUT of the
     parsed and mapped row arrays (other subjects' rows untouched); the
     validation checkpoint is cleared (derived data keyed by row index —
     a revalidate regenerates it from the surviving rows).
   * **Original uploads of matched jobs** — deleted entirely, with their
     `UploadedFile` rows: an original document (PDF/XLSX binary) cannot
     be row-filtered, and the 30-day auto-deletion bounds what an erasure
     must cover anyway (workbook position). Export archive copies of
     matched jobs are deleted for the same reason.
   * **Import payloads** — matching `RawJson` columns nulled.
   * **Extraction records** — matching raw/processed records deleted
     (each record is one capture of the subject).
   * **Document content** — matching blocks deleted; an image block's
     asset (row + file) goes with its matching caption.
   * **Name-matched uploads** — a file whose NAME is the identifier is
     deleted with its row.
   Audited `DSR_ERASED` with per-store counts. A re-run of step 1 must
   return zero matches — that re-run is the closure evidence.

### Limitations and positions

* **File content is not scanned.** Uploaded binaries are matched by file
  name and via their PARSED rows (which ARE the file's content in
  searchable form). A file that was never parsed and whose name does not
  carry the identifier is not found by search — it falls to the 30-day
  purge.
* **Statutory period:** the response window (e.g. 30 days under GDPR /
  the Mauritius DPA) is tracked in the program workbook (pack-09 is
  pending); this runbook covers the mechanical part, which completes in
  minutes.
* **Backups:** erasure applies to the live system. Backup snapshots
  rotate out within 90 days (see the schedule above); restored backups
  must be re-erased by re-running step 3 before going live — record this
  in the request ticket.
* The subject's identifier never enters the audit trail or the
  application log; audit rows reference a SHA-256 prefix.

## Sign-off

Retention schedule and DSR procedure approved:

| | |
|---|---|
| Name | Reezvi Pydiah |
| Role | Accountable executive, MoDocument security program |
| Signature | ______________________________ |
| Date | ______________ |
