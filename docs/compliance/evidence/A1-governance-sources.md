# A1 — Sources for MO-001 Governance Structure

Collected on 2026-10-19 for `docs/compliance/mo-001-governance-structure.md`. Each row is one fact that the document uses, with the place it came from.

The source names used below mean:

- **Checklist**: Bhavish's work checklist, row by task ID.
- **02 / 03 / 04**: the Security, Software Engineering and IT Infrastructure & Operations workbooks, by sheet and control or risk ID. The workbook statements are owner statements from 2 Oct 2026.
- **Gate PDF**: "MoDocument — Go-Live Security Gate", 2 Oct 2026. This is earlier context and not a repo file.
- **Repo**: a path in this repository.

## People and the Board

| # | Fact | Source |
|---|---|---|
| P1 | The company has four directors | 02, Control Register SUP-S12 Required Action ("security@ to all four directors") |
| P2 | Bhavish and Rushal are the 2 admins across AWS, Azure and MoDocument Admin. There have been no grants beyond "the 2 founders". | 02, Control Register / Gap Assessment SEC-017 Comments and Current State |
| P3 | The two named administrators are each the other's deputy | 02, Questionnaire Answers SEC-017 Draft Answer |
| P4 | Monthly director review of the security roadmap | 02, Control Register SEC-041 Required Action |
| P5 | Joint risk session with "all directors and the DPO" | Checklist MO-031 Detailed Work Required |
| P6 | Bhavish Nobeen owns every control in all three workbooks | 02/03/04, Gap Assessment and 30-60-90 Roadmap "Owner" ("Security — Bhavish Nobeen", "Software Engineering — Bhavish Nobeen", "IT Infrastructure — Bhavish Nobeen"); Implementation Plan "Supporting" |
| P7 | "Management decision 2 Oct 2026 to assign all fixes to Bhavish". Bhavish also holds the DR Lead and Data & Privacy items. Rushal is named deputy per item, with a weekly status check. | 02, Risk Register R-PEOPLE-02 (status "Accepted (management decision)") |
| P8 | Rushal is deputy on the 21 Oct build items | 03, Risk Register R-SCHED-01 Mitigation |
| P9 | Bhavish and Rushal are on call, rotating weekly | 04, Control Register / Gap Assessment Comments (SEC-005, SUP-S11 design decisions of 2 Oct 2026) |
| P10 | Taj Aundoo ran the working sessions that filled the workbooks on 2 Oct 2026 | 02/03/04 meta readme ("filled from the … session with Taj Aundoo") |
| P11 | Taj does the risk and control review of MO-031, MO-032, MO-033 and MO-034 | Checklist MO-031…034 "Reviewer / Approver" |
| P12 | Rushal cross-checks MO-001 and MO-009, and reviews MO-016…019 | Checklist "Reviewer / Approver" |
| P13 | Reezvi approves MO-017 and MO-019 | Checklist MO-017, MO-019 "Reviewer / Approver" |
| P14 | The Board of Directors approves MO-001, MO-032 and MO-034 | Checklist "Reviewer / Approver"; "Management Approval: Yes" |
| P15 | MO-001 is "Governance structure by bhavish" | Checklist MO-001 Notes |
| P16 | Owners named in the repo: Bhavish Nobeen (platform/infra), Reezvi Pydiah (risk & compliance sign-offs), Rushal (operations), Taj Aundoo (corporate IT) | `docs/security/security-roadmap.md` "Remaining — owners and dates" |
| P17 | The repo uses a surname for Rushal. The workbooks do not, so the pack uses "Rushal" only. | `docs/security/security-roadmap.md`, `docs/security/incident-response-stub.md`, `README.md` |
| P18 | Gate sign-off: technical owner Bhavish Nobeen, independent reviewer "Pack 07 reviewer", approver Reezvi Pydiah | Gate PDF, "Sign-off" block |
| P19 | The independent reviewer of evidence and answers is "Pack 07", independent of the Security owner | 02/03/04, Evidence Register and Questionnaire Answers "Reviewer" |

