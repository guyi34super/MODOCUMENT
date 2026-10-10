-- ws23 (phase 2, code-based extraction fallback — docs/phase2/contracts.md
-- §3/§4, plan §4): the per-template extraction mode, per-field extraction
-- hints for the code extractor, and the parse provenance of every ingestion
-- job.
--
--  * Template.ExtractionMode — AI_WITH_CODE_FALLBACK, CODE_ONLY or AI_ONLY;
--    NULL means "inherit the bank's extraction.defaultMode setting".
--  * TemplateFields.Aliases / DateFormats — JSON arrays of strings
--    (alternative header/label texts; DateTimeFormatter patterns tried before
--    the built-ins). LabelPattern / ValuePattern — author-supplied regexes,
--    vetted by SafeRegex on save (length <= 500 matches its cap) and again
--    before every use.
--  * IngestionJob.ParsedBy (AI | CODE | AI_PARTIAL), ParseStrategy (how),
--    ParseConfidence (the code extractor's 0..1 score, 3 decimals) and
--    ParseFallbackReason (why code stood in for the AI). Counts and labels
--    only — never cell values.
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): additive only. Every
-- column is nullable with no default, so the previous release — whose
-- entities know none of them — keeps inserting rows that read as "inherit"
-- / "no hints" / "provenance unknown". The CHECKs admit NULL and only
-- constrain values the previous release never writes; they are added NOT
-- VALID because every existing row holds NULL (which passes) — that only
-- skips a pointless full-table scan under lock on IngestionJob; new and
-- updated rows are checked either way. No new table, so the
-- row-level security policies (afterMigrate) are unchanged: the columns live
-- on BankId-stamped tables that are already tenant-filtered.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so Template (ck_template_extractionmode),
-- TemplateFields and IngestionJob (ck_ingestionjob_parsedby) mirror this file
-- by hand; FlywayBootPgIT / ProductionBootPgIT validate the mapping against
-- PostgreSQL.

ALTER TABLE Template ADD COLUMN ExtractionMode varchar(30);
ALTER TABLE Template
    ADD CONSTRAINT ck_template_extractionmode
        CHECK (ExtractionMode IN ('AI_WITH_CODE_FALLBACK', 'CODE_ONLY', 'AI_ONLY')) NOT VALID;

ALTER TABLE TemplateFields ADD COLUMN Aliases text;
ALTER TABLE TemplateFields ADD COLUMN LabelPattern varchar(500);
ALTER TABLE TemplateFields ADD COLUMN ValuePattern varchar(500);
ALTER TABLE TemplateFields ADD COLUMN DateFormats text;

ALTER TABLE IngestionJob ADD COLUMN ParsedBy varchar(15);
ALTER TABLE IngestionJob
    ADD CONSTRAINT ck_ingestionjob_parsedby
        CHECK (ParsedBy IN ('AI', 'CODE', 'AI_PARTIAL')) NOT VALID;
ALTER TABLE IngestionJob ADD COLUMN ParseStrategy varchar(30);
ALTER TABLE IngestionJob ADD COLUMN ParseConfidence numeric(4, 3);
ALTER TABLE IngestionJob ADD COLUMN ParseFallbackReason varchar(30);
