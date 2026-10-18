# Audit event catalogue

Control: **C7** (append-only audit trail, `AuditEvent`, V14). Audit API and viewer: workstream 28
(`docs/optimization/contracts.md` §3 and §4).

Every action the application writes is listed below. The list is generated from
`application/audit/AuditActions` (the catalogue served by `GET /api/admin/audit/actions`).
`AuditActionCatalogueDocTest` fails when this page and the code drift apart.

## Row anatomy

| Column | Content |
|---|---|
| `AuditEventId` | UUIDv7 (time-ordered) |
| `OccurredAt` | server time of the write |
| `BankId` | the tenant (RLS); an unattributable anonymous row goes to the default bank (1) |
| `ActorUserId` / `ActorEmail` / `ActorRole` | who: the authenticated caller, or the account an anonymous flow names |
| `SessionJti` | the access token's jti (`LOGIN_SUCCESS` and `TOKEN_REFRESHED`: the refresh token's) |
| `RequestId` / `Ip` | correlation id (`X-Request-Id`) and client address |
| `Action` / `Outcome` | the catalogue name; `SUCCESS`, `DENIED` or `FAILED` |
| `EntityType` / `EntityId` | what was acted on |
| `OldValues` / `NewValues` | small JSON snapshots, never secrets; truncated above 64 KB |

## Flood control (workstream 28)

- **`ACCESS_DENIED` is aggregated** by the recorder, whatever the call site. The key is
  (bank, actor, or the client ip when no one is authenticated, entity type). The first denial
  of a key is written at once with `count: 1` and opens a one-minute window. Later denials in the
  window are only counted. The suppressed count is written as one row when the window ends
  (a flush runs every 15 s), or by the next denial after it. That row opens the next window.
  So a key never gets more than one row per minute, and the sum of `count` is the number of
  refusals. Memory is bounded at 10 000 keys. Past that, new keys share one overflow key per
  bank and entity type, so a spray from many addresses still writes few rows. The windows are
  in memory per task.
- **Read audits are deduplicated.** `SENSITIVE_DATA_VIEWED` is written once per (bank, actor,
  entity) per hour. `AUDIT_LOG_VIEWED` is written once per (bank, actor) per 15 minutes. Both
  write in their own transaction and never fail the read. A failed write is retried on the
  next read.
- `SENSITIVE_DATA_VIEWED` follows the bank setting `audit.readAuditEnabled` (BOOLEAN, BANK scope,
  SECURITY tier, platform-controlled, default `true`).
- Reading the trail never audits the rows it shows, so there is no recursion. `AUDIT_LOG_VIEWED`
  records the filters only.

## Detection

The windowed alert rule **`access-denied-burst`** raises a MEDIUM alert when one actor, or one
address for anonymous refusals, accumulates `alerts.accessDeniedBurst.threshold` (default 3)
refusals within `alerts.accessDeniedBurst.window` (default 5 minutes). Its group-by is
`ACTOR_OR_IP`. Rows are aggregated per minute, so the windowed sweep sums the `count` of each
`ACCESS_DENIED` row instead of counting rows (OBS-01, v1.2.0): 50 refusals inside one minute
(an opening row with `count: 1` and a flushed row with `count: 49`) alert like 50 refusals
spread over five minutes, and the alert reports 50 events. The suppressed tail of a minute is
written when its window ends, so a one-minute burst alerts within about two sweeps. Only the
`count` number is read from the row's values. See
[alerting-and-export-caps.md](alerting-and-export-caps.md).

## Viewer and API

| Route | Permission | Scope |
|---|---|---|
| `GET /api/admin/audit` | `AUDIT_READ` (ADMIN, APPROVER, PLATFORM_ADMIN) | the caller's bank, filters, keyset paging (`cursor`, `limit` 1–200) |
| `GET /api/admin/audit/export` | `AUDIT_READ` | the same filters as CSV, the newest 50 000 rows at most (`X-Export-Total`, `X-Export-Truncated`) |
| `GET /api/admin/audit/actions` | `AUDIT_READ` | this catalogue |
| `GET /api/platform/audit` | `AUDIT_READ` + platform bank | the platform bank's own events |
| `GET /api/platform/audit/counts` | `AUDIT_READ` + platform bank | per bank: total, denied and failed counts (counts only) |

