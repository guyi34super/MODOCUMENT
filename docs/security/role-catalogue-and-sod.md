# Role catalogue & separation of duties — ws11, gates B8/C10, SEC-012, SEC-024

This document is the prose rendering of two pieces of code that the build
pins to it:

- `RolePermissions` — the role → permission matrix, byte-identical to
  [`role-permission-matrix.csv`](role-permission-matrix.csv)
  (`RolePermissionMatrixTest`);
- `SodPolicy` — the SoD conflict table as data, exercised by
  `SodPolicyTest` and the gate tests named below.

## 1. Role catalogue (SEC-012)

Every bank user holds **exactly one** of four bank roles (a single
enum-typed column on `AppUsers`; see §3 on why that is itself an SoD
control). Phase 2 adds two roles that are never a bank user's role —
`PLATFORM_ADMIN` and `API_CLIENT`, see the end of this section. The mapping
to banking job functions:

| Role         | Banking job function                                                                 | Permission profile (full matrix: [CSV](role-permission-matrix.csv)) |
|--------------|--------------------------------------------------------------------------------------|---------------------------------------------------------------------|
| `READ_ONLY`  | Auditors, controllers, second-line risk — people who must SEE operations, never move them. | Every domain `*:read` + self-service. No writes, exports, uploads or LLM spend (PRIV-005). |
| `READ_WRITE` | Operations staff / report authors — the makers: they design templates, ingest data, author and generate documents. | `READ_ONLY` plus every `*:write`, uploads, AI use, exports, extraction runs, `doc:generate`. |
| `APPROVER`   | Supervisors / four-eyes checkers — they review, they do not author.                   | `READ_ONLY` plus the two approval verbs: `change:approve` (ws10) and `doc:release` (ws11). |
| `ADMIN`      | The bank's application owner / IT administration — user management, bank settings, integrations, governance functions (retention run, SoD scan). | Everything a bank can hold, including `user:admin`, `settings:*`, `integration:admin`. Not the platform-owner permissions (incl. `lookup:admin`: the lookup tables are shared by every bank) and not the API-client scopes. |

**Phase-2 roles outside the bank catalogue**

| Role | Who | Permission profile |
|---|---|---|
| `PLATFORM_ADMIN` (ws21) | MO Intelligence staff, only in the single platform bank (enforced by a DB trigger and `RoleAssignmentPolicy`). | `platform:*` + `lookup:admin`, plus managing their own platform-bank users, approving platform changes, the platform bank's audit feed, the version. **No domain permission**: no bank's templates, jobs or data. Platform changes (bank onboarding/suspension, platform settings, egress rules) are maker/checker between two platform admins. |
| `API_CLIENT` (ws26) | A bank system's machine principal, never an `AppUsers` row. | Only the `integration:*` / `agent:connect` scopes, further narrowed by the client's granted scopes; bound to one bank. |

Which roles a user may hold follows the kind of their bank
(`RoleAssignmentPolicy`, ws21): a customer bank hands out the four bank
roles only, the platform bank `PLATFORM_ADMIN` only — so platform admins
manage their own colleagues through the same maker/checker user
administration, and nobody can grant `PLATFORM_ADMIN` inside a customer
bank (a V210 database trigger backs this). `API_CLIENT` is never assignable,
and OIDC maps bank roles only and never provisions into the platform bank.
See [platform-owner.md](../platform/platform-owner.md).

**Job-function customization:** banks that model finer-grained job functions
in their IdP do not need new MoDocument roles — the OIDC integration maps an
IdP group/claim onto one of these four (`role-claim`, with `default-role` as
the fallback; see [identity-providers.md](identity-providers.md)). The
four-role catalogue is the enforcement vocabulary; the IdP owns who holds
which job function, and ws10's maker/checker gate covers role changes made
inside MoDocument.

## 2. SoD conflict table (SEC-012)

Conflicts are defined **operation-level**, as data, in
`application/security/SodPolicy.Operation` — pairs of duties on one object
that one person must never perform both of:

| Operation (`SodPolicy.Operation`) | Object        | Duty A            | Duty B   | Runtime refusal (409) + DENIED audit | Gate |
|-----------------------------------|---------------|-------------------|----------|--------------------------------------|------|
| `CHANGE_APPROVAL`                 | `PendingChange` | maker (requested) | checker (approves) | `SELF_APPROVAL_BLOCKED`     | B7 (ws10) |
| `DOC_RELEASE`                     | `DocInstance`   | author (owner) **and** submitter | releaser (releases/rejects) | `DOC_SELF_RELEASE_BLOCKED` | B8 (ws11) |