## Approvals and decision rights

| # | Fact | Source |
|---|---|---|
| A1 | Reezvi Pydiah approves the CIA table, the security matrices and the roadmap (version 1.0 draft, next review 2026-11-02) | 02, Policies & Documents PRIV-011, SEC-040, SEC-041 "Approval" |
| A2 | The privileged-access grant record is approved by Reezvi | 02, Control Register SEC-017 Required Action |
| A3 | Reezvi sets the budget lines, starting with the pen test (31 Oct) | 02, Control Register SEC-041 Required Action |
| A4 | Reezvi decides the pen-test budget by 31 Oct | 02, Control Register SEC-009 Required Action |
| A5 | Risk acceptance by Reezvi Pydiah: R-SHARED-01, R-AI-01 (gate C11) and R-REGION-01 | 03, Risk Register R-SHARED-01, R-AI-01 "Acceptance Required"; 04, Risk Register R-REGION-01 |
| A6 | No partial go-live. A failed item needs management's written risk acceptance. | Gate PDF, "Gate rule and current position" |
| A7 | Reezvi Pydiah decides bank, regulator and data-subject notification. Incidents are reported to Rushal. Rushal owns pack-09. | `docs/security/incident-response-stub.md` |
| A8 | The DPIA sign-off is the risk owner's: Reezvi Pydiah | `docs/security/dpia-template.md` (intro and §5) |
| A9 | The retention/DSR procedure is signed off by Reezvi Pydiah, "Accountable executive, MoDocument security program" | `docs/security/retention-and-dsr.md` "Sign-off" |
| A10 | Egress allow-list D3: Bhavish defines, Reezvi signs off. D7 DR sign-off: Reezvi. E4 pen test: Reezvi procures, Bhavish remediates. SEC-038 certification decision: Reezvi. | `README.md` "Implement later" table; `docs/security/security-roadmap.md` |
| A11 | D4–D6 backups and restore drill, E7 load test and the SIEM reference test: Rushal | `README.md`; `docs/security/security-roadmap.md` |
| A12 | E5 security mailbox, SUP-S10 DMARC and SUP-S09 MDM: Taj Aundoo | `README.md`; `docs/security/security-roadmap.md`; `SECURITY.md` |
| A13 | The status page is updated by Rushal during incidents | `docs/security/sla-and-status.md` |
| A14 | Production release: environment `production` with 2 required reviewers and "Prevent self-review"; 48 h soak; only release managers create `v*` tags | `docs/release/runbook.md` §3, §7; `.github/workflows/release.yml` job `production` |
| A15 | `main`: PR required, 1 approval with Code Owner review, required checks, no admin bypass | `docs/security/branch-protection.md`; `.github/CODEOWNERS` |
| A16 | An accepted scanner risk goes into a reviewed `.trivyignore` entry with expiry and ticket | `docs/release/runbook.md` §2 |
| A17 | Joiners are approved "by the data owner" (design) | 03, Technology Implementation PRIV-017 "Configuration (from steps)" |

## Product roles and code-enforced governance (verified by reading the code)

