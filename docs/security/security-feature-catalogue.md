# Security feature catalogue — SEC-027

The one answer that frames all others: **every security control MoDocument
implements, with the code, test and document that prove it.** Everything
listed here exists in this repository today; items that are *designed but
not yet executed* (cloud build-out, external exercises) are explicitly in
the README's "Implement later" table, not here. Last reviewed: 2026-10-04
(workstream 20).

Paths are relative to the repo root; `…/` abbreviates
`src/main/java/com/modocument/` and tests live under `src/test/java/`.

## 1. Identity & authentication (gate B)

| Gate/control | Feature | Code | Test / evidence |
|---|---|---|---|
| B1 | JWT bearer auth on every endpoint except login/refresh/health; 15-min access TTL; rotating 7-day httpOnly refresh cookie scoped to `/api/auth/refresh`; logout revokes the jti and every refresh token; password change bumps the token version (all sessions die) | `…infrastructure/security/JwtService.java`, `…application/usecase/AuthUseCase.java`, V15 migration | `AuthFlowIntegrationTest`, `JwtServiceTest`, `RevocationValidatorTest`; audit `LOGIN_SUCCESS`/`LOGIN_FAILED`/`TOKEN_VERSION_BUMPED` |
| B1 | Fail-fast secret hygiene: boot refuses a missing/short/known-default `MODOCUMENT_JWT_SECRET`; no secret committed for local dev | `…api/config/SecurityConfig.java`, `application-local-secrets.yml.example` | boot check; README "Authentication" |
| B2/B3 | OIDC SSO (Entra ID / Keycloak) — IdP authenticates (incl. provider MFA), app issues its own tokens; no token in URLs; password login stays break-glass; shipped Keycloak realm enforces password policy + TOTP enrolment | `…infrastructure/security/oidc/*`, `docker/keycloak/modocument-realm.json`, `application-oidc.yml.example` | `KeycloakRealmConfigTest`, `AuthProvidersConfiguredTest`; audit `USER_PROVISIONED_OIDC`, `USER_ROLE_MAPPED_OIDC`, `IDENTITY_LINKED`; [identity-providers.md](identity-providers.md) |
| B3 | Local TOTP MFA with AES-256-GCM-encrypted secrets, recovery codes (Argon2id-hashed, shown once), challenge tokens (5-min TTL); **MFA mandatory for ADMIN** (`require-for-admin`, default true) | `…application/usecase/MfaUseCase.java`, `…infrastructure/security/MfaSecretCipher.java`, `totp/Totp.java`, V41 | `MfaFlowIntegrationTest`, `MfaSecretCipherTest`, `TotpTest`; audit `MFA_ENROLLED`/`MFA_ENABLED`/`MFA_DISABLED`/`RECOVERY_CODE_USED` |
| B4 | Account lockout: 5 wrong passwords, exponential lock (1→30 min cap); constant-cost dummy Argon2id match for unknown emails and locked accounts (no user enumeration, no timing oracle) | `…application/usecase/AuthUseCase.java`, `UserSecurityService.java`, V40 | `AccountLockoutIntegrationTest`, `LoginLockoutPolicyTest`; audit `ACCOUNT_LOCKED` |
| SEC-021 | Password policy: min length 12 (configurable), breached-list + email-local-part checks always on; Argon2id hashing with transparent BCrypt upgrade | `…api/config/SecurityConfig.java`, `application.yml` | `UserSecurityServiceTest`; audit `PASSWORD_CHANGED`/`PASSWORD_REHASHED`; [crypto-standard.md](crypto-standard.md) |
| SEC-002 | Rate limiting: per-IP (30/15 min) + per-email (10/15 min) on the open auth endpoints; per-user LLM quota (60/h) on AI endpoints → 429 with Retry-After | `…infrastructure/security/` rate limiter, `application.yml` | `FixedWindowRateLimiterTest`, `LlmQuotaRetryAfterIntegrationTest` |

## 2. Authorization, roles & dual control (gates B5–B8, C10)