Value masking follows the caller's channel:

- **ADMIN** reads like the working screens (raw unless the bank force-masks).
- **APPROVER and PLATFORM_ADMIN** read like a read-only screen (masked).
- **The CSV export** is the EXPORT channel (masked for everyone).

The actor columns are not masked.

## Catalogue

| Action | Category | Outcome | Entity | Recorded when |
|---|---|---|---|---|
| `LOGIN_SUCCESS` | Authentication | — | — | Sign-in (password, OIDC or MFA step); session jti = the opening refresh token. |
| `LOGIN_FAILED` | Authentication | — | — | Failed sign-in (unknown email, wrong password, suspended bank, ...). |
| `LOGOUT` | Authentication | — | — | Sign-out: access token denylisted, refresh families revoked. |
| `ACCOUNT_LOCKED` | Authentication | — | — | Account locked after repeated wrong passwords. |
| `TOKEN_REFRESHED` | Authentication | SUCCESS | RefreshTokenFamily | **ws28.** A refresh token was rotated (`POST /api/auth/refresh`); the session jti is the new refresh token's. |
| `TOKEN_REFRESH_REUSE_DETECTED` | Authentication | — | — | A rotated refresh token was replayed; the family was revoked. |
| `SESSION_IDLE_EXPIRED` | Authentication | — | — | Refresh refused: idle timeout. |
| `SESSION_ABSOLUTE_EXPIRED` | Authentication | — | — | Refresh refused: absolute session lifetime. |
| `TOKEN_VERSION_BUMPED` | Authentication | — | — | All of a user's tokens invalidated (password change, role change, reset). |
| `PASSWORD_CHANGED` | Authentication | — | — | The user changed their password. |
| `PASSWORD_REHASHED` | Authentication | — | — | A legacy hash was upgraded to Argon2id at sign-in. |
| `MFA_ENROLLED` | Authentication | — | — | TOTP enrolment started. |
| `MFA_ENABLED` | Authentication | — | — | TOTP enrolment confirmed. |
| `MFA_DISABLED` | Authentication | — | — | MFA turned off. |
| `RECOVERY_CODE_USED` | Authentication | — | — | A recovery code was used. |
| `IDENTITY_LINKED` | Authentication | — | — | An OIDC identity was linked to a user. |
| `USER_PROVISIONED_OIDC` | Authentication | — | — | A user was created just-in-time from OIDC. |
| `USER_ROLE_MAPPED_OIDC` | Authentication | — | — | An OIDC group mapping changed a user's role. |
| `AUTHORIZATION_DENIED` | Access control | — | — | `@RequiresPermission` refused a route (entity Route = path). |
| `ACCESS_DENIED` | Access control | DENIED | Route / owned entity / BearerToken | **ws28.** A refusal outside method security, **aggregated** (see below). `newValues.kind`: `USE_CASE` (an `AccessDeniedException` inside a use case; entity Route = path), `NOT_FOUND_PROBE` (an ownership check answered 404: Template, UploadedFile, IngestionJob, DocBlueprint, DocInstance), `INVALID_TOKEN` (a presented bearer token was refused: bad signature, expired, revoked, version bumped; keyed by ip). Carries `count`, `windowStart`, and `sampleEntityIds` when one row stands for several ids. |
| `SELF_APPROVAL_BLOCKED` | Access control | — | — | A maker tried to approve their own change. |
| `DOC_SELF_RELEASE_BLOCKED` | Access control | — | — | An author tried to release their own document. |
| `SOD_SCAN_COMPLETED` | Access control | — | — | Nightly separation-of-duties scan finished. |
| `SOD_VIOLATION_FOUND` | Access control | — | — | The scan found a separation-of-duties violation. |
| `BOOTSTRAP_ADMIN_CREATED` | User administration | — | — | First-start administrator seeded. |
| `USER_CREATED` | User administration | — | — | A user was created (maker/checker applied). |
| `USER_UPDATED` | User administration | — | — | A user's profile was edited by an admin. |
| `USER_ROLE_CHANGED` | User administration | — | — | A user's role changed. |
| `USER_DISABLED` | User administration | — | — | A user was disabled. |
| `USER_ENABLED` | User administration | — | — | A user was re-enabled. |
| `USER_UNLOCKED` | User administration | — | — | A locked account was unlocked. |
| `USER_PASSWORD_RESET` | User administration | — | — | An admin reset a password. |
| `USER_MFA_RESET` | User administration | — | — | An admin reset a user's MFA. |
| `USER_LIST_EXPORTED` | User administration | — | — | The user list was exported. |
| `ACCESS_REVIEW_GENERATED` | User administration | — | — | An access review report was produced. |
| `CHANGE_REQUESTED` | Maker/checker | — | — | A maker/checker change was submitted. |
| `CHANGE_APPROVED` | Maker/checker | — | — | A change was approved and applied. |
| `CHANGE_REJECTED` | Maker/checker | — | — | A change was rejected. |
| `CHANGE_CANCELLED` | Maker/checker | — | — | A change was withdrawn by its maker. |
| `CHANGE_APPLY_FAILED` | Maker/checker | — | — | An approved change could not be applied. |
| `TEMPLATE_CREATED` | Templates | SUCCESS | Template | **ws28.** A template was created: `POST /Templates/Create` (name, extraction mode), or `POST /Templates/CreateWithFields` (one row for the whole composite: name, visibility, `fieldCount`, `ruleCount`, `via`). |
| `TEMPLATE_UPDATED` | Templates | — | — | A template's name, description or extraction mode changed. |
| `TEMPLATE_DELETED` | Templates | — | — | A template was deleted. |
| `TEMPLATE_VISIBILITY_CHANGED` | Templates | — | — | A template was shared with the bank or made private. |
| `TEMPLATE_FIELD_CREATED` | Templates | SUCCESS | TemplateFields | **ws28.** `POST /TemplateFields/Create`; new values = template, field name, data type, PII kind. |
| `TEMPLATE_FIELD_UPDATED` | Templates | SUCCESS | TemplateFields | **ws28.** `PUT /TemplateFields/Update/{id}`; old and new definition. |
| `TEMPLATE_FIELD_DELETED` | Templates | SUCCESS | TemplateFields | **ws28.** `DELETE /TemplateFields/{id}`; old definition. |
| `VALIDATION_RULE_CREATED` | Templates | SUCCESS | ValidationRule | **ws28.** `PUT /ValidationRules/Upsert` for a (template, field, rule type) that did not exist; entity id `templateId:fieldName:ruleType`, new values include the rule config. |
| `VALIDATION_RULE_UPDATED` | Templates | SUCCESS | ValidationRule | **ws28.** The same upsert over an existing rule; old and new config. |
| `VALIDATION_RULE_DELETED` | Templates | SUCCESS | ValidationRule | **ws28.** `DELETE /ValidationRules` (either form) that removed a rule. |
| `FILE_UPLOADED` | Ingestion | SUCCESS | UploadedFile | **ws28.** A file was stored: `/api/fileupload/upload`, the multipart `POST /api/ingestion/jobs`, the integration API `POST /files`. Entity id = stored name; values = size, SHA-256 (never the original name, which can carry personal data). |
| `INGESTION_JOB_CREATED` | Ingestion | SUCCESS | IngestionJob | **ws28.** An ingestion job was created (template, stored file name, origin `user` or `api-client:<id>`). |
| `INGESTION_REMAPPED` | Ingestion | SUCCESS | IngestionJob | **ws28.** `POST /api/ingestion/jobs/{id}/remap` re-ran mapping (no values); `PUT /api/ingestion/jobs/{id}/mapping` applied a manual mapping (BUG-10): values = `source: MANUAL` and `mappedColumns` (a count, never cell values). |
| `INGESTION_ROWS_EDITED` | Ingestion | SUCCESS | IngestionJob | **ws28.** `PUT /api/ingestion/jobs/{id}/rows`; values = row count and changed rows (never cell values). Also `POST /api/ingestion/jobs/{id}/chat-edit` (BUG-11): the same counts plus `source: CHAT_EDIT`. |
| `INGESTION_PARSE_FALLBACK` | Ingestion | — | — | A job was parsed by code extraction instead of the AI. |
| `JOB_DELETED` | Ingestion | — | — | An ingestion job (and its files) was deleted by its owner. |
| `EXPORT_RUN` | Exports | — | — | The export log: a file or database export succeeded (format/target, rows, cap). |
| `EXPORT_DOWNLOADED` | Exports | SUCCESS | IngestionJob | **ws28.** An export file was delivered to the caller (file name, format, rows, bytes). `EXPORT_RUN` remains the export log of both export paths. |
| `EXPORT_BLOCKED` | Exports | — | — | An export above the role cap was refused (no reason given). |
| `EXPORT_OVER_CAP_WITH_REASON` | Exports | — | — | An export above the cap was allowed with a stated reason. |
| `DOC_GENERATED` | Documents | SUCCESS / FAILED | DocInstance | **ws28, ws31.** `POST /api/doccreation/instances/{id}/generate`: the PDF drawn onto the template (format, exact, addedPages, continued keys). FAILED when the exactness self-check refused the output (500 EXACTNESS_FAILED): exact false, differing pixels, pages, dpi. |
| `DOC_DOWNLOADED` | Documents | SUCCESS | DocInstance | **ws28.** `GET /api/doccreation/instances/{id}/download` (format — always pdf since ws31 — and bytes). |
| `DOC_SUBMITTED` | Documents | — | — | A document was submitted for release. |
| `DOC_RELEASED` | Documents | — | — | A document was released. |
| `DOC_RELEASE_REJECTED` | Documents | — | — | A release was rejected. |
| `DOC_BLUEPRINT_CREATED` | Documents | SUCCESS | DocBlueprint | **ws31.** A PDF template was uploaded as a DRAFT: `POST /api/doccreation/blueprints` — flow (BLANK / WITH_DATA), pages, proposed regions and removals (applied), suggestedRemovals (low-confidence guesses shown for review, not applied), detectedBy (CODE / CODE+AI). Never the removed sample values. |
| `DOC_BLUEPRINT_VERSION_SAVED` | Documents | SUCCESS | DocBlueprint | **ws29, ws31.** A template version was saved: `PUT /api/doccreation/blueprints/{id}/overlay` — version, region, edit and removal counts, whether it confirmed the draft, note. |
| `DOC_LEGACY_PURGED` | Documents | SUCCESS | Bank | **ws31.** `POST /api/admin/doccreation/purge-legacy`: the caller's bank's Word-based templates were deleted with their documents and stored files — counts of templates, documents and files. |
| `DOC_BLUEPRINT_MAPPING_SAVED` | Documents | SUCCESS | DocBlueprint | **ws32.** A data source of a PDF template was added (`POST /api/doccreation/blueprints/{id}/sources`, mapping pre-filled by auto-mapping), saved (`PUT …/sources/{sourceId}`) or removed (`DELETE …/sources/{sourceId}`) — change (CREATED / UPDATED / DELETED), source id, flow template id, role, number of mapped regions, linked. Counts only, never values. |
| `DOC_FILLED_FROM_RECORD` | Documents | SUCCESS | DocInstance | **ws32.** `POST /api/doccreation/instances/{id}/fill-from-record`: a document was filled from flow records — the record ids used, the region keys written, the overwrite mode and the mapping source (SAVED / AUTO). Never values. |
| `DOC_FIELD_RULES_SAVED` | Documents | SUCCESS | DocBlueprint | **ws33.** `PUT /api/doccreation/blueprints/{id}/rules`: a PDF template's field rules were saved — the new rules revision and the number of rules, by type and severity. Never configs or values. |
| `DOC_FIELD_RULES_IMPORTED` | Documents | SUCCESS | DocBlueprint | **ws33.** `POST /api/doccreation/blueprints/{id}/rules/import`: field rules were copied from a flow template's field rules — the source (DATA_SOURCES or the template id), the mode (MERGE / REPLACE), the number of fields and rules imported and skipped. Never configs or values. |
| `DOC_VALIDATED` | Documents | SUCCESS / FAILED | DocInstance | **ws33.** `POST /api/doccreation/instances/{id}/validate`: a document was checked against its template's fields and rules — SUCCESS when it became VALIDATED, FAILED when it stays a DRAFT; the number of errors and warnings, the region keys with errors and the rules revision. Never values. |
| `DOC_DATA_TEMPLATE_SAVED` | Documents | SUCCESS | DocDataTemplate | **ws34.** A data template (the validation of document creation) was created, or its details, fields (`PUT /api/doccreation/data-templates/{id}/fields`) or rules (`PUT …/rules`) were saved — what changed, the counts of fields and rules and the new rules revision. Never configs or values. |
| `DOC_DATA_TEMPLATE_DELETED` | Documents | SUCCESS | DocDataTemplate | **ws34.** `DELETE /api/doccreation/data-templates/{id}`: a data template no PDF template uses was deleted — its field and rule counts. |
| `DOC_DATA_TEMPLATE_COPIED` | Documents | SUCCESS | DocDataTemplate | **ws34.** `POST /api/doccreation/data-templates/from-flow-template`, `…/from-flow-templates` or `…/{id}/add-from-flow-templates`: a data template was made from (or added to from) one or several flow templates — the flow template ids (`templates`; the single copy also `templateId`), the counts of fields and rules copied and skipped, and `added`. Never names, configs or values. |
| `DOC_BLUEPRINT_DATA_MAPPING_SAVED` | Documents | SUCCESS | DocBlueprint | **ws34.** `PUT /api/doccreation/blueprints/{id}/data-template` or `…/data-mapping`: a PDF template's validation (data template) was chosen or its fields mapped — the data template id, mapped and unmapped counts, whether the managed data source changed. |
| `DOC_VALIDATION_SETTING_CHANGED` | Documents | SUCCESS | DocBlueprint / DocInstance | **ws34.** Validation was switched on or off: for a PDF template (`PUT /api/doccreation/blueprints/{id}/validation-setting`, the default for new documents) or for one document (`PUT /api/doccreation/instances/{id}/validation-setting`) — enabled true/false and, for a document, the status after the switch. |
| `SENSITIVE_DATA_VIEWED` | Privacy | SUCCESS | IngestionJob / ExtractionJob / AppUserList / DataSubject / TemplateRecord | **ws28.** Read audit, once per actor, entity and hour: the ingestion job detail, extraction records, the user list, the DSR search, a flow record (ws32: `GET /api/doccreation/records/{id}` and the fill preview). Gated by the bank setting `audit.readAuditEnabled` (on when absent). |
| `TEMPLATE_RECORDS_SEARCHED` | Privacy | SUCCESS | Template | **ws32.** `GET /api/doccreation/records`: a flow template's validated records were searched — the first 16 hex characters of the query's SHA-256 (lower-cased), the field searched, the result count and the page. Never the query or values. |
| `DSR_SEARCH` | Privacy | — | — | A data-subject search was run (counts only). |
| `DSR_EXPORTED` | Privacy | — | — | A data-subject export was produced. |
| `DSR_ERASED` | Privacy | — | — | A data subject was erased. |
| `RETENTION_PURGE_COMPLETED` | Privacy | — | — | The daily retention purge finished for the bank. |
| `AUDIT_LOG_VIEWED` | Audit | SUCCESS | AuditLog | **ws28.** The audit viewer was read (`/api/admin/audit`, `/api/platform/audit`, `/api/platform/audit/counts`); once per actor per 15 minutes; values = the filters. |
| `AUDIT_LOG_EXPORTED` | Audit | SUCCESS | AuditLog | **ws28.** Every CSV export of the trail: filters, `rowCount`, `matched`, `truncated`. |
| `AI_CALL` | AI | — | — | One model call (purpose, model, tokens; no prompt text). |
| `AI_PROMPT_REFUSED` | AI | — | — | The AI gateway refused a cross-bank prompt. |
| `AI_PROMPT_BLOCKED_PII` | AI | — | — | A prompt was blocked for untokenizable personal data. |
| `AI_OUTPUT_BLOCKED` | AI | — | — | A model reply was withheld by the output policy. |
| `AI_OUTPUT_FLAGGED` | AI | — | — | A model reply was flagged by the output policy. |
| `AI_CIRCUIT_OPENED` | AI | — | — | The AI circuit breaker opened (platform bank). |
| `SETTING_UPDATED` | Settings | — | — | An operational setting changed. |
| `SECURITY_SETTING_UPDATED` | Settings | — | — | A security setting changed (maker/checker). |
| `PLATFORM_SETTING_UPDATED` | Settings | — | — | The platform owner changed a global default or a bank bound. |
| `PLATFORM_BOOTSTRAPPED` | Platform | — | — | The platform bank and its admins were seeded. |
| `BANK_ONBOARDED` | Platform | — | — | A bank was onboarded. |
| `BANK_ONBOARDING_REVERTED` | Platform | — | — | A failed onboarding was rolled back. |
| `BANK_SUSPENDED` | Platform | — | — | A bank was suspended. |
| `BANK_REACTIVATED` | Platform | — | — | A bank was reactivated. |
| `BANK_ADMIN_ADDED` | Platform | — | — | A break-glass admin was added to a bank. |
| `EGRESS_RULE_REQUESTED` | Platform | — | — | A bank requested an egress rule. |
| `EGRESS_RULE_APPROVED` | Platform | — | — | The platform approved an egress rule. |
| `EGRESS_RULE_REVOKED` | Platform | — | — | An egress rule was revoked. |
| `ANNOUNCEMENT_PUBLISHED` | Platform | — | — | A platform announcement was published. |
| `ANNOUNCEMENT_ARCHIVED` | Platform | — | — | An announcement was archived. |
| `VERSION_DEPLOYED` | Platform | — | — | A new application version started. |
| `API_CLIENT_CREATED` | Integration | — | — | An integration API client was created. |
| `API_CLIENT_ROTATED` | Integration | — | — | An API client secret was rotated. |
| `API_CLIENT_REVOKED` | Integration | — | — | An API client was revoked. |
| `API_CLIENT_TOKEN_ISSUED` | Integration | — | — | An OAuth2 client token was issued. |
| `API_CLIENT_AUTH_FAILED` | Integration | — | — | An API client failed to authenticate. |
| `API_CLIENT_INITIAL_SECRET_READ` | Integration | — | — | An API client's one-time secret was read. |
| `INTEGRATION_API_CALL` | Integration | — | — | One Integration API v1 call. |
| `INTEGRATION_CONNECTION_CHANGED` | Integration | — | — | An integration connection was created, changed or deleted. |
| `INTEGRATION_FLOW_SAVED` | Integration | — | — | An integration flow was saved. |
| `INTEGRATION_FLOW_ENABLED` | Integration | — | — | A flow was enabled. |
| `INTEGRATION_FLOW_DISABLED` | Integration | — | — | A flow was disabled. |
| `INTEGRATION_FLOW_DELETED` | Integration | — | — | A flow was deleted. |
| `INTEGRATION_RUN_COMPLETED` | Integration | — | — | An integration run succeeded. |
| `INTEGRATION_RUN_FAILED` | Integration | — | — | An integration run failed. |
| `INTEGRATION_DELIVERY_DEAD` | Integration | — | — | An outbound delivery gave up after its retries. |
| `AGENT_REGISTERED` | Integration | — | — | An on-premise agent was registered. |
| `AGENT_ENROLLED` | Integration | — | — | An agent enrolled with its one-time token. |
| `AGENT_ENROLMENT_INVALID` | Integration | — | — | An agent enrolment token was refused. |
| `AGENT_ONLINE` | Integration | — | — | An agent came back online. |
| `AGENT_OFFLINE` | Integration | — | — | An agent stopped sending heartbeats. |
| `AGENT_REVOKED` | Integration | — | — | An agent was revoked. |
| `ALERT_SENT` | Alerting & operations | — | — | A security alert was delivered. |
| `ALERT_DELIVERY_FAILED` | Alerting & operations | — | — | An alert channel failed. |
| `HOUSEKEEPING_COMPLETED` | Alerting & operations | SUCCESS | Bank | **ws28.** Housekeeping retention sweep finished (SE1, ws27). |
