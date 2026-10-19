# Threat–control matrix — SEC-040

Preventive and detective controls per threat, with the artifact that proves
each. **Honesty flags:** ✅ = control exists and is covered by an automated
test in this repo; 🏗️ = control is written as IaC/config, not yet applied to
any cloud account; ⏳ = designed/documented, pending execution (owner in the
[roadmap](security-roadmap.md) and the README "Implement later" table).

| Version | Date | Change | Author | Approval |
|---|---|---|---|---|
| 1.0 | 2026-10-09 | First matrix (integration of workstreams 16–20) | Bhavish Nobeen | — |
| 1.1 draft | 2026-10-19 | Re-verified for the technology-risk pack: per-threat "tested alert" check added (SEC-040 acceptance), CI and scan status corrected, egress status corrected | Bhavish Nobeen (owner); drafted for the pack | Reezvi Pydiah — pending |

Review cycle: monthly (02 workbook SEC-040 Evidence Register). This matrix is
one half of SEC-040. The other half, the **access-control matrix** (role ×
permission), is `docs/security/role-permission-matrix.csv`: it is generated
from `RolePermissions` and pinned byte-for-byte by `RolePermissionMatrixTest`,
so every release carries the matrix the code enforces (run 2026-10-19: pass,
`docs/compliance/evidence/A5-scans-and-tests.md`). The role descriptions are
in [role-catalogue-and-sod.md](role-catalogue-and-sod.md).

