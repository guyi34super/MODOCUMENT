-- ws12 (PII classification + role/channel masking, gate item C2,
-- PRIV-004/PRIV-005): template fields carry an explicit PII classification.
--
--  * TemplateFields.PiiKind — which kind of personal data the field holds
--    (see com.modocument.domain.model.PiiKind). NULL means "not classified
--    yet": the first read that needs the classification (an export, a
--    read-only screen render) infers it via PiiClassifier from the field
--    name, the field's validation rules and sample values, and persists the
--    result here so the template owner can see and correct it in the
--    template editor. An explicit value — including 'NONE' — always wins
--    over inference and is never overwritten by the classifier.
--
-- The vocabulary is closed (ck_templatefields_piikind), matching the enum
-- persisted BY NAME; NULL stays allowed as the "unclassified" state.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the matching piiKind field and
-- @Check mirror on TemplateFields must be kept in sync with this file by
-- hand.

ALTER TABLE TemplateFields ADD COLUMN PiiKind varchar(20) NULL;

ALTER TABLE TemplateFields ADD CONSTRAINT ck_templatefields_piikind
    CHECK (PiiKind IN ('NIC', 'ACCOUNT_NUMBER', 'EMAIL', 'PHONE', 'NAME', 'NONE'));