Both enforcement points call the one shared mechanism,
`SodPolicy.verifyDistinct(actor, otherParty, operation)` — a new conflicting
operation is a new enum constant plus one call site, never a new ad-hoc `if`.

**Why there are no role-PAIR conflicts.** Classic SoD matrices forbid toxic
role combinations ("may not hold Author and Approver together"). In
MoDocument that table would be empty by construction: a user holds exactly
one role (single `Role`-typed column — no role set exists to combine), and a
role change *replaces* the role (and is itself maker/checker-gated, ws10).
`SodPolicyTest` asserts this structure so a future "secondary roles" feature
fails the test and forces this analysis to be redone. What single-role
cannot prevent is one PERSON on both sides of one OBJECT — an `ADMIN` is
maker and checker within one role; a report author later granted `APPROVER`
is author and releaser of their old drafts. Hence the operation-level table
above, enforced per object at runtime.

## 3. The three enforcement layers (SEC-024)

| Layer | Where | What it does | Proven by |
|-------|-------|--------------|-----------|
| **1. Assignment time** | `AppUsers.Role` column + ws10 | Single-role-per-user is structural (no toxic combination can be assigned); role changes go through maker/checker, so nobody grants themselves a reviewing role unreviewed. | `SodPolicyTest` (structure), `MakerCheckerIntegrationTest` (role change needs a second admin) |
| **2. Runtime** | `PendingChangeUseCase.approve`, `DocReleaseUseCase.release/reject` via `SodPolicy.verifyDistinct` | The hard gate at the moment of action: maker ≠ checker, releaser ∉ {author, submitter} — **even when the author holds the Approver or Admin role** (B8). 409 + DENIED audit row (`SELF_APPROVAL_BLOCKED` / `DOC_SELF_RELEASE_BLOCKED`), written in its own transaction so the evidence survives the refusal. | `MakerCheckerIntegrationTest` (B7), `DocReleaseSodIntegrationTest` (B8) |
| **3. Nightly scan** | `infrastructure/sod/SodScanJob` (daily cron `modocument.sod.cron`, default 03:23; manual `POST /api/admin/sod/scan`, `user:admin`) | Re-verifies the invariants over the persisted data, per bank under that bank's tenant context: `APPLIED` changes with `MakerUserId == CheckerUserId` (expect 0) and `RELEASED` instances with `ReleasedByUserId ∈ {OwnerUserId, SubmittedByUserId}` (expect 0). Each bank's run writes `SOD_SCAN_COMPLETED` (SUCCESS, counts); each hit writes `SOD_VIOLATION_FOUND` (DENIED, parties named) — a hit means the runtime gate was bypassed (direct DB write, bug, restored backup) and is an alerting event. | `DocReleaseSodIntegrationTest` (clean scan = 0; a manufactured violation is found and audited) |

## 4. The ws11 release workflow (gate B8) in brief

```
author (owner, doc:generate)          approver (doc:release, a DIFFERENT user)
----------------------------          -----------------------------------------
generate → status GENERATED           GET  /api/doccreation/instances/pending-release
POST .../instances/{id}/submit          └─ bank-wide queue, with the full content
  └─ SUBMITTED, audit DOC_SUBMITTED        blocks and the aiComposed flag (C10)
                                      GET  .../instances/{id}/review-file?format=…
                                        └─ the document exactly as last generated
                                      POST .../instances/{id}/release {comment}
                                        ├─ releaser == author or submitter? →
                                        │   409 + DOC_SELF_RELEASE_BLOCKED (DENIED)
                                        └─ RELEASED; audit DOC_RELEASED
                                           (reviewComment + aiComposed)
                                      POST .../instances/{id}/reject {comment}
                                        └─ back to GENERATED (rejection is a
                                           transition, not a status); audit
                                           DOC_RELEASE_REJECTED; the author sees
                                           the comment and resubmits
```

- The author keeps generate/download of their own instance in every status
  (working copy); regeneration never changes a SUBMITTED/RELEASED status.
- **C10 (human review of AI output):** `DocInstance.AiComposed` records
  whether the last generation's content came from the AI composer. The
  approver's queue shows the flag and the content itself; releasing an
  AI-composed document therefore IS the human check, and the
  `DOC_RELEASED` audit event carries `aiComposed` plus the approver's
  `reviewComment` as evidence.
