# Security roadmap — SEC-041

What is **done** (and where the proof lives), what **remains** (and who owns
it). Companion to the README section *"Implement later (cloud &
organisational)"*, which maps the open gate items in table form. Last
updated: **2026-10-09** (integration of workstreams 16–20, the last wave of
the 20-workstream security program, executed Sep–Oct 2026).

## Done — delivered by the security program (merged to this branch)

| When (workstream) | Delivered | Proof |
|---|---|---|
| ws01 (foundation) | IAM core: JWT auth hardening, bank/tenant columns, app DB role (NOBYPASSRLS), RLS policies, append-only audit trail, refresh/revoked tokens (V10–V15) | `TenantIsolationGatePgIT`, `AuditAppendOnlyPgIT`, `AuthFlowIntegrationTest` |
| ws02 | Upload ownership + `UploadedFile` registry (V20) | `UploadOwnershipEnforcementTest` |
| ws05 | Integrity constraints + SHA-256 upload hashes (V30/V31), upload content gate | `IntegrityGateC6Test`, `UploadContentGateSec030Test` |
| ws06 | Argon2id + lockout (V40), TOTP MFA with AES-256-GCM secrets at rest (V41), admin MFA requirement | `AccountLockoutIntegrationTest`, `MfaFlowIntegrationTest`, `MfaSecretCipherTest` |
| ws07 | OIDC SSO (Entra ID/Keycloak), identity linking, shipped hardened realm (V50) | `KeycloakRealmConfigTest`, [identity-providers.md](identity-providers.md) |
| ws09 | JML user administration per bank (V70) | `UserAdminIntegrationTest`, [jml-runbook.md](jml-runbook.md) |
| ws10 | Maker/checker pending-change queue (V80) | `MakerCheckerIntegrationTest`, [maker-checker.md](maker-checker.md) |
| ws11 | Role catalogue + SoD policy, doc release four-eyes, SoD scan (V90) | `DocReleaseSodIntegrationTest`, `SodPolicyTest`, [role-catalogue-and-sod.md](role-catalogue-and-sod.md) |
| ws12 | PII classification + channel×role masking (V100) | `MaskingGateC2Test`, [masking-matrix.md](masking-matrix.md) |
| ws14 | Retention schedule + purge job + DSR endpoints (V120) | `RetentionPurgeGateC5Test`, `DataSubjectRightsGateC12Test`, [retention-and-dsr.md](retention-and-dsr.md) |
| ws(platform) | Trusted-proxy model, rate limits + LLM quota, log redaction, PII tokenization for LLM calls | `TrustedProxyIntegrationTest`, `LlmQuotaRetryAfterIntegrationTest`, `LogRedactionCanaryTest` |
| ws15 | AI gateway: pseudonymised prompts, one-bank rule, output scanning, local model by default, vision OCR opt-in (C8–C10) | `AiGatewayC9Test`, `AiOutputPolicyC10Test`, `NoDirectChatModelAccessGuardTest`, [ai-controls.md](ai-controls.md) |
| ws16 | Web & platform hardening: SPA/API security headers (E1), non-root containers + dropped capabilities (SUP-S04), `<NAME>_FILE` secrets and no compose secret defaults (SEC-018), DB TLS client side (C4), production first boot on PostgreSQL with the RLS datasource | `HeaderContractTest`, `scripts/check-nginx-headers.sh`, `SecretFileEnvironmentPostProcessorTest`, `DatabaseTlsConfigTest`, `ProductionBootPgIT`, [web-hardening.md](web-hardening.md) |
| ws17 | API contract generated from code + pinned route inventory (SEC-006/E2), per-user rate-limit buckets on every expensive endpoint (SEC-007), OWASP API Top 10 mapping, versioning policy, k6 load scenarios (scripts only) | `OpenApiContractTest`, `ApiDocsExposureTest`, `ApiRateLimitIntegrationTest`, [owasp-api-top10.md](owasp-api-top10.md), [versioning-policy.md](../api/versioning-policy.md), `loadtest/` |
| ws18 | Alert framework over the audit trail with log/webhook/e-mail channels (D1, SEC-032), per-role export caps with reason (D2, PRIV-002), bank-scoped SIEM feed + single-bank push (E6, SEC-031 app side), public synthetic probe (SUP-S11); V160/V161 | `AlertingGateD1Test`, `ExportCapGateD2Test`, `SiemFeedE6Test`, `SyntheticProbeTest`, [alerting-and-export-caps.md](alerting-and-export-caps.md), [siem-event-schema.md](siem-event-schema.md) |
| ws19 | CI/CD supply chain: SHA-pinned least-privilege workflows, gitleaks full history (A7), Semgrep with custom rules, dependency review, npm audit, Trivy image rebuilds, ZAP baseline (E3), CycloneDX SBOM (E8), Dependabot, CODEOWNERS + PR template (A8 prep) | `.github/workflows/`, `.semgrep/modocument.yml`, `.gitleaks.toml`, [branch-protection.md](branch-protection.md) |
| ws20 | Terraform for the af-south-1 go-live design (7 modules, 2 envs), crypto standard, feature catalogue, threat matrix, CIA table, data inventory, DPIA template, key register, SLA plan, security.txt | `infra/terraform/`, this folder |
| post-ws20 (9 Oct 2026) | ECS go-live blockers fixed in code: nginx trusted-proxy mode behind the ALB (realip from the VPC CIDR, ALB `X-Forwarded-Proto`, `CloudFront-Viewer-Address` via a new edge origin request policy that also forwards `Host`); bootstrap-admin secret shells + `secrets` entries + exact-ARN execution-role access (`bootstrap_admin_enabled` lifecycle); AWS RDS CA bundle baked into the app image, `sslmode=verify-full&sslrootcert=…` in both envs; `terraform validate` passing for both envs | [`infra/terraform/README.md`](../../infra/terraform/README.md) "Proxy chain, bootstrap admin, DB CA", `frontend/docker-entrypoint.d/`, repo `Dockerfile` (`rds-ca` stage), [web-hardening.md](web-hardening.md) §5d/§5e, [key register](key-certificate-register.md) K14/K15 |

