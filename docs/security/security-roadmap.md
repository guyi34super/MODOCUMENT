# Security roadmap — SEC-041

What is **done** (and where the proof lives), what **remains** (and who owns
it, by when, at what cost). Companion to the README section *"Implement later
(cloud & organisational)"*, which maps the open gate items in table form.

| Version | Date | Change | Author | Approval |
|---|---|---|---|---|
| 1.0 | 2026-10-09 | Done / remaining after workstreams 16–20 of the 20-workstream security program (Sep–Oct 2026) | Bhavish Nobeen | — |
| 1.1 draft | 2026-10-19 | Dated plan with owners, phases and budget lines added from the technology-risk pack (workbooks 02/03/04 and the scans of 2026-10-19); overdue workbook dates re-proposed | Bhavish Nobeen (owner); drafted for the pack | Reezvi Pydiah (dates, budget lines) — pending |

Review: monthly by the directors (02 workbook SEC-041); quarterly progress,
annual refresh. Horizon: October 2026 – Q1 2027 for dated items, 12–24 months
for the standing cadence. The dated plan is the section "Plan October 2026 –
Q1 2027" below.

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
| post-ws20 (9 Oct 2026) | Client-sent `Forwarded` / `X-Forwarded-Port` / `-Prefix` / `-Ssl` cleared in nginx (SEC-022): Spring prefers RFC 7239 `Forwarded` over the `X-Forwarded-For` nginx overwrites, so before this a client could pick its own IP for rate limits and audit rows | `frontend/nginx.conf` API location, `scripts/check-nginx-headers.sh` (CI), [web-hardening.md](web-hardening.md) §5e |
| post-ws20 (10 Oct 2026) | `Host` / `X-Forwarded-Host` sent to the app are the configured public host (`MODOCUMENT_PUBLIC_HOST`; ECS: `public_host` = `app_domain`), validated at container start, instead of the client's `Host` header; also fixes the compose OIDC redirect URI losing `:3000` | `frontend/nginx.conf`, `frontend/docker-entrypoint.d/`, `infra/terraform/modules/ecs-app`, [web-hardening.md](web-hardening.md) §5e |

## Remaining — owners and dates

Owners as named in this repository: **Bhavish Nobeen** (platform/infra),
**Reezvi Pydiah** (risk & compliance sign-offs), **Rushal** (operations),
**Taj Aundoo** (corporate IT). The workbooks assign every workbook control
to Bhavish Nobeen (management decision of 2 Oct 2026); where the two
differ, the MO-001 Governance Structure records the conflict.