| Threat | Preventive controls | Detective controls | Status detail |
|---|---|---|---|
| **Credential stuffing / brute force** | Per-IP + per-email auth rate limits (429) ✅; account lockout with exponential backoff ✅; constant-cost dummy hash — no enumeration/timing oracle ✅; password policy incl. breached-list ✅; WAF auth-path rate rule 🏗️ | `LOGIN_FAILED`/`ACCOUNT_LOCKED` audit events ✅; windowed alert rules (failures per user / per IP in 5 min) and an immediate `ACCOUNT_LOCKED` alert → log + webhook + e-mail, recorded in `AlertLog` ✅ (ws18); CloudWatch metric filter on `ACCOUNT_LOCKED` + burst alarm → SNS 🏗️ | Tests: `AccountLockoutIntegrationTest`, `LoginLockoutPolicyTest`, `FixedWindowRateLimiterTest`, `AlertingGateD1Test`, `AlertRuleCatalogueTest`. Alarm: `infra/terraform/modules/security-monitoring`. Behind the ALB the per-IP keys see the real client via nginx trusted-proxy mode (realip from the ALB, `CloudFront-Viewer-Address` in production) 🏗️ — [web-hardening.md](web-hardening.md) §5e. |
| **Token theft / session hijack** | 15-min access TTL ✅; httpOnly refresh cookie scoped to `/api/auth/refresh`, rotating family with 12h idle / 7d absolute bounds ✅; jti revocation on logout, token-version bump on password change ✅; no token ever in a URL (OIDC lands on `/login/complete`) ✅; HSTS + TLS 1.2+ ✅/🏗️ | Refresh reuse is refused (rotation) ✅ and audited `TOKEN_REFRESH_REUSE_DETECTED`, which raises an immediate CRITICAL alert (ws18 `refresh-token-reuse` rule) ✅; `TOKEN_VERSION_BUMPED` audit ✅ | Tests: `AuthFlowIntegrationTest`, `RevocationValidatorTest`, `JwtServiceTest`, `TrustedProxyIntegrationTest`, `AuthUseCaseTest`, `AlertRuleCatalogueTest`, `AlertingGateD1Test`. |
| **Cross-tenant access (bank A reads bank B)** | App-level owner/bank scoping on every query ✅; PostgreSQL RLS on every BankId table, fail-closed, `modocument_app` NOBYPASSRLS ✅; per-bank admin boundaries ✅ | Append-only audit with BankId on every row ✅; denied-authorization events audited ✅ | Tests: `TenantIsolationGatePgIT`, `RlsPolicyCoveragePgIT` (real PostgreSQL in CI), `CrossUserAccessTest`, `ExtractionCrossUserAccessTest`. |
| **Malicious insider export / data theft** | RBAC: exports and AI spend denied to READ_ONLY/APPROVER ✅; PII masked in every export channel for every role (C2) ✅; maker/checker on user-security changes, self-approval 409 ✅; author ≠ releaser SoD ✅; DB-export credentials one-shot, never persisted ✅ | Every export/DSR action audited (`EXPORT_RUN`, `EXPORT_BLOCKED`, `EXPORT_OVER_CAP_WITH_REASON`, `DSR_EXPORTED`, `USER_LIST_EXPORTED`, denied events) ✅; over-cap attempts alerted immediately, export bursts by windowed rules ✅ (ws18); SoD scan with `SOD_VIOLATION_FOUND` ✅; access review export ✅ | Tests: `MaskingGateC2Test`, `MakerCheckerIntegrationTest`, `DocReleaseSodIntegrationTest`, `RoutePermissionCoverageTest`, `DatabaseExportServiceTest`, `ExportCapGateD2Test`, `ExportGuardTest`, `AlertingGateD1Test`. Preventive add-on (ws18): per-role export row caps — above the cap 403 `EXPORT_CAP_EXCEEDED` unless a reason is stated ✅; every export path shares the per-user `exports` rate-limit bucket ✅ (`ApiRateLimitIntegrationTest`). |
| **Ransomware / destructive attack** | Immutable backups: vault lock (compliance) + 90d retention + cross-region copy 🏗️; S3 Object Lock 7y log archive 🏗️; RDS PITR 🏗️; append-only audit table (DB-enforced) ✅; SSH-less, IMDSv2-only nodes 🏗️ | GuardDuty 🏗️; VPC flow logs 🏗️; CloudTrail with log-file validation 🏗️ | `AuditAppendOnlyPgIT` is the tested piece. Everything cloud-side is written in `infra/terraform/modules/backups` and `infra/terraform/modules/security-monitoring` but **not applied**; restore drill is gate D6 ⏳. |
| **Supply chain (dependency/image/CI compromise)** | Locked dependency manifests (Maven, package-lock) ✅; CI builds from source on every PR ✅; every GitHub Action pinned by commit SHA, least-privilege `permissions` ✅; Dependabot (Maven, npm, Actions, Docker, Terraform) ✅; CODEOWNERS second-person review ✅ (enforced once branch protection is switched on ⏳); ECR immutable tags + scan-on-push 🏗️; no static cloud keys — GitHub OIDC, branch-pinned production role 🏗️ | gitleaks full-history ✅, Semgrep SAST incl. custom crypto/AI-gateway/env-var rules ✅, `npm audit --audit-level=high` ✅, dependency review ⏳ (cannot run on the private repository until Code Security is enabled), weekly Trivy image scan ✅ (last run 2026-10-12: 11 critical + 41 high fixable findings in the app image, open — issue #52), ZAP baseline ✅ (2026-10-13: 0 fail, 1 warning), CycloneDX SBOM per release ✅ (ws19); ECR scan findings 🏗️; CI failures ✅ — but no CI job has started since at least 2026-10-17 ⏳ | Workflows in `.github/workflows/`, rules in `.semgrep/modocument.yml`, `.gitleaks.toml`; [branch-protection.md](branch-protection.md). The seeded-vulnerable-PR drill (A8) and applying the branch-protection settings in GitHub are ⏳ (repo-admin actions). |
| **AI prompt injection / model abuse** | One AI gateway for every model call — pseudonymised prompts, one-bank rule, output scanning, local model by default (ws15) ✅; PII tokenization before prompts (fail-closed default) ✅; names-only automapper, failing-rows-only chat edit ✅; structured output + plan validation (nothing invented reaches documents) ✅; invented mapping targets discarded ✅; per-user LLM quota + vision page budget ✅ | Extraction LLM calls land in the queryable extraction audit trail ✅ | Tests: `AiGatewayC9Test`, `AiOutputPolicyC10Test`, `NoDirectChatModelAccessGuardTest`, `AutoMappingPromptTest`, `ChatEditScopeResolverTest`, `LlmStructuredJsonParserTest`, `LlmQuotaRetryAfterIntegrationTest`; [ai-controls.md](ai-controls.md). **Residual, by design:** document-to-table extractionand vision OCR send document content to the LLM (the `/api/aifiles/send` passthrough was removed in 1.2.0) — their job; a hostile *document* can steer its own extraction output, mitigated by validation + human review stages, accepted under gate C11 ⏳ (written acceptance pending, see README later-list). |
| **Data in logs leak** | Redacting logback converters (patterns + structured JSON + exceptions) ✅; `LogSafe` discipline at call sites ✅; DB-export credentials and per-run extraction credentials never logged ✅; Performance Insights deliberately OFF (would sample SQL with PII) 🏗️ | Canary test fails the build if redaction regresses ✅ | Tests: `LogRedactionCanaryTest`, `StructuredLogRedactionTest`. Note: redaction is pattern-based — free-text PII with no recognizable shape can pass; mitigated by the masking/tokenization layers above the log line. |
| **Spoofed client identity (rate-limit/audit evasion)** | App port unpublished; nginx overwrites `X-Forwarded-*` (trusted proxy) ✅; behind the ALB nginx's trusted-proxy mode takes the client address / proto only from the ALB's CIDR (realip, recursive XFF; `CloudFront-Viewer-Address` via the edge origin request policy in production) ✅ code / 🏗️ deploy; CloudFront-only origin + origin-verify header 🏗️ | Audit rows carry the proxy-asserted IP + requestId ✅ | Tests: `TrustedProxyIntegrationTest`; nginx spoofing matrix in all three proxy modes against the real image ([web-hardening.md](web-hardening.md) §5e). Live confirmation on the first AWS deploy ⏳. Client-sent `Forwarded` / `X-Forwarded-Port` / `-Prefix` / `-Ssl` cleared in nginx (Spring would prefer `Forwarded` over `X-Forwarded-For`) ✅, guarded in CI by `scripts/check-nginx-headers.sh`. `Host` / `X-Forwarded-Host` (OIDC redirect URIs) are the configured `MODOCUMENT_PUBLIC_HOST`, never the client's `Host` header ✅ (Semgrep `request-host-used` in CI). |
| **Web-layer & API abuse (XSS/clickjacking, unmetered expensive calls, undocumented endpoints)** | Strict CSP + full header set on the SPA and every API response, nginx fallback for its own responses ✅ (ws16); per-user rate-limit buckets on every expensive route, ADMIN not exempt ✅ (ws17); generated OpenAPI contract, only login entry points + the synthetic probe anonymous ✅ (ws17/18); non-root containers, all capabilities dropped ✅/🏗️ | Route inventory pinned in CI — a new endpoint fails the build until reviewed ✅; refused calls audited `AUTHORIZATION_DENIED` ✅; ZAP baseline weekly ✅ (ws19) | Tests: `HeaderContractTest`, `scripts/check-nginx-headers.sh`, `ApiRateLimitIntegrationTest`, `OpenApiContractTest`, `ApiDocsExposureTest`, `RoutePermissionCoverageTest`; [web-hardening.md](web-hardening.md), [owasp-api-top10.md](owasp-api-top10.md). Container runtime checks on a Docker host ⏳. |
| **Malicious file upload** | Content gate: magic-byte/structure checks, not extension trust ✅; SHA-256 dedup/integrity ✅; size limits ✅; parsing in a non-root container ✅ | Upload + parse failures audited/logged ✅ | Tests: `UploadContentGateSec030Test`, `UploadContentCheckerTest`, `IntegrityGateC6Test`. AV scanning is **not implemented** — compensating controls: content-type gate, no re-serving of uploads to other users, non-root parse. Decision owner in roadmap. |
| **Web-extraction legal/abuse exposure** | Domain allow-list, robots.txt fail-closed, crawl-delay honored, per-host rate limiter, honest UA, 401/402/403 terminal, no CAPTCHA/login bypass ✅; `termsAcknowledged` required ✅ | Every fetch/block/dedup/retry in the queryable extraction audit trail ✅ | Tests: `CompliantWebAccessTest`, `RobotsTxtParserTest`. |

## Detective-control test coverage, summarized honestly

* **Tested in CI today:** audit append-only + enrichment (`AuditAppendOnlyPgIT`,
  `JpaAuditRecorderTest`), denied-access auditing, retention-sweep evidence
  events (`RetentionPurgeGateC5Test`), redaction canaries, refresh-rotation
  refusal; the ws18 alert framework end to end (immediate + windowed rules,
  webhook/e-mail/log delivery, dedupe — `AlertingGateD1Test`), export-cap
  refusals/overrides (`ExportCapGateD2Test`), the bank-scoped SIEM feed
  (`SiemFeedE6Test`) and the synthetic probe (`SyntheticProbeTest`,
  `ProductionBootPgIT`); ws19's secret/SAST/dependency scans are configured
  to run on every push/PR — but since at least 2026-10-17 GitHub Actions
  starts no job (every run fails within seconds), so no CI scan has run since
  the last successful Security run of 2026-10-12. The weekly Trivy scan of
  2026-10-12 found 11 critical and 41 high fixable library vulnerabilities in
  the app image, still open (GitHub issue #52;
  `docs/compliance/evidence/A5-vulnerability-assessment-report-2026-10.md`).
* **Written but unapplied (🏗️):** GuardDuty, CloudTrail, flow logs, the
  CloudWatch alarms and the `ACCOUNT_LOCKED` metric filter — they exist as
  Terraform in `infra/terraform/` and have **no runtime evidence yet**.
* **Pending (⏳):** the SIEM reference test with a bank's real SIEM
  (SEC-031 — the feed and push exist, the end-to-end exercise does not),
  wiring the production alert receivers (webhook/e-mail/SNS endpoints are
  deployment configuration), the ALB proxy-chain fix
  for per-IP keys/HSTS in the ECS topology, the restore drill (D6), the k6
  load-test run (E7), external pen test (E4).

## Detective control with a tested alert, per threat (SEC-040 acceptance)

Acceptance: "every threat row has at least one detective control with a
tested alert". "Tested" here means an automated test trips the rule end to
end and asserts the alert (`AlertLog` row or webhook), and the test passed in
the run of 2026-10-19 (`docs/compliance/evidence/A5-scans-and-tests.md`).

| Threat | Alert rule(s) | Trip test | Met? |
|---|---|---|---|
| Credential stuffing / brute force | `login-failures-per-user`, `login-failures-per-ip`, `account-locked` | `AlertingGateD1Test` | ✅ |
| Token theft / session hijack | `refresh-token-reuse` (CRITICAL) | Replay is refused and audited (`AuthFlowIntegrationTest`); the rule is only pinned as configuration (`AlertRuleCatalogueTest`) — no test trips it | ⏳ |
| Cross-tenant access | `access-denied-burst`, `authorization-denied-burst` | `AccessDeniedBurstAlertTest` (access-denied-burst) | ✅ |
| Malicious insider export / data theft | `export-blocked`, `export-over-cap`, `export-burst`, `privileged-change` | `ExportCapGateD2Test` | ✅ |
| Ransomware / destructive attack | GuardDuty → SNS 🏗️ | none (`NOT IN REPO (live cloud)`); no EDR or file-integrity monitoring | ❌ |
| Supply chain | CI job failures; issue "Security: base image refresh" | Not an alert to a person; CI not running since at least 2026-10-17 | ❌ |
| AI prompt injection / model abuse | `ai-prompt-refused`, `ai-output-blocked`, `ai-circuit-opened` | Rules pinned as configuration only; no test trips them | ⏳ |
| Data in logs leak | none at runtime (build-time canary `LogRedactionCanaryTest`) | — | ❌ |
| Spoofed client identity | none | — | ❌ |
| Web-layer & API abuse | `authorization-denied-burst`, `api-client-auth-failures`, `platform-change` (egress approvals, bank lifecycle) | `PlatformChangeAlertTest` (platform-change); the other two untested | ✅ (partly) |
| Malicious file upload | none | — | ❌ |
| Web-extraction legal/abuse exposure | none | — | ❌ |

Result: **4 of 12** threat rows meet the acceptance (3 fully, 1 partly); 2 have alert rules without a trip test; 6 have no alerting detective control.
Further tested rules that map to no row above: `admin-login`
(`AlertingGateD1Test`), `agent-offline` (`AgentOfflineSweepTest`). Open
actions (owner Bhavish Nobeen; dates in [security-roadmap.md](security-roadmap.md)):
add trip tests for `refresh-token-reuse`, the AI rules and
`api-client-auth-failures`; decide a detective control for each ❌ row or
record the gap as an accepted risk.

Related: [security-feature-catalogue.md](security-feature-catalogue.md) ·
[cia-controls-table.md](cia-controls-table.md) ·
[security-roadmap.md](security-roadmap.md)
