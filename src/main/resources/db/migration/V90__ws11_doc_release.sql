-- ws11 (report release, gate B8, SEC-012/SEC-024): separation of duties on
-- document release.
--
-- A generated document is no longer "done" when its author says so: the
-- author SUBMITS it (GENERATED -> SUBMITTED) and only a DIFFERENT user
-- holding doc:release can RELEASE it. The releaser being the author (owner)
-- or the submitter is a hard 409, audited DOC_SELF_RELEASE_BLOCKED (DENIED)
-- — even when the author also holds the Approver role (B8). A rejection is
-- NOT a stored status: it transitions the instance back to GENERATED (the
-- author reworks and resubmits) and the decision lives in the
-- DOC_RELEASE_REJECTED audit row plus ReviewComment.
--
--  * Status — DRAFT -> GENERATED -> SUBMITTED -> RELEASED; reject returns
--    SUBMITTED -> GENERATED. V9 shipped the column without a CHECK; the
--    constraint lands now that the vocabulary is final.
--  * SubmittedByUserId/SubmittedAt — who sent it for review (today always
--    the owner — the submit endpoint is owner-scoped — recorded separately
--    so the SoD check and the nightly scan need no join to prove
--    releaser != submitter).
--  * ReleasedByUserId/ReleasedAt — the approving human. The nightly
--    SodScanJob asserts ReleasedByUserId differs from both OwnerUserId and
--    SubmittedByUserId on every RELEASED row (SEC-024 layer 3).
--  * ReviewComment — the approver's release/reject note (C10: the human
--    review of AI-written content is recorded).
--  * AiComposed — true when the last generation's content was composed by
--    the AI path (AiDocumentComposer), false when the deterministic engine
--    carried it. Surfaced to the approver (C10): releasing an AI-composed
--    document IS the human check of the model's text and figures.
--
-- ROW LEVEL SECURITY: DocInstance already carries BankId (V11), so the
-- afterMigrate__rls_enforce.sql callback keeps covering it — approvers see
-- their own bank's queue only.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA
-- entities (ddl-auto: create, Flyway disabled), so the DocInstance entity
-- must be kept in sync with this file by hand.

ALTER TABLE DocInstance ADD COLUMN SubmittedByUserId integer NULL REFERENCES AppUsers (AppUserId);
ALTER TABLE DocInstance ADD COLUMN SubmittedAt timestamp(6) NULL;
ALTER TABLE DocInstance ADD COLUMN ReleasedByUserId integer NULL REFERENCES AppUsers (AppUserId);
ALTER TABLE DocInstance ADD COLUMN ReleasedAt timestamp(6) NULL;
ALTER TABLE DocInstance ADD COLUMN ReviewComment varchar(500) NULL;
ALTER TABLE DocInstance ADD COLUMN AiComposed boolean NOT NULL DEFAULT false;

ALTER TABLE DocInstance ADD CONSTRAINT ck_docinstance_status
    CHECK (Status IN ('DRAFT', 'GENERATED', 'SUBMITTED', 'RELEASED'));

-- The approver queue (Status = 'SUBMITTED' per bank) and the nightly scan
-- (Status = 'RELEASED' per bank) both filter on (BankId, Status).
CREATE INDEX IF NOT EXISTS ix_docinstance_bankid_status ON DocInstance (BankId, Status);