| Item | Gate/control | Owner | Target / note |
|---|---|---|---|
| External penetration test | E4 / SEC-034 | Reezvi Pydiah (procure), Bhavish Nobeen (remediate) | **Budget approval 31 Oct 2026**; run against staging (open `ingress_cidrs` for the test window), findings re-tested before go-live |
| Load test execution | E7 / SEC-003 | Rushal | k6 scenarios are in [`loadtest/`](../../loadtest/README.md) (ws17, not yet run); target = staging ALB (`envs/staging` output); pass = autoscaling caps hold, p95 within SLA |
| Cloud build-out: accounts, console MFA, CloudTrail lock account, staging account, Secrets Manager values | A1–A6 | Bhavish Nobeen | Apply `infra/terraform/` per its README; org-level items (accounts, root MFA) are console work |
| Egress allow-list enforcement | D3 / A12 | Bhavish Nobeen (define), Reezvi Pydiah (sign off) | Then flip `enforce_egress_nacl` + narrow `app_egress_https_cidrs` |
| Real backups live + **restore drill** | D4–D6 / PRIV-006 | Rushal | Drill in staging (unlocked vault), evidence = timed restore log; only then `enforce_backup_locks=true` in production |
| DR/BCDR sign-off | D7 | Reezvi Pydiah | Based on the cross-region copies + drill evidence |
| security@ mailbox + security.txt contact goes live | E5 / SUP-S12 | Taj Aundoo | `frontend/public/.well-known/security.txt` ships a **placeholder** address on the reserved `.example` domain; the real address, the mailbox and a public policy URL are needed before go-live ([vulnerability-disclosure-policy.md](../compliance/policies/vulnerability-disclosure-policy.md)) |
| Residual AI-risk written acceptance | C11 | **Reezvi Pydiah** | The risk list is in the README later-table (no field-extraction tokenization, no input cleaning, model-written figures, no model pinning) |
| DMARC/SPF/DKIM on the sending domain | SUP-S10 | Taj Aundoo | DNS-level; not representable in this repo |
| MDM on staff devices | SUP-S09 | Taj Aundoo | Organisational |
| SIEM reference test | SEC-031 | Rushal | App side done (ws18): bank-scoped pull feed `GET /api/siem/events` + optional signed push, schema in [siem-event-schema.md](siem-event-schema.md), alert channels; infra feed = CloudTrail + app log group + `MoDocument/Security` metrics (`infra/terraform/modules/security-monitoring`). Remaining: connect one bank's real SIEM and prove one end-to-end detection (e.g. `ACCOUNT_LOCKED` burst → alert received) |
| Branch protection applied + seeded-PR drill | A8 / SEC-042 | Bhavish Nobeen | Settings per [branch-protection.md](branch-protection.md) are a repo-admin action in GitHub; then run the documented seeded-vulnerable-PR drill and file the blocked run as evidence |
| `dependency-review` check able to run | SEC-010 / SUP-S05 | Bhavish Nobeen | The repository is private, and GitHub runs dependency review there only with Dependency graph + GitHub Code Security enabled (Settings → Code security); until then the job fails with "Dependency review is not supported on this repository" (seen on PR 12, 9 Oct 2026). Dependabot, `npm-audit` and the weekly Trivy rebuilds still cover known-vulnerable dependencies. Enable it, or record a decision to drop the job — never mark it optional silently |
| ECS proxy chain, bootstrap admin, RDS CA — live confirmation | C4 / SEC-028 / B5 | Bhavish Nobeen | Fixed in code (Done table, post-ws20); on the first staging/production deploy confirm: audit IPs = viewer addresses (IPv4 + IPv6), HSTS on API responses, OIDC redirect URI `https://<app_domain>/…`, the app connects with `verify-full`, `bootstrap_admin_enabled=false` after the first admin enrols MFA — [`infra/terraform/README.md`](../../infra/terraform/README.md) "Proxy chain, bootstrap admin, DB CA" |
| Container runtime verification | SUP-S04 | Bhavish Nobeen | First `docker compose up --build` on a Docker host: `id` = 10001 (app) / 101 (frontend), volume writable, healthchecks green, ZAP baseline run ([web-hardening.md](web-hardening.md) "What is not verified without Docker") |
| Certification path decision (ISO 27001 vs SOC 2) | SEC-038 | Reezvi Pydiah | Decide before contracting the auditor; the evidence pack in this folder is structured to serve either |
| HS256 → RS256 migration decision point | SEC-029 | Bhavish Nobeen | Trigger: first second-verifier requirement; else review Oct 2027 ([crypto-standard.md](crypto-standard.md) §2) |
| Terraform `plan`/`apply` with AWS credentials | — | Bhavish Nobeen | `fmt -check` + `validate` pass for both envs (Terraform 1.9.8, aws 5.100.0, local provider mirror, 9 Oct 2026); `plan` needs account credentials |

## Plan October 2026 – Q1 2027

Built on 2026-10-19 from the 30-60-90 roadmap rows of the three workbooks
(02 Security, 03 Software Engineering, 04 IT Infrastructure & Operations),
checked against the repository and the runs of 2026-10-19
(`docs/compliance/evidence/A5-scans-and-tests.md`). Workbook dates that have
passed are shown as **overdue** with a new date marked *proposed*.

**Budget:** no budget line exists in the repository or the workbooks. Every
amount is `TO CONFIRM (organisational)`, to be set by Reezvi Pydiah, starting
with the penetration test (02 workbook SEC-041). The "Budget line" column
says what the money is for.

Status: Done · In progress (an artifact exists, the item is not closed) ·
Not started.

### Day 30 (to 2026-10-31)

