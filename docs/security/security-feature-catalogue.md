# Security feature catalogue — SEC-027

The one answer that frames all others: **every security control MoDocument
implements, with the code, test and document that prove it.** Everything in
the tables exists in this repository today. Items that are *designed but not
executed* (cloud build-out, external exercises) are marked 🏗️ (Terraform
written, not applied) or listed in the README "Implement later" table.
Known gaps found while re-checking this catalogue are listed in §9.

Last reviewed: **2026-10-19** against `main` at `23647fe` (after PR #66):
phase 2 (v1.1.0), the optimization release v1.2.0 and the document-creation
work (ws31–ws34). Reviewer: Agent A7 for the SEC-027 evidence pack
(`docs/compliance/controls/SEC-027.md`); human sign-off required.

Paths are relative to the repo root; `…/` abbreviates
`src/main/java/com/modocument/`. Tests live under `src/test/java/` (agent
tests under `agent/src/test/java/`). Every path and test class named here was
checked to exist by the SEC-027 test (T01).

## 1. Identity and authentication

| Control | Feature | Code | Test / evidence |
|---|---|---|---|
| B1 | JWT bearer auth on every endpoint except the login entry points, `/actuator/health`, the agent enrolment route and the public synthetic probe; 15-min access TTL; rotating 7-day httpOnly refresh cookie scoped to `/api/auth/refresh`; logout revokes the jti and every refresh token; a password change bumps the token version | `…infrastructure/security/JwtService.java`, `…application/usecase/AuthUseCase.java`, `…infrastructure/security/RevocationValidator.java` | `AuthFlowIntegrationTest`, `JwtServiceTest`, `RevocationValidatorTest` |
| B1 | Fail-fast secrets: the app refuses to boot with a missing, short or known-default `MODOCUMENT_JWT_SECRET`; secrets can be files (`<NAME>_FILE`) | `…api/config/SecurityConfig.java`, `…infrastructure/security/SecretFileEnvironmentPostProcessor.java` | `SecretFileEnvironmentPostProcessorTest` |
| B2/B3 | OIDC SSO (Entra ID / Keycloak): the IdP authenticates (incl. its MFA), the app issues its own tokens; password login stays break-glass; no IdP path into the platform bank | `…infrastructure/security/oidc/`, `docker/keycloak/modocument-realm.json` | `KeycloakRealmConfigTest`, `AuthProvidersConfiguredTest`; [identity-providers.md](identity-providers.md) |
| B3 | Local TOTP MFA: AES-256-GCM-encrypted secrets, Argon2id-hashed recovery codes shown once, 5-min challenge tokens; MFA required for `ADMIN` (`require-for-admin`, default true in `src/main/resources/application.yml`; compose and the `local` profile switch it off) and always for `PLATFORM_ADMIN` | `…application/usecase/MfaUseCase.java`, `…infrastructure/security/MfaSecretCipher.java`, `…infrastructure/security/totp/Totp.java` | `MfaFlowIntegrationTest`, `MfaSecretCipherTest`, `TotpTest` |
| B4 | Lockout after 5 wrong passwords, exponential lock up to 30 min; constant-cost dummy Argon2id match for unknown or locked accounts (no user enumeration) | `…application/usecase/AuthUseCase.java` | `AccountLockoutIntegrationTest`, `LoginLockoutPolicyTest` |
| SEC-021 | Password policy (min length 12, breached-list and e-mail checks); Argon2id hashing with transparent BCrypt upgrade | `…api/config/SecurityConfig.java` | `UserSecurityServiceTest`; [crypto-standard.md](crypto-standard.md) |
| SEC-002/007 | Rate limits: per-IP and per-e-mail on login; per-user hourly buckets on every expensive route (uploads, ingestion, exports, doc generation, DSR, MFA) chosen from the matched route pattern; per-user LLM quota (60/h) → 429 with `Retry-After` | `…infrastructure/ratelimit/ApiRateLimitInterceptor.java`, `…infrastructure/ratelimit/LlmQuotaInterceptor.java` | `ApiRateLimitIntegrationTest`, `LlmQuotaRetryAfterIntegrationTest`, `FixedWindowRateLimiterTest` |

## 2. Authorization, roles and dual control

| Control | Feature | Code | Test / evidence |
|---|---|---|---|
| B8 / SEC-012 | Six roles (`ADMIN`, `APPROVER`, `READ_WRITE`, `READ_ONLY`, `PLATFORM_ADMIN`, and `API_CLIENT` for machines) × 41 permissions; **every route** mapped to a permission | `…domain/model/Role.java`, `…domain/model/Permission.java` | `RolePermissionMatrixTest` (pins [role-permission-matrix.csv](role-permission-matrix.csv)), `RoutePermissionCoverageTest`; [role-catalogue-and-sod.md](role-catalogue-and-sod.md) |
| B7 / SEC-023 | Maker/checker for 17 change types (user create/role/disable/password reset/MFA reset, template update/delete, bank and platform security settings, bank create/suspend/break-glass admin, integration connections, outbound flows, API clients, agents, egress rules): a **different** holder of `change:approve` approves; self-approval is 409 and audited | `…domain/model/ChangeType.java`, `…application/usecase/PendingChangeUseCase.java`, `…application/security/ChangeAudience.java` | `MakerCheckerIntegrationTest`, `SettingsMakerCheckerIntegrationTest`, `PendingChangeAudienceTest`; [maker-checker.md](maker-checker.md) |
| B5/B6 | Joiner/mover/leaver: admin-created accounts only, bootstrap admin seeded once, role change and disable under dual control | `…application/usecase/UserAdminUseCase.java`, `…infrastructure/bootstrap/BootstrapAdminRunner.java` | `UserAdminIntegrationTest`, `BootstrapAdminRunnerTest`; [jml-runbook.md](jml-runbook.md) |
| C10 / SEC-024 | Separation of duties: document releaser ∉ {author, submitter}; SoD policy as data plus a nightly scan | `…application/security/SodPolicy.java`, `…infrastructure/sod/SodScanJob.java` | `SodPolicyTest`, `DocReleaseSodIntegrationTest` |
| Access review | Reviewable user/role export (itself audited, `ACCESS_REVIEW_GENERATED`) | `…application/usecase/AccessReviewUseCase.java` | `AccessReviewUseCaseTest` |
| Platform owner (ws21) | One platform bank; `PLATFORM_ADMIN` exists only there and has **no domain permission**; platform permissions need the role **and** a token of the platform bank; cross-bank reads return counts only; two-admin minimum; DB triggers back the role rule | `…application/security/RoleAssignmentPolicy.java`, `…application/usecase/PlatformBankUseCase.java`, `src/main/resources/db/migration/V210__ws21_platform_bank.sql` | `PermissionCheckerPlatformGuardTest`, `PlatformBoundaryQaTest`, `PlatformRoleTriggerPgIT`, `RoleAssignmentPolicyTest`; [platform-owner.md](../platform/platform-owner.md) |

## 3. Tenant isolation and data protection

| Control | Feature | Code | Test / evidence |
|---|---|---|---|
| C1 | Two layers: owner/bank scoping in every query **and** PostgreSQL row-level security on every `BankId` table, fail-closed; the app connects as `modocument_app` (NOBYPASSRLS); an `afterMigrate` callback applies the policy to every new tenant table | `src/main/resources/db/migration/afterMigrate__rls_enforce.sql`, `…infrastructure/persistence/` | `TenantIsolationGatePgIT`, `RlsPolicyCoveragePgIT`, `CrossUserAccessTest` |
| C2 / PRIV-004/005 | PII masking: per-field `PiiKind`, channel × role policy at each channel's choke point (screen, report, export, audit viewer, integration outbound) | `…application/privacy/PiiMaskingService.java`, `…application/privacy/MaskingPolicy.java` | `MaskingGateC2Test`, `MaskingMatrixDocTest`; [masking-matrix.md](masking-matrix.md) |
| C4 / SEC-028 | Encryption at rest and in transit — full inventory in `docs/compliance/controls/SEC-028.md` | see [crypto-standard.md](crypto-standard.md) | `DatabaseTlsConfigTest`, `TrustedProxyIntegrationTest`, `ConnectorSecretVaultTest` |
| C5 / PRIV-013 | Retention: daily per-bank purge (30-day customer data), housekeeping of tokens, integration history and idempotency keys | `…infrastructure/retention/RetentionPurger.java`, `…infrastructure/housekeeping/` | `RetentionPurgeGateC5Test`, `HousekeepingJobTest`; [retention-and-dsr.md](retention-and-dsr.md) |
| C6 / SEC-030 | Integrity: DB constraints, SHA-256 upload hashes, upload content gate (magic bytes) | `…infrastructure/file/` | `IntegrityGateC6Test`, `UploadContentGateSec030Test` |
| D2 / PRIV-002 | Export caps per role with a stated reason above the cap; every export, refusal and override audited and alerted | `…infrastructure/export/ExportGuard.java` | `ExportCapGateD2Test`, `ExportGuardTest`; [alerting-and-export-caps.md](alerting-and-export-caps.md) |
| C12 | Data-subject rights per bank (search, export, erase — also clears the bank's AI result cache) | `…application/usecase/DataSubjectUseCase.java` | `DataSubjectRightsGateC12Test` |
| v1.2.0 | **S3 file store**: one `FileStore` port for uploads, exports and document assets; S3 backend with SSE-KMS on every PUT and keys under `bank/{id}/`; path traversal refused 🏗️ (bucket) | `…infrastructure/file/store/S3FileStore.java`, `…infrastructure/file/store/FileStoreKeys.java`, `infra/terraform/modules/file-store/main.tf` | `S3FileStoreTest`, `FileStoreTraversalQaTest`, CI job `storage-s3` |

## 4. Audit trail and monitoring

| Control | Feature | Code | Test / evidence |
|---|---|---|---|
| C7 / SEC-033/035 | Append-only `AuditEvent`: UUIDv7 ids; actor, bank, role, session jti, request id, IP, old/new values; success events commit with the business change, refusals commit on their own; UPDATE/DELETE/TRUNCATE revoked from the app role **and** blocked by triggers | `…infrastructure/audit/JpaAuditRecorder.java`, `src/main/resources/db/migration/V14__audit_event.sql` | `JpaAuditRecorderTest`, `AuditAppendOnlyPgIT`; [audit-events.md](audit-events.md) |
| v1.2.0 | **Audit viewer**: `GET /api/admin/audit` (filters, keyset paging, CSV export up to 50,000 rows, masked) and `/api/platform/audit`; pages `/admin/audit`, `/platform/audit`; permission `audit:read`; viewing and exporting are themselves audited (`AUDIT_LOG_VIEWED`, `AUDIT_LOG_EXPORTED`) | `…api/controller/AuditLogController.java`, `frontend/src/pages/AuditLogPage.jsx` | `AuditLogApiTest`, `AuditViewAuditQaTest` |
| v1.2.0 | **Read audit** `SENSITIVE_DATA_VIEWED` (once per actor, entity and hour); aggregated `ACCESS_DENIED`; gap-fill of template, upload, ingestion-edit and document events | `…application/audit/AuditActions.java` | `AuditTrailGapsTest`, `JpaAuditRecorderThrottleTest`, `AccessDeniedBurstAlertTest` |
| E9 / SEC-034 | One request id per request: honoured or minted, on every log line (MDC), echoed in `X-Request-Id`, in error bodies and on every audit row; carried onto async threads | `…infrastructure/security/RequestIdFilter.java`, `src/main/resources/logback-spring.xml` | `RequestTraceabilityE9Test` |
| SEC-026 | Log redaction on every log path, including exceptions and structured JSON | `…infrastructure/logging/RedactingConverter.java`, `…infrastructure/logging/RedactingJsonMembersCustomizer.java` | `LogRedactionCanaryTest`, `StructuredLogRedactionTest` |
| D1 / E6 | Alert rules over the audit trail (immediate and windowed), delivered to log, HMAC-signed webhook and e-mail; bank-scoped SIEM feed; synthetic probe | `…infrastructure/alerting/`, `…infrastructure/siem/` | `AlertingGateD1Test`, `SiemFeedE6Test`, `SyntheticProbeTest`; [siem-event-schema.md](siem-event-schema.md) |

## 5. Settings guardrails (phase 2, ws20)

| Feature | Code | Test / evidence |
|---|---|---|
| Every setting in a code catalogue with type, scope, tier and **hard limits** from the go-live gate; no value can leave them; `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true` (staging/production) refuses to boot with an out-of-limit default | `…infrastructure/settings/SettingDefinitions.java`, `…infrastructure/settings/SettingsStartupCheck.java` | `SettingsGuardrailTest`, `SettingsStartupCheckTest`, `SettingsCatalogueDocTest`; [settings catalogue](../settings/catalogue.md) |
| SECURITY-tier settings need maker/checker; platform-set settings are read-only for banks; optimistic versions (409 on a stale write) | `…infrastructure/settings/SettingsStore.java` | `SettingsMakerCheckerIntegrationTest`, `PlatformControlledSettingsIntegrationTest` |
| Change history: every setting change is audited with old and new values and shown in the audit viewer category "Settings" | `…application/audit/AuditActions.java` | `AuditLogApiTest` |

## 6. Integration hub security (phase 2, ws25/ws26)

| Feature | Code | Test / evidence |
|---|---|---|
| **API clients** for bank systems: OAuth2 client credentials, API keys or mTLS; secrets stored only as Argon2id hashes; short-lived client tokens (`integration.clientTokenTtl`, 5–15 min); allowed methods platform-set per bank; creation under maker/checker | `…infrastructure/security/client/ApiClientAuthenticator.java`, `…infrastructure/security/client/ClientTokenService.java`, `src/main/resources/db/migration/V260__ws26_machine_identity.sql` | `ApiClientProvisioningTest`, `ApiClientAuthPgIT`, `IntegrationHubSecurityQaTest` |
| **mTLS** for machine clients: optional ALB mutual-TLS listener with a CA trust store 🏗️; nginx strips client-certificate headers on the browser port; the certificate's SHA-256 fingerprint binds the client (RFC 8705) | `…infrastructure/security/client/MtlsClientCertificates.java`, `infra/terraform/modules/ecs-app/mtls.tf`, `frontend/nginx.conf` | `RestApiMtlsTest`, `AgentMtlsTest`; `scripts/check-nginx-headers.sh` |
| **Connector Agent** (on-premises): no inbound port, dials out over HTTPS only; enrolment by a single-use 256-bit token valid 15 min, stored as a SHA-256 hash; registration under maker/checker; bank credentials never leave the bank host | `…application/usecase/AgentEnrolmentService.java`, `agent/src/main/java/com/modocument/agent/cloud/CloudClient.java` | `AgentEnrolmentTokenTest`, `AgentProtocolSecurityChainTest`; [agent.md](../integration/agent.md) |
| **SecretVault**: connection secrets sealed with AES-256-GCM under `MODOCUMENT_CONNECTOR_KEY`, a **per-bank key derived by HKDF-SHA256**, the bank id and key version as associated data, key rotation with retired keys | `…infrastructure/integration/secrets/ConnectorSecretVault.java` | `ConnectorSecretVaultTest`; [secrets.md](../integration/secrets.md) |
| **Egress rules**: outbound targets must pass the host policy (no private, loopback or metadata addresses; every redirect re-checked); a bank's private destination needs an egress rule approved by a second platform admin | `…infrastructure/net/OutboundHostPolicy.java`, `…infrastructure/integration/egress/BankEgressGuard.java`, `…application/usecase/EgressRuleUseCase.java` | `OutboundHostPolicyTest`, `BankEgressGuardTest`, `EgressRuleIntegrationTest`, `EgressRulePgIT` |
| Outbound PII masking on by default (`integration.outboundPiiMasking`, SECURITY tier); webhooks HMAC-signed; SFTP host keys pinned by fingerprint; plain `http://` targets refused in deployed environments | `connector-core/src/main/java/com/modocument/connector/core/webhook/WebhookConnector.java`, `connector-core/src/main/java/com/modocument/connector/core/sftp/SftpConnector.java`, `…infrastructure/integration/connector/IntegrationConnectorProperties.java` | `WebhookIntegrationConnectorTest`, `SftpConnectorTest`, `IntegrationSettingsTest` |

## 7. AI controls

Full detail: [ai-controls.md](ai-controls.md).

| Control | Feature | Code | Test / evidence |
|---|---|---|---|
| C9 | One gateway for every model call; one bank per prompt; pseudonymised prompts; raw identifiers in an assembled prompt are blocked; a build guard fails on direct chat-model use | `…infrastructure/ai/AiGateway.java` | `AiGatewayC9Test`, `NoDirectChatModelAccessGuardTest` |
| C10 | Output scanning on every completion (strict or lenient per purpose) | `…infrastructure/ai/AiOutputScanner.java` | `AiOutputScannerTest`, `AiOutputPolicyC10Test` |
| C8 | Local model by default; vision OCR off by default; prompts and completions never stored or logged | `…infrastructure/ai/SpringAiConfig.java` | `AiGatewayC8LoggingTest` |
| ws23 | Per-bank AI switch (`ai.enabled`, SECURITY tier), circuit breaker, timeouts, code fallback | `…infrastructure/ai/AiCircuitBreaker.java`, `…infrastructure/ai/AiPreflight.java` | `AiCircuitBreakerTest`, `AiPreflightTest`, `IngestionParseStepImplTest` |
| ws28 | Bank-scoped AI result cache (RLS), purged with customer data and by a DSR erase | `…infrastructure/ai/AiResultCache.java` | `AiResultCacheRlsPgIT` |

## 8. Document creation (ws31–ws34)

| Feature | Code | Test / evidence |
|---|---|---|
| **Validation before download**: a document whose template has validation on (the default, `ValidationEnabled` defaults to true in V334) generates only when `VALIDATED` (409 `NOT_VALIDATED`); switching validation off is audited (`DOC_VALIDATION_SETTING_CHANGED`) | `…doccreation/usecase/DocInstanceUseCase.java`, `src/main/resources/db/migration/V334__ws34_doc_validation_switch.sql` | `DocValidationApiTest`, `DocValidationSwitchApiTest` |
| **PII masking in generated documents**: the REPORT masking channel is applied to the values drawn into a generated PDF; stored values and the owner's preview stay raw | `…doccreation/usecase/DocInstanceUseCase.java` | `MaskingGateC2Test` (`reportChannelMasksPiiShapesInGeneratedDocuments`) |
| Flow records used to fill documents are bank-scoped (RLS) and masked per role; searches audited with a query hash and count only | `…doccreation/usecase/TemplateRecordUseCase.java` | `FlowRecordsApiTest`, `DocPdfTemplateRlsPgIT` |
| Generated PDFs are refused unless identical to the template outside the filled regions (exactness check) | `…doccreation/usecase/DocInstanceUseCase.java` | `DocCreationPdfApiTest` |
| AI help for PDF templates sees masked text runs only and answers with ids that are re-validated | `…doccreation/pdftemplate/AiRegionAdvisor.java` | `AiRegionAdvisorTest` |

## 9. Platform, pipeline and supply chain

| Control | Feature | Code | Test / evidence |
|---|---|---|---|
| Trusted proxy | nginx is the single entry and overwrites `X-Forwarded-*`; the app port is never published | `frontend/nginx.conf`, `docker-compose.yml` | `TrustedProxyIntegrationTest` |
| E1 / SEC-018 | Security headers on SPA and API (CSP, HSTS, nosniff, frame-ancestors) | `frontend/security-headers.conf`, `…api/config/SecurityConfig.java` | `HeaderContractTest`; [web-hardening.md](web-hardening.md) |
| SUP-S04 | Non-root containers (app uid 10001, nginx uid 101), capabilities dropped | `Dockerfile`, `frontend/Dockerfile` | [web-hardening.md](web-hardening.md) |
| SUP-S05 / SEC-042 | CI: build and tests, PostgreSQL ITs, gitleaks (full history), Semgrep with repo rules, dependency review, `npm audit`; CycloneDX SBOMs; Trivy-gated release images built once and deployed by digest; Dependabot; all 58 action references SHA-pinned; OIDC deploy roles per environment 🏗️ | `.github/workflows/ci.yml`, `.github/workflows/security.yml`, `.github/workflows/sbom.yml`, `.github/workflows/release.yml`, `.github/dependabot.yml`, `infra/terraform/modules/ci-oidc/main.tf` | `docs/compliance/evidence/A7-runs.md` (R10); [branch-protection.md](branch-protection.md) |
| SEC-025 | Release pipeline with staging soak and production reviewers; rollback workflow; expand-only migrations; deployed version audited | `.github/workflows/release.yml`, `.github/workflows/deploy.yml`, `…infrastructure/release/DeployedVersionRecorder.java` | `MigrationPolicyTest`, `DeployedVersionRecorderTest`; [runbook](../release/runbook.md) |

### Gaps found in this review (2026-10-19)

These are open; owners and dates are on the control pages in `docs/compliance/controls/`.

1. GitHub Actions has not started any job since PR #63 (16 Oct 2026); PRs #63–#66 merged on a local gate without review (SEC-025, SUP-S05).
2. No hash chain or verification job on the audit trail; a database superuser can bypass the append-only triggers undetected (SEC-035).
3. No build-provenance attestation; release images are built with `--provenance=false` (SUP-S05).
4. The CI frontend SBOM omits `react`, `react-dom` and `scheduler`; no licence gate in CI (SEC-042).
5. Internal hop ALB → task is plain HTTP inside the VPC; backup vaults and the log archive use AWS-managed keys, not own CMKs; KMS keys have no explicit key policy (SEC-028, SEC-029).
6. nginx does not log or set the request id; structured JSON logs are opt-in and the ECS task does not switch them on (SEC-034).
7. The staging CI deploy role trusts any branch of the repository (`repo:<owner>/<repo>:*`) (SUP-S05, SUP-S07).
