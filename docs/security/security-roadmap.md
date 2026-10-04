# Security roadmap — SEC-041

What is **done** (and where the proof lives), what **remains** (and who owns
it). Companion to the README section *"Implement later (cloud &
organisational)"*, which maps the open gate items in table form. Last
updated: **2026-10-04** (workstream 20 of the 20-workstream security
program, executed Sep–Oct 2026).

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
| ws20 (this) | Terraform for the af-south-1 go-live design (7 modules, 2 envs), crypto standard, feature catalogue, threat matrix, CIA table, data inventory, DPIA template, key register, SLA plan, security.txt | `infra/terraform/`, this folder |

(ws17–ws19 — load-test scripts, alerting channels, CI SAST/branch
protection — merge in parallel; their artifacts are referenced where they
land, not claimed here.)

## Remaining — owners and dates

Owners per the program workbooks: **Bhavish Nobeen** (platform/infra),
**Reezvi Pydiah** (risk & compliance sign-offs), **Rushal Seeruthun**
(operations), **Taj Aundoo** (corporate IT).

| Item | Gate/control | Owner | Target / note |
|---|---|---|---|
| External penetration test | E4 / SEC-034 | Reezvi Pydiah (procure), Bhavish Nobeen (remediate) | **Budget approval 31 Oct 2026**; run against staging (open `ingress_cidrs` for the test window), findings re-tested before go-live |
| Load test execution | E7 / SEC-003 | Rushal Seeruthun | k6 scripts arrive from ws17; target = staging ALB (`envs/staging` output); pass = autoscaling caps hold, p95 within SLA |
| Cloud build-out: accounts, console MFA, CloudTrail lock account, staging account, Secrets Manager values | A1–A6 | Bhavish Nobeen | Apply `infra/terraform/` per its README; org-level items (accounts, root MFA) are console work |
| Egress allow-list enforcement | D3 / A12 | Bhavish Nobeen (define), Reezvi Pydiah (sign off) | Then flip `enforce_egress_nacl` + narrow `app_egress_https_cidrs` |
| Real backups live + **restore drill** | D4–D6 / PRIV-006 | Rushal Seeruthun | Drill in staging (unlocked vault), evidence = timed restore log; only then `enforce_backup_locks=true` in production |
| DR/BCDR sign-off | D7 | Reezvi Pydiah | Based on the cross-region copies + drill evidence |
| security@ mailbox + security.txt contact goes live | E5 / SUP-S12 | Taj Aundoo | `frontend/public/.well-known/security.txt` ships the address; mailbox must be monitored before go-live |
| Residual AI-risk written acceptance | C11 | **Reezvi Pydiah** | The risk list is in the README later-table (no field-extraction tokenization, no input cleaning, model-written figures, no model pinning) |
| DMARC/SPF/DKIM on the sending domain | SUP-S10 | Taj Aundoo | DNS-level; not representable in this repo |
| MDM on staff devices | SUP-S09 | Taj Aundoo | Organisational |
| SIEM reference test | SEC-031 | Rushal Seeruthun | Feed = CloudTrail + app log group + `MoDocument/Security` metrics (`infra/terraform/modules/security-monitoring`); prove one end-to-end detection (e.g. `ACCOUNT_LOCKED` burst → alert received) |
| Certification path decision (ISO 27001 vs SOC 2) | SEC-038 | Reezvi Pydiah | Decide before contracting the auditor; the evidence pack in this folder is structured to serve either |
| HS256 → RS256 migration decision point | SEC-029 | Bhavish Nobeen | Trigger: first second-verifier requirement; else review Oct 2027 ([crypto-standard.md](crypto-standard.md) §2) |
| Terraform `validate`/`plan` on a machine with terraform | — | Bhavish Nobeen | This container has no terraform binary; mechanical HCL checks only were run (ws20 verification) |

## Standing cadence (post-go-live)

* Quarterly: access review export (SEC-025) reviewed and signed; key/cert
  register expiry sweep (SUP-S08).
* Semi-annual: restore drill repeat; SoD scan findings review.
* Annual: pen test; crypto standard review; DPIA refresh for new
  processing ([dpia-template.md](dpia-template.md)).