| # | Control | Action | Owner | Workbook date | Date now | Status | Budget line | Artifact |
|---|---|---|---|---|---|---|---|---|
| R1 | SEC-009 | Fix the 11 critical and 41 high library CVEs in the app image (Spring Boot parent 3.5.3 → latest 3.5.x, Spring AI ≥ 1.0.7, JDBC drivers); re-scan | Bhavish Nobeen | — (72 h SLA from 2026-10-12) | **overdue** → 2026-10-22 (proposed) | Not started | engineering time | `docs/compliance/evidence/A5-vulnerability-assessment-report-2026-10.md` V-01/V-02; GitHub issue #52 |
| R2 | SEC-009 / SUP-S05 | Restore GitHub Actions runners so CI scans run again | Bhavish Nobeen | — | 2026-10-22 (proposed) | Not started | GitHub plan: `TO CONFIRM (organisational)` | P-01 in the VA report |
| R3 | SEC-009 | Penetration-test budget decision; then vendor and scope | Reezvi Pydiah (budget), Bhavish Nobeen (remediation) | 2026-10-31 | 2026-10-31 | Not started | PT + retest: `TO CONFIRM (organisational)` | — |
| R4 | SEC-009 | Monthly VA report; ZAP COEP warning fixed or justified | Bhavish Nobeen | monthly from 2026-10-09 | next report 2026-11 (proposed) | In progress | — | VA report 2026-10 |
| R5 | SEC-032 | Phone path live: webhook relay for HIGH/CRITICAL, alert variables in the ECS task definition, written trip test (date, rule, time, recipient), repeated out of hours | Bhavish Nobeen | 2026-10-16 | **overdue** → 2026-10-30 (proposed) | In progress | on-call / relay tool: `TO CONFIRM (organisational)` | [alert-escalation-policy.md](../compliance/policies/alert-escalation-policy.md) |
| R6 | SEC-040 | Trip tests for `refresh-token-reuse`, the AI rules and `api-client-auth-failures`; monthly matrix review | Bhavish Nobeen | 2026-10-16 | **overdue** → 2026-11-02 (proposed) | In progress | engineering time | [threat-control-matrix.md](threat-control-matrix.md) acceptance table (4 of 12 threats) |
| R7 | PRIV-011 | Approve the CIA table | Reezvi Pydiah | 2026-10-16 | **overdue** → 2026-11-02 (proposed) | In progress | — | [cia-controls-table.md](cia-controls-table.md) v1.1 draft |
| R8 | SUP-S12 | Real security address and mailbox to the directors, 48 h acknowledgement, public policy URL, CERT-MU subscription, weekly intel review running | Taj Aundoo (mailbox) / Bhavish Nobeen (policy) — split `TO CONFIRM (organisational)` | 2026-10-16 | **overdue** → 2026-10-30 (proposed) | In progress | mailbox: `TO CONFIRM (organisational)` | [vulnerability-disclosure-policy.md](../compliance/policies/vulnerability-disclosure-policy.md), [threat-intelligence-review-procedure.md](../compliance/policies/threat-intelligence-review-procedure.md), `docs/compliance/evidence/A5-threat-intel-review-note-2026-W43.md` |
| R9 | SEC-017 / SEC-013 | Separate admin identities via role elevation; one-page grant record approved by Reezvi Pydiah; quarterly permission recertification | Bhavish Nobeen | 2026-10-30 | 2026-10-30 | In progress (workbook) | identity tooling: `TO CONFIRM (organisational)` | — |
| R10 | SEC-041 | Confirm proposed dates; set budget lines; monthly director review minuted | Bhavish Nobeen (dates), Reezvi Pydiah (budget) | 2026-10-31 | 2026-10-31 | In progress | — | this page |
| R11 | SEC-010 / SEC-042 | Branch protection applied + seeded-PR drill; dependency review enabled (Code Security) or dropped by decision | Bhavish Nobeen | 2026-10-16 (SEC-010), 2026-10-09 (SEC-042) | **overdue** → 2026-10-30 (proposed) | Not started | GitHub Code Security: `TO CONFIRM (organisational)` | [branch-protection.md](branch-protection.md) |
| R12 | SUP-S10 | SPF, DKIM, DMARC on the company domain | Taj Aundoo (repo) / Bhavish Nobeen (workbook) | 2026-10-09 | **overdue** → 2026-10-30 (proposed) | Not started | — | not representable in this repository |
| R13 | SUP-S07 | Staging in a separate AWS account; staging → production reachability test | Bhavish Nobeen | 2026-10-09 | **overdue** → with the first `terraform apply` (date `TO CONFIRM (organisational)`) | Not started | AWS account | `infra/terraform/envs/` |

### Day 60 (to 2026-11-30)

