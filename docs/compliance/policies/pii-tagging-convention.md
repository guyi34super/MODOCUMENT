# PII tagging convention

Control **PRIV-004** (privacy by design: "the ORM PII-tag convention doc"), feeding PRIV-005
(masking) and PRIV-013 (DSR). Written from the code on 2026-10-19.

MoDocument holds two kinds of personal data, and each has its own tagging rule.

## 1. Customer data: tagged per field

Customer values never live in fixed columns. They live in JSON rows whose fields are defined by a
bank's template, so the **template field** carries the tag.

| Rule | Where in the code |
|---|---|
| Every template field has a `PiiKind`: `NIC`, `ACCOUNT_NUMBER`, `EMAIL`, `PHONE`, `NAME` or `NONE` | `src/main/java/com/modocument/domain/model/PiiKind.java` |
| Flow templates store it in `TemplateFields.PiiKind` (closed vocabulary, database CHECK) | `src/main/resources/db/migration/V100__ws12_pii_tags.sql`, `src/main/java/com/modocument/domain/entity/TemplateFields.java` |
| Document-creation data templates store it in `DocDataField.PiiKind`, validated against the enum on save and copied from the flow field | `src/main/resources/db/migration/V330__ws34_doc_data_template.sql`, `src/main/java/com/modocument/doccreation/usecase/DataTemplateUseCase.java` |
| An explicit tag from the template owner (including `NONE`) always wins and is never re-inferred | `src/main/java/com/modocument/application/privacy/PiiClassifier.java` |
| An untagged field is classified once — by field name, then validation-rule types, then unanimous value shapes — and the result is persisted so the owner can see and correct it | `src/main/java/com/modocument/application/privacy/PiiClassifier.java` |
| The tag drives masking per channel and role | `src/main/java/com/modocument/application/privacy/MaskingPolicy.java`, `src/main/java/com/modocument/application/privacy/PiiMaskingService.java`, `docs/security/masking-matrix.md` |
| Creating a field audits its PII kind (`TEMPLATE_FIELD_CREATED`) | `docs/security/audit-events.md` |

## 2. Free text and logs: tagged by pattern

Where no field exists (file bodies, log lines, LLM prompts, alert summaries), one shared pattern
set finds NIC formats, account-number runs, e-mails, bearer tokens, JWTs and credential key/value
pairs: `src/main/java/com/modocument/application/privacy/PiiPatterns.java`. It drives the log
redaction (`src/main/resources/logback-spring.xml`), the LLM tokeniser and fail-closed prompt scan
(`src/main/java/com/modocument/application/privacy/PiiTokenizer.java`) and the alert scrubbing.

## 3. MoDocument's own tables: listed, not annotated

Fixed columns that hold personal data (for example `AppUsers.Email`, `AppUsers.DisplayName`,
`AuditEvent.ActorEmail`, `RefreshToken.Ip`) are listed in `docs/security/data-inventory.md`
rather than annotated in the entities. There is no `@PersonalData`-style annotation on JPA fields
today; this is the remaining gap against the workbook's "PII annotation in the data model".

## 4. Developer checklist (also in the PR template)

1. A new stored field that can hold personal data gets a `PiiKind` (template data) or a row in
   the data inventory (fixed column) and, if displayed, a masking-matrix entry.
2. It gets a retention category and is reached by the purge job.
3. DSR search/export/erase reach it.
4. It is never logged unredacted and never sent to a model without tokenisation.

Source: the "Privacy checklist (PRIV-004)" section of `.github/pull_request_template.md`.
