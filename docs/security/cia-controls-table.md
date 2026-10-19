# CIA controls table — PRIV-011

One page: how MoDocument protects **C**onfidentiality, **I**ntegrity and
**A**vailability. Every cell names a verifiable artifact: a test class, a
file, or a Terraform path. 🏗️ = written as Terraform, validated, **not
applied** to any cloud account (`infra/terraform/README.md` status). ⛔ = not
in place; the gap is named in the cell.

| Version | Date | Change | Author | Approval |
|---|---|---|---|---|
| 1.0 | 2026-10-09 | First table (integration of workstreams 16–20) | Bhavish Nobeen | — |
| 1.1 draft | 2026-10-19 | Re-verified against the code for the technology-risk pack: roles and permissions recounted (6 × 41), egress layer split into app (done) and network (not enforced), availability drill status stated, control IDs and evidence pages added | Bhavish Nobeen (owner); drafted for the pack | Reezvi Pydiah — pending |

Review cycle: annual consolidation review, and on every release that changes
a cited control (PRIV-011 Evidence Register). Evidence: PRIV-011-E01
(`docs/compliance/controls/PRIV-011.md`).

| Layer | Confidentiality | Integrity | Availability |
|---|---|---|---|
| **Access** | RBAC: 6 roles (`Role` enum) × 41 permissions (`Permission` enum), every route mapped — `RolePermissionMatrixTest`, `RoutePermissionCoverageTest`; MFA enrolment forced for admins before any token — `MfaFlowIntegrationTest`; only login entry points and the synthetic probe are anonymous — `OpenApiContractTest`, `ApiDocsExposureTest`. Controls SEC-011, SEC-013, SEC-014 | Maker/checker on critical changes, self-approval refused — `MakerCheckerIntegrationTest`; author ≠ releaser — `DocReleaseSodIntegrationTest`. Controls SEC-023, SEC-024 | Lockout is per account, rate limits answer 429 with Retry-After — `AccountLockoutIntegrationTest`, `LlmQuotaRetryAfterIntegrationTest`, `ApiRateLimitIntegrationTest`. Control SEC-002 |
| **Application** | PII masking per channel × role — `MaskingGateC2Test`; per-role export row caps, above the cap only with a stated reason, refusal alerted — `ExportCapGateD2Test`, `ExportGuardTest`; AI gateway pseudonymisation and one-bank rule — `AiGatewayC9Test`; AI model local by default (`aifiles.openai.base-url` defaults to a local endpoint in `src/main/resources/application.yml`; [ai-controls.md](ai-controls.md)); log redaction — `LogRedactionCanaryTest`; CSP and header set — `HeaderContractTest`, `scripts/check-nginx-headers.sh`. Controls PRIV-002, SUP-S13, SUP-S01 | Upload content gate (magic bytes) — `UploadContentGateSec030Test`; SHA-256 upload hashes and DB constraints — `IntegrityGateC6Test`, `IntegrityConstraintsPgIT`; generated-PDF self-check — `DocCreationPdfApiTest` | Resumable jobs checkpointed per stage — `IngestionJobUseCaseTest`; LLM paths degrade instead of failing — `CompositeDocumentParserTest`; optimistic locking — `IngestionJobOptimisticLockTest` |
| **Data / tenant** | PostgreSQL RLS, fail-closed, `modocument_app` NOBYPASSRLS — `TenantIsolationGatePgIT`, `RlsPolicyCoveragePgIT`; owner scoping — `CrossUserAccessTest`; bank-scoped SIEM feed — `SiemFeedE6Test`. Controls SEC-016, SEC-031 | Append-only audit trail (REVOKE + trigger, `src/main/resources/db/migration/V14__audit_event.sql`) written in the business transaction — `AuditAppendOnlyPgIT`, `JpaAuditRecorderTest`. Control SEC-035 | Retention purge keeps report skeletons and referential structure — `RetentionPurgeGateC5Test`. Control SEC-033 |
| **Session / transport** | TLS 1.2+ at ALB/CloudFront 🏗️ `infra/terraform/modules/ecs-app`, `infra/terraform/modules/edge`; forced DB TLS 🏗️ `infra/terraform/modules/rds` (`rds.force_ssl`) and client `sslmode` — `DatabaseTlsConfigTest`, `ProductionBootPgIT`; HSTS via trusted proxy — `TrustedProxyIntegrationTest`. Control SEC-028 | JWT signatures — `JwtServiceTest`, with a ≥ 32-byte key enforced at boot in `src/main/java/com/modocument/api/config/SecurityConfig.java`; refresh rotation refuses replay — `AuthFlowIntegrationTest`, and the replay alert rule is pinned — `AlertRuleCatalogueTest`; HMAC-SHA256-signed alert and SIEM webhooks — `AlertingGateD1Test`, `SiemFeedE6Test`. Control SUP-S02 | Short access TTL with rotating refresh — `AuthFlowIntegrationTest` |
| **Secrets / crypto** | Argon2id passwords, AES-256-GCM MFA secrets — `MfaSecretCipherTest`, [crypto-standard.md](crypto-standard.md); secrets as files — `SecretFileEnvironmentPostProcessorTest`; Secrets Manager injection 🏗️ `infra/terraform/modules/ecs-app`; no secret in git history — gitleaks run 2026-10-19, 0 findings (`docs/compliance/evidence/A5-scans-and-tests.md`). Controls SEC-018, SEC-029 | GCM authentication tag rejects tampering — `MfaSecretCipherTest` | Fail-fast boot on a missing or short JWT secret — `src/main/java/com/modocument/api/config/SecurityConfig.java`; secret-file loading — `SecretFileEnvironmentPostProcessorTest` |
| **Network / egress** | App layer: user-supplied URLs refused when they resolve to internal ranges — `OutboundHostPolicyTest`; private bank destinations only through approved egress rules (two platform admins) — `BankEgressGuardTest`, `EgressRuleIntegrationTest`; private subnets, DB reachable only from the app SG 🏗️ `infra/terraform/modules/network`. ⛔ Network allow-list not enforced: app SG HTTPS egress defaults to `0.0.0.0/0` and the egress NACL is off until gate D3 sign-off (`infra/terraform/modules/network/variables.tf`, `infra/terraform/modules/edge/main.tf`). Controls PRIV-002, SUP-S06 | CloudTrail log-file validation 🏗️ `infra/terraform/modules/security-monitoring`; ECR immutable tags 🏗️ `infra/terraform/modules/ci-oidc`; Object-Lock WORM archive 🏗️ `infra/terraform/modules/backups` | WAF and CloudFront in front of the ALB 🏗️ `infra/terraform/modules/edge`. Control SUP-S03 |
| **Infrastructure** | RDS encrypted with KMS, Performance Insights off 🏗️ `infra/terraform/modules/rds`. Control SEC-028 | Terraform `validate` passes for both envs (`infra/terraform/README.md`); plan/apply pending. Control SEC-025 | Multi-AZ RDS, 2-AZ ECS, target-tracking autoscaling with a max cap, ALB health checks 🏗️ `infra/terraform/modules/rds`, `infra/terraform/modules/ecs-app`; locked backups with cross-region copy 🏗️ `infra/terraform/modules/backups`. ⛔ **Restore drill not evidenced:** the workbook planned a first drill on 3 Oct 2026; no drill log exists in the repository and the RTO stays "pending" ([sla-and-status.md](sla-and-status.md)); nothing is deployed. Controls SEC-004, PRIV-006 |
| **Monitoring** | 26 alert rules over the audit trail → log / webhook / e-mail with `AlertLog` evidence — `AlertingGateD1Test`, `AlertRuleCatalogueTest`, [alerting-and-export-caps.md](alerting-and-export-caps.md); GuardDuty 🏗️ `infra/terraform/modules/security-monitoring`. Controls SEC-032, PRIV-003 | Audit trail 7-year retention — [retention-and-dsr.md](retention-and-dsr.md), 🏗️ `infra/terraform/modules/backups`; CI scans (gitleaks, Semgrep, dependency review, npm audit, Trivy, ZAP, SBOM) — `.github/workflows/security.yml`, `.github/workflows/image-rebuild.yml`, `.github/workflows/zap-baseline.yml`, `.github/workflows/sbom.yml`. Controls SEC-009, SEC-042 | Public synthetic probe through the whole path — `SyntheticProbeTest`; ALB 5xx / CPU / storage alarms → SNS 🏗️ `infra/terraform/modules/security-monitoring`; SLA 99.5 % — [sla-and-status.md](sla-and-status.md). Controls SEC-005, SUP-S11 |

## What the table does not yet prove

- Every 🏗️ cell is configuration only: `NOT IN REPO (live cloud)` until the
  first `terraform apply`.
- Availability has no tested recovery: the restore drill (gate D6) has not
  run. Whether the 3 Oct 2026 drill took place outside the repository is
  `TO CONFIRM (organisational)`.
- RTO figures disagree: [sla-and-status.md](sla-and-status.md) says 4 h; the
  04 workbook (SEC-005 comments, 2 Oct 2026) says 2 h. To settle at approval.
- The table is not yet approved (Reezvi Pydiah, approver of security
  documents per the governance RACI).

Full control inventory: [security-feature-catalogue.md](security-feature-catalogue.md).
Threats × controls: [threat-control-matrix.md](threat-control-matrix.md).