| # | Control | Action | Owner | Workbook date | Date now | Status | Budget line | Artifact |
|---|---|---|---|---|---|---|---|---|
| R14 | PRIV-002 | Network egress allow-list enforced (narrow `app_egress_https_cidrs`, set `enforce_egress_nacl = true`); alert on a new egress destination | Bhavish Nobeen (define), Reezvi Pydiah (sign-off) | 2026-11-13 | 2026-11-13; needs AWS | Not started | VPC interface endpoints: `TO CONFIRM (organisational)` | `infra/terraform/modules/network/variables.tf`, `infra/terraform/modules/edge/main.tf` |
| R15 | SEC-015 | Session Manager recording live; database statement audit (pgAudit is not configured — the RDS parameter group logs DDL and connections only); reconstruct one test admin session from logs the admin cannot change | Bhavish Nobeen | 2026-11-13 | 2026-11-13; needs AWS | Not started | — | `infra/terraform/modules/security-monitoring/main.tf`, `infra/terraform/modules/rds/main.tf` |
| R16 | SEC-031 | Reference-SIEM test (free Elastic or Splunk): login failure visible in < 60 s; `structured-logs` profile set in the ECS task; syslog/CEF decision | Bhavish Nobeen (workbook) / Rushal (repo) | 2026-11-30 | 2026-11-30 | In progress | SIEM test instance: `TO CONFIRM (organisational)` | [siem-event-schema.md](siem-event-schema.md) |
| R17 | SEC-040 | A detective control or a written risk acceptance for each threat without a tested alert (ransomware, supply chain, upload, spoofing, extraction, log leak) | Bhavish Nobeen; Reezvi Pydiah (acceptance) | — | 2026-11-30 (proposed) | Not started | EDR / file-integrity tool: `TO CONFIRM (organisational)` | [threat-control-matrix.md](threat-control-matrix.md) |

### Pre-go-live (dates to set; each needs the AWS build-out first)

The 03 and 04 workbooks give no date for these rows ("Pre-go-live (date to
set)").

| # | Control(s) | Action | Owner | Status | Budget line |
|---|---|---|---|---|---|
| R18 | PRIV-006, SEC-004, PRIV-011 | Backups live; timed restore drill (gate D6); then irreversible vault locks | Rushal (drill), Bhavish Nobeen (DR lead) | Not started — no drill record in the repository | AWS Backup storage |
| R19 | SEC-008, SEC-002, SEC-003 | Load / stress / soak run on staging; capacity report | Bhavish Nobeen (plan, report), Rushal (run) | Not started in staging (see MO-016/MO-017) | staging run time |
| R20 | SEC-005, SUP-S11 | External uptime monitoring, status page, paging to on-call | Bhavish Nobeen, Rushal | Not started (probe endpoint exists) | monitoring service: `TO CONFIRM (organisational)` |
| R21 | PRIV-003, SEC-033 | GuardDuty, CloudTrail lock account, 7-year Object-Lock archive applied | Bhavish Nobeen | Not started (Terraform written) | AWS |
| R22 | SUP-S03 | WAF and CloudFront applied; DDoS runbook | Bhavish Nobeen | Not started (Terraform written) | AWS WAF |
| R23 | SEC-026 | Monthly patch window and report; weekly image rebuild running again (R2) | Bhavish Nobeen | In progress (workflow written) | — |
| R24 | SUP-S08 | Certificate and key register with expiry alerts at 30/14/7 days | Bhavish Nobeen | In progress (register written) | — |
| R25 | SUP-S09 | MDM on staff devices | Taj Aundoo | Not started | MDM licences: `TO CONFIRM (organisational)` |
| R26 | SUP-S13 / C11 | Written acceptance of residual AI risks | Reezvi Pydiah | Not started | — |
| R27 | SEC-038 | Certification path decision (ISO 27001 or SOC 2) | Reezvi Pydiah | Not started | auditor: `TO CONFIRM (organisational)` |

### Q1 2027

| # | Control | Action | Owner | Status | Budget line |
|---|---|---|---|---|---|
| R28 | SEC-009 | Independent penetration test and retest; share the executive summary | Reezvi Pydiah (procure), Bhavish Nobeen (remediate) | Not started (R3 first) | PT + retest: `TO CONFIRM (organisational)` |

Concentration risk (02 workbook, cross-cutting risk row "Multiple"): Bhavish
Nobeen owns most actions above plus the DR Lead and Data & Privacy items, by
management decision of 2 Oct 2026 (status "Accepted"). Rushal is the named
deputy.

## Standing cadence (post-go-live)

* Quarterly: access review export (SEC-025) reviewed and signed; key/cert
  register expiry sweep (SUP-S08).
* Semi-annual: restore drill repeat; SoD scan findings review.
* Annual: pen test; crypto standard review; DPIA refresh for new
  processing ([dpia-template.md](dpia-template.md)).