| # | Fact | Source |
|---|---|---|
| C1 | `Role` = `ADMIN`, `APPROVER`, `READ_WRITE`, `READ_ONLY`, `PLATFORM_ADMIN`, `API_CLIENT`. The first four are bank roles. | `src/main/java/com/modocument/domain/model/Role.java` |
| C2 | Permissions per role | `docs/security/role-permission-matrix.csv`; `src/main/java/com/modocument/application/security/RolePermissions.java`; `src/test/java/com/modocument/application/security/RolePermissionMatrixTest.java` |
| C3 | 17 maker/checker change types, each with its maker permission | `src/main/java/com/modocument/domain/model/ChangeType.java` |
| C4 | `PendingChange` workflow; self-approval refused with 409 and `SELF_APPROVAL_BLOCKED` | `docs/security/maker-checker.md`; `src/main/java/com/modocument/domain/entity/PendingChange.java`; `src/main/java/com/modocument/application/usecase/PendingChangeUseCase.java`; `src/test/java/com/modocument/api/MakerCheckerIntegrationTest.java` |
| C5 | Bank vs platform checker audience | `src/main/java/com/modocument/application/security/ChangeAudience.java`; `src/test/java/com/modocument/application/usecase/PendingChangeAudienceTest.java` |
| C6 | SoD: single role per user, operation-level conflicts, nightly scan | `docs/security/role-catalogue-and-sod.md`; `src/main/java/com/modocument/application/security/SodPolicy.java`; `src/main/java/com/modocument/infrastructure/sod/SodScanJob.java` |
| C7 | Exactly two platform admins at bootstrap; at least two must stay active (`MIN_ACTIVE_PLATFORM_ADMINS = 2`); `PLATFORM_ADMIN` only in the platform bank | `src/main/java/com/modocument/application/security/RoleAssignmentPolicy.java`; `src/main/java/com/modocument/infrastructure/bootstrap/BootstrapPlatformRunner.java`; `src/main/java/com/modocument/application/usecase/UserAdminUseCase.java`; `src/main/resources/db/migration/V210__ws21_platform_bank.sql`; `docs/platform/platform-owner.md` |
| C8 | A new bank needs at least 1 `ADMIN` and at least 2 `ADMIN`/`APPROVER` first users | `src/main/java/com/modocument/application/usecase/BankCreateChangeApplier.java` |
| C9 | The audit trail is append-only: privileges revoked and triggers block changes | `src/main/resources/db/migration/V14__audit_event.sql`; `src/test/java/com/modocument/pgit/AuditAppendOnlyPgIT.java`; `docs/security/audit-events.md` |
| C10 | DSR and alert endpoints need `user:admin`; the audit log needs `audit:read` | `src/main/java/com/modocument/api/controller/DataSubjectController.java`; `src/main/java/com/modocument/api/controller/AlertAdminController.java`; `src/main/java/com/modocument/api/controller/AuditLogController.java` |
| C11 | Quarterly access recertification by each bank's Admin | `docs/security/jml-runbook.md`; `src/main/java/com/modocument/api/controller/UserAdminController.java` (`/api/admin/users/access-review`) |
| C12 | `privileged-change` and `platform-change` alert rules | `docs/security/alerting-and-export-caps.md` |

## DPO

| # | Fact | Source |
|---|---|---|
| D1 | DPO actions per task: MO-001 confirm; MO-018/019 review personal-data access (MO-056); MO-031/032 privacy risks (MO-048); MO-033/034 data-protection controls (MO-059); linked MO-038 | Checklist "DPO Involvement", "DPO Action", Notes |
| D2 | No repo document names the DPO | Repo search of `docs/` for "DPO": no hits outside `docs/compliance/` |

## Conflicts recorded (see §3.4 of MO-001)

| # | Conflict | Sources |
|---|---|---|
| X1 | security@ (SUP-S12): Bhavish (02 SUP-S12) vs Taj Aundoo (gate E5) | 02 workbook; `SECURITY.md`; `README.md` |
| X2 | DMARC (SUP-S10): Bhavish (04 R-SPOOF-01) vs Taj Aundoo | 04 workbook; `README.md`; `docs/security/security-roadmap.md` |
| X3 | D7 DR sign-off: Reezvi Pydiah (repo) vs Taj Aundoo (Gate PDF) | `README.md`; Gate PDF |
| X4 | E7 load test run owner Rushal (repo) vs MO-016/017 owned by Bhavish | `README.md`; Checklist |

## Not found in any source (left as TO CONFIRM)

Board members and Chair, titles, reporting lines, the DPO's identity and line, contract/NDA signatories, spend thresholds, the Pack 07 reviewer's identity, GitHub reviewer and release-manager names, and any existing risk or compliance forum.