## Remaining — owners and dates

Owners per the program workbooks: **Bhavish Nobeen** (platform/infra),
**Reezvi Pydiah** (risk & compliance sign-offs), **Rushal Seeruthun**
(operations), **Taj Aundoo** (corporate IT).

| Item | Gate/control | Owner | Target / note |
|---|---|---|---|
| External penetration test | E4 / SEC-034 | Reezvi Pydiah (procure), Bhavish Nobeen (remediate) | **Budget approval 31 Oct 2026**; run against staging (open `ingress_cidrs` for the test window), findings re-tested before go-live |
| Load test execution | E7 / SEC-003 | Rushal Seeruthun | k6 scenarios are in [`loadtest/`](../../loadtest/README.md) (ws17, not yet run); target = staging ALB (`envs/staging` output); pass = autoscaling caps hold, p95 within SLA |
| Cloud build-out: accounts, console MFA, CloudTrail lock account, staging account, Secrets Manager values | A1–A6 | Bhavish Nobeen | Apply `infra/terraform/` per its README; org-level items (accounts, root MFA) are console work |
| Egress allow-list enforcement | D3 / A12 | Bhavish Nobeen (define), Reezvi Pydiah (sign off) | Then flip `enforce_egress_nacl` + narrow `app_egress_https_cidrs` |
| Real backups live + **restore drill** | D4–D6 / PRIV-006 | Rushal Seeruthun | Drill in staging (unlocked vault), evidence = timed restore log; only then `enforce_backup_locks=true` in production |
| DR/BCDR sign-off | D7 | Reezvi Pydiah | Based on the cross-region copies + drill evidence |
| security@ mailbox + security.txt contact goes live | E5 / SUP-S12 | Taj Aundoo | `frontend/public/.well-known/security.txt` ships the address; mailbox must be monitored before go-live |
| Residual AI-risk written acceptance | C11 | **Reezvi Pydiah** | The risk list is in the README later-table (no field-extraction tokenization, no input cleaning, model-written figures, no model pinning) |
| DMARC/SPF/DKIM on the sending domain | SUP-S10 | Taj Aundoo | DNS-level; not representable in this repo |
| MDM on staff devices | SUP-S09 | Taj Aundoo | Organisational |
| SIEM reference test | SEC-031 | Rushal Seeruthun | App side done (ws18): bank-scoped pull feed `GET /api/siem/events` + optional signed push, schema in [siem-event-schema.md](siem-event-schema.md), alert channels; infra feed = CloudTrail + app log group + `MoDocument/Security` metrics (`infra/terraform/modules/security-monitoring`). Remaining: connect one bank's real SIEM and prove one end-to-end detection (e.g. `ACCOUNT_LOCKED` burst → alert received) |
| Branch protection applied + seeded-PR drill | A8 / SEC-042 | Bhavish Nobeen | Settings per [branch-protection.md](branch-protection.md) are a repo-admin action in GitHub; then run the documented seeded-vulnerable-PR drill and file the blocked run as evidence |
| ECS proxy chain, bootstrap admin, RDS CA — live confirmation | C4 / SEC-028 / B5 | Bhavish Nobeen | Fixed in code (Done table, post-ws20); on the first staging/production deploy confirm: audit IPs = viewer addresses (IPv4 + IPv6), HSTS on API responses, OIDC redirect URI `https://<app_domain>/…`, the app connects with `verify-full`, `bootstrap_admin_enabled=false` after the first admin enrols MFA — [`infra/terraform/README.md`](../../infra/terraform/README.md) "Proxy chain, bootstrap admin, DB CA" |
| Strip client-supplied `Forwarded` / `X-Forwarded-Port` / `-Prefix` / `-Ssl` in nginx | SEC-022 | Bhavish Nobeen | **Before go-live.** Spring prefers RFC 7239 `Forwarded` over the `X-Forwarded-For` nginx overwrites, so a client can still pick its own IP for rate limits/audit (pre-existing; found 9 Oct 2026). One-line-per-header fix + test — [web-hardening.md](web-hardening.md) §5e, [`infra/terraform/README.md`](../../infra/terraform/README.md) "Still open" |
| Container runtime verification | SUP-S04 | Bhavish Nobeen | First `docker compose up --build` on a Docker host: `id` = 10001 (app) / 101 (frontend), volume writable, healthchecks green, ZAP baseline run ([web-hardening.md](web-hardening.md) "What is not verified without Docker") |
| Certification path decision (ISO 27001 vs SOC 2) | SEC-038 | Reezvi Pydiah | Decide before contracting the auditor; the evidence pack in this folder is structured to serve either |
| HS256 → RS256 migration decision point | SEC-029 | Bhavish Nobeen | Trigger: first second-verifier requirement; else review Oct 2027 ([crypto-standard.md](crypto-standard.md) §2) |
| Terraform `plan`/`apply` with AWS credentials | — | Bhavish Nobeen | `fmt -check` + `validate` pass for both envs (Terraform 1.9.8, aws 5.100.0, local provider mirror, 9 Oct 2026); `plan` needs account credentials |

## Standing cadence (post-go-live)

* Quarterly: access review export (SEC-025) reviewed and signed; key/cert
  register expiry sweep (SUP-S08).
* Semi-annual: restore drill repeat; SoD scan findings review.
* Annual: pen test; crypto standard review; DPIA refresh for new
  processing ([dpia-template.md](dpia-template.md)).
