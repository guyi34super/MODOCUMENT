# Threat–control matrix — SEC-040

Preventive and detective controls per threat, with the artifact that proves
each. **Honesty flags:** ✅ = control exists and is covered by an automated
test in this repo; 🏗️ = control is written as IaC/config, not yet applied to
any cloud account; ⏳ = designed/documented, pending execution (owner in the
[roadmap](security-roadmap.md) and the README "Implement later" table).
Last reviewed: 2026-10-04.

| Threat | Preventive controls | Detective controls | Status detail |
|---|---|---|---|
| **Credential stuffing / brute force** | Per-IP + per-email auth rate limits (429) ✅; account lockout with exponential backoff ✅; constant-cost dummy hash — no enumeration/timing oracle ✅; password policy incl. breached-list ✅; WAF auth-path rate rule 🏗️ | `LOGIN_FAILED`/`ACCOUNT_LOCKED` audit events ✅; CloudWatch metric filter on `ACCOUNT_LOCKED` + burst alarm → SNS 🏗️ | Tests: `AccountLockoutIntegrationTest`, `LoginLockoutPolicyTest`, `FixedWindowRateLimiterTest`. Alarm: `infra/terraform/modules/security-monitoring`. Alert channels arrive with ws18. |
| **Token theft / session hijack** | 15-min access TTL ✅; httpOnly refresh cookie scoped to `/api/auth/refresh`, rotating family with 12h idle / 7d absolute bounds ✅; jti revocation on logout, token-version bump on password change ✅; no token ever in a URL (OIDC lands on `/login/complete`) ✅; HSTS + TLS 1.2+ ✅/🏗️ | Refresh reuse is refused (rotation) ✅; `TOKEN_VERSION_BUMPED` audit ✅ | Tests: `AuthFlowIntegrationTest`, `RevocationValidatorTest`, `JwtServiceTest`, `TrustedProxyIntegrationTest`. Refresh-reuse *alerting* (vs refusal) is ⏳ (ws18 metric filter candidate). |
| **Cross-tenant access (bank A reads bank B)** | App-level owner/bank scoping on every query ✅; PostgreSQL RLS on every BankId table, fail-closed, `modocument_app` NOBYPASSRLS ✅; per-bank admin boundaries ✅ | Append-only audit with BankId on every row ✅; denied-authorization events audited ✅ | Tests: `TenantIsolationGatePgIT`, `RlsPolicyCoveragePgIT` (real PostgreSQL in CI), `CrossUserAccessTest`, `ExtractionCrossUserAccessTest`. |
| **Malicious insider export / data theft** | RBAC: exports and AI spend denied to READ_ONLY/APPROVER ✅; PII masked in every export channel for every role (C2) ✅; maker/checker on user-security changes, self-approval 409 ✅; author ≠ releaser SoD ✅; DB-export credentials one-shot, never persisted ✅ | Every export/DSR action audited (`DSR_EXPORTED`, `USER_LIST_EXPORTED`, denied events) ✅; SoD scan with `SOD_VIOLATION_FOUND` ✅; access review export ✅ | Tests: `MaskingGateC2Test`, `MakerCheckerIntegrationTest`, `DocReleaseSodIntegrationTest`, `RoutePermissionCoverageTest`, `DatabaseExportServiceTest`. Volume-anomaly alerting on exports is ⏳. |
| **Ransomware / destructive attack** | Immutable backups: vault lock (compliance) + 90d retention + cross-region copy 🏗️; S3 Object Lock 7y log archive 🏗️; RDS PITR 🏗️; append-only audit table (DB-enforced) ✅; SSH-less, IMDSv2-only nodes 🏗️ | GuardDuty 🏗️; VPC flow logs 🏗️; CloudTrail with log-file validation 🏗️ | `AuditAppendOnlyPgIT` is the tested piece. Everything cloud-side is written in `infra/terraform/modules/{backups,security-monitoring}` but **not applied**; restore drill is gate D6 ⏳. |
| **Supply chain (dependency/image/CI compromise)** | Locked dependency manifests (Maven, package-lock) ✅; CI builds from source on every PR ✅; ECR immutable tags + scan-on-push 🏗️; no static cloud keys — GitHub OIDC, branch-pinned production role 🏗️; branch protection ⏳ (ws19) | ECR scan findings 🏗️; CI failures ✅ | SAST/secret-scanning and the dependency-audit gate are ws19 deliverables — not claimed here. |
| **AI prompt injection / model abuse** | PII tokenization before prompts (fail-closed default) ✅; names-only automapper, failing-rows-only chat edit ✅; structured output + plan validation (nothing invented reaches documents) ✅; invented mapping targets discarded ✅; per-user LLM quota + vision page budget ✅ | Extraction LLM calls land in the queryable extraction audit trail ✅ | Tests: `AutoMappingPromptTest`, `ChatEditScopeResolverTest`, `LlmStructuredJsonParserTest`, `AiDocumentComposerTest`, `LlmQuotaRetryAfterIntegrationTest`. **Residual, by design:** document-to-table extraction, vision OCR and `/api/aifiles/send` send document content to the LLM — their job; a hostile *document* can steer its own extraction output, mitigated by validation + human review stages, accepted under gate C11 ⏳ (written acceptance pending, see README later-list). |
| **Data in logs leak** | Redacting logback converters (patterns + structured JSON + exceptions) ✅; `LogSafe` discipline at call sites ✅; DB-export credentials and per-run extraction credentials never logged ✅; Performance Insights deliberately OFF (would sample SQL with PII) 🏗️ | Canary test fails the build if redaction regresses ✅ | Tests: `LogRedactionCanaryTest`, `StructuredLogRedactionTest`. Note: redaction is pattern-based — free-text PII with no recognizable shape can pass; mitigated by the masking/tokenization layers above the log line. |
| **Spoofed client identity (rate-limit/audit evasion)** | App port unpublished; nginx overwrites `X-Forwarded-*` (trusted proxy) ✅; CloudFront-only origin + origin-verify header 🏗️ | Audit rows carry the proxy-asserted IP + requestId ✅ | Tests: `TrustedProxyIntegrationTest`. |
| **Malicious file upload** | Content gate: magic-byte/structure checks, not extension trust ✅; SHA-256 dedup/integrity ✅; size limits ✅; parsing in a non-root container ✅ | Upload + parse failures audited/logged ✅ | Tests: `UploadContentGateSec030Test`, `UploadContentCheckerTest`, `IntegrityGateC6Test`. AV scanning is **not implemented** — compensating controls: content-type gate, no re-serving of uploads to other users, non-root parse. Decision owner in roadmap. |
| **Web-extraction legal/abuse exposure** | Domain allow-list, robots.txt fail-closed, crawl-delay honored, per-host rate limiter, honest UA, 401/402/403 terminal, no CAPTCHA/login bypass ✅; `termsAcknowledged` required ✅ | Every fetch/block/dedup/retry in the queryable extraction audit trail ✅ | Tests: `CompliantWebAccessTest`, `RobotsTxtParserTest`. |

## Detective-control test coverage, summarized honestly

* **Tested in CI today:** audit append-only + enrichment (`AuditAppendOnlyPgIT`,
  `JpaAuditRecorderTest`), denied-access auditing, retention-sweep evidence
  events (`RetentionPurgeGateC5Test`), redaction canaries, refresh-rotation
  refusal.
* **Written but unapplied (🏗️):** GuardDuty, CloudTrail, flow logs, the
  CloudWatch alarms and the `ACCOUNT_LOCKED` metric filter — they exist as
  Terraform in `infra/terraform/` and have **no runtime evidence yet**.
* **Pending (⏳):** alert channel wiring (ws18), SIEM reference test
  (SEC-031), export-volume anomaly detection, refresh-reuse alerting, the
  restore drill (D6), external pen test (E4).

Related: [security-feature-catalogue.md](security-feature-catalogue.md) ·
[cia-controls-table.md](cia-controls-table.md) ·
[security-roadmap.md](security-roadmap.md)