| Gate/control | Feature | Code | Test / evidence |
|---|---|---|---|
| B8 / SEC-012 | Four-role catalogue (ADMIN / APPROVER / READ_WRITE / READ_ONLY) × 25 permissions; **every route** mapped to a permission | `RolePermissions`, route security config | `RolePermissionMatrixTest` (pins [role-permission-matrix.csv](role-permission-matrix.csv) byte-for-byte), `RoutePermissionCoverageTest` (no unmapped route); [role-catalogue-and-sod.md](role-catalogue-and-sod.md) |
| B7 / SEC-023 | Maker/checker on user create / role change / disable and template update/delete: 202 + pending change, approval by a **different** holder of `change:approve`, self-approval always 409; user-security changes never exempt | `…application/usecase/PendingChangeUseCase.java`, V80 | `MakerCheckerIntegrationTest`, `TemplateMakerCheckerConfigOffIntegrationTest`; audit `CHANGE_REQUESTED/APPROVED/REJECTED/CANCELLED/APPLY_FAILED`; [maker-checker.md](maker-checker.md) |
| B5/B6 / PRIV-017 | JML: admin-created accounts only (no self-registration), bootstrap admin seeded once; movers via role change (dual control); leavers via disable (dual control) — all per-bank | `…application/usecase/UserAdminUseCase.java`, `…infrastructure/bootstrap/BootstrapAdminRunner.java`, V70 | `UserAdminIntegrationTest`, `BootstrapAdminRunnerTest`; audit `USER_CREATED`/`USER_ROLE_CHANGED`/`BOOTSTRAP_ADMIN_CREATED`; [jml-runbook.md](jml-runbook.md) |
| C10 / SEC-024 | Separation of duties: author ≠ releaser on document release; SoD conflict policy as data + scheduled scan with audited findings | `SodPolicy`, `…infrastructure/sod/SodScanJob.java`, `…doccreation/usecase/DocReleaseUseCase.java`, V90 | `SodPolicyTest`, `DocReleaseSodIntegrationTest`; audit `DOC_SUBMITTED`/`DOC_RELEASED`/`DOC_RELEASE_REJECTED`, `SOD_SCAN_COMPLETED`/`SOD_VIOLATION_FOUND` |
| SEC-025 | Periodic access review export (reviewable user/role listing, itself audited) | `…application/usecase/AccessReviewUseCase.java` | `AccessReviewUseCaseTest`; audit `ACCESS_REVIEW_GENERATED`, `USER_LIST_EXPORTED` |

## 3. Tenant isolation & data protection (gate C)

| Gate/control | Feature | Code | Test / evidence |
|---|---|---|---|
| C1 | **Two layers**: application owner/bank scoping on every query, plus PostgreSQL **row-level security** on every BankId table, fail-closed (no tenant bound → zero rows). The app connects as `modocument_app` (NOBYPASSRLS); Flyway migrates as the schema owner. An `afterMigrate` callback re-applies the policy to every new tenant table automatically | V11–V13, `afterMigrate__rls_enforce.sql`, `docker/postgres-init/01-app-role.sh`, `TenantAwareDataSource` | `TenantIsolationGatePgIT`, `RlsPolicyCoveragePgIT` (real PostgreSQL via Testcontainers in CI), `CrossUserAccessTest`, `ExtractionCrossUserAccessTest`, `UploadOwnershipEnforcementTest`, `DocCreationOwnershipIntegrationTest` |
| C2 / PRIV-004/005 | PII masking: per-field `PiiKind` classification (explicit tag wins, heuristic otherwise, persisted + correctable); channel × role masking policy applied at each channel's choke point; NIC/account numbers masked for every role in screens/reports/exports | `…application/privacy/*` (`PiiClassifier`, `MaskingPolicy`, `PiiMaskingService`, `Masker`), V100 | `MaskingGateC2Test`, `MaskerTest`, `MaskingMatrixDocTest` (doc pinned to code); [masking-matrix.md](masking-matrix.md) |
| C4 / SEC-028 | Encryption at rest and in transit: RDS KMS storage encryption, forced DB TLS, ALB TLS 1.2+/1.3, HSTS from the app behind the trusted proxy | `infra/terraform/modules/{rds,ecs-app,edge}`, `…api/config/SecurityConfig.java`, `frontend/nginx.conf` | `TrustedProxyIntegrationTest`; [crypto-standard.md](crypto-standard.md) |
| C5 / PRIV-013 | Retention: daily per-bank purge (30-day customer data, 7-year audit), manual run endpoint, per-bank audited sweep with zero counts as proof | `…infrastructure/retention/RetentionPurger.java`, V120 | `RetentionPurgeGateC5Test`; audit `RETENTION_PURGE_COMPLETED`; [retention-and-dsr.md](retention-and-dsr.md) |
| C6 / SEC-030 | Integrity: DB constraints hardening (V30), SHA-256 upload hashes (V31), upload content-type/structure gate (magic-byte checks, not extension trust) | V30/V31, `UploadContentChecker` | `IntegrityGateC6Test`, `IntegrityConstraintsPgIT`, `UploadContentGateSec030Test`, `UploadContentCheckerTest` |
| C7 / SEC-033 | Append-only audit trail: UUIDv7 ids, immutable entity, SUCCESS events join the business transaction (**no change commits unaudited**), DENIED/FAILED commit independently; actor, bank, jti, requestId, IP enriched; 64 KB payload truncation keeps rows valid | `…infrastructure/audit/{JpaAuditRecorder,AuditEventWriter}.java`, `…api/security/AuthorizationDeniedAuditListener.java`, V14 | `JpaAuditRecorderTest`, `AuditAppendOnlyPgIT` (append-only enforced in PostgreSQL); 7-year retention: [retention-and-dsr.md](retention-and-dsr.md), archive IaC `infra/terraform/modules/backups` |
| C12 | Data-subject rights: admin DSR search/export/erase per bank, audited | `…application/usecase/DataSubjectUseCase.java` | `DataSubjectRightsGateC12Test`; audit `DSR_SEARCH`/`DSR_EXPORTED`/`DSR_ERASED`; [retention-and-dsr.md](retention-and-dsr.md) |

## 4. AI-specific controls

| Control | Feature | Code | Test / evidence |
|---|---|---|---|
| PII tokenization | Sensitive cells replaced by stable placeholders before any prompt leaves the backend; mapping held server-side per request; placeholders swapped back in the answer (blind-patch). Fail-closed default (`aifiles.privacy.tokenize-pii=true`) | `…application/privacy/`, prompt strategies | README "Privacy: PII tokenization" (incl. honest boundary list: raw-document extraction and vision OCR inherently see content) |
| Data minimisation | LLM automapper sees column/field **names only**, never data; chat-edit sends only failing rows; mapping LLM fallback disable-able | mapping pipeline, `ChatEditScopeResolver` | `AutoMappingPromptTest`, `ChatEditScopeResolverTest`, `TableExtractionPromptTest` |
| Output validation | Structured output enforced; invented mapping targets discarded; doc-generation assembly plans validated (every original element kept once, nothing invented) before rendering | `LlmStructuredJsonParser`, `DocumentAssembler` | `LlmStructuredJsonParserTest`, `AiDocumentComposerTest`, `DocumentAssemblerTest` |
| Cost/abuse caps | Per-user LLM quota (60/h → 429); vision-OCR page budget (20 pages/PDF) | `application.yml` rate-limit + `ocr.vision.max-pages` | `LlmQuotaRetryAfterIntegrationTest`, `VisionOcrServiceTest` |
| Graceful degradation | Every LLM path degrades (no key → feature off, outage → unmapped/heuristic), never fails open | readers, mapper, doc-creation flows | `CompositeDocumentParserTest`, `AiSectionSplitterTest` |

Residual AI risks pending written acceptance (gate C11) are listed in the
README "Implement later" table.

## 5. Platform & operational controls

| Control | Feature | Code | Test / evidence |
|---|---|---|---|
| Trusted-proxy model | App port never published; nginx is the single entry and **overwrites** `X-Forwarded-*` (rate-limit keys, audit IPs, HSTS unspoofable) | `docker-compose.yml`, `frontend/nginx.conf` | `TrustedProxyIntegrationTest`; README Docker section |
| Log hygiene (SEC-026) | Redacting converters on every log pattern + structured JSON members; PII/credential patterns scrubbed, including exceptions | `…infrastructure/logging/Redacting*`, `…application/privacy/LogSafe.java` | `LogRedactionCanaryTest`, `StructuredLogRedactionTest` |
| Request traceability | Request-id filter + MDC (requestId, ip) carried onto async threads; stamped into every audit row | `…infrastructure/security/RequestIdFilter.java`, `ClientIps.java` | `JpaAuditRecorderTest` |
| Extraction compliance | Domain allow-list, robots.txt fail-closed, per-host rate limiter, honest bot UA, 401/402/403 terminal skips, queryable audit trail; per-run credentials never persisted | `…infrastructure/extraction/web/CompliantWebAccess.java` | `CompliantWebAccessTest`, `RobotsTxtParserTest`, `ExtractionSourceAuthEnvVarTest` |
| Export safety | DB-export identifiers strictly validated and quoted; row values only as bound parameters; one-shot credentials never persisted/logged | `…infrastructure/` database export | `DatabaseExportServiceTest` |
| Supply chain / CI | `mvn -B verify` + real-PostgreSQL IT suite + frontend tests on every push/PR; ECR scan-on-push + immutable tags (IaC); no static cloud keys in CI (OIDC deploy roles) | `.github/workflows/ci.yml`, `infra/terraform/modules/ci-oidc` | CI runs; ws19 adds SAST/secret scanning |
| Non-root runtime | Multi-stage Dockerfile, non-root user, healthcheck; ECS task mirrors it; IMDSv2-only, SSH-less (Session Manager) nodes | `Dockerfile`, `infra/terraform/modules/ecs-app` | image inspection; SEC-015 session logging IaC |

## 6. Where the rest lives

* Deployment topology & gate items executed as IaC: [`infra/terraform/README.md`](../../infra/terraform/README.md)
* Threats × controls, with honest tested/pending flags: [threat-control-matrix.md](threat-control-matrix.md)
* C/I/A one-pager: [cia-controls-table.md](cia-controls-table.md)
* What is DONE vs OPEN, with owners and dates: [security-roadmap.md](security-roadmap.md)
* Data inventory & flows: [data-inventory.md](data-inventory.md)
* Items not executable from this repository: README →
  "Implement later (cloud & organisational)".
