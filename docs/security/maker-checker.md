# Maker/checker (dual control) — ws10, gate B7, SEC-023

A critical change is never applied by the person who asked for it. The
requester (the **maker**) submits it; it sits in the approval queue as a
`PendingChange` row in status `REQUESTED` until a **different** user holding
`change:approve` (roles APPROVER and ADMIN) decides it. Approval executes
the change — in the same transaction as the decision — and the row becomes
`APPLIED`; rejection leaves the target untouched. The maker can withdraw an
undecided request (`CANCELLED`).

## Scope

| Change type        | What it does                              | Maker permission | Submitted via                                   | Checker |
|--------------------|-------------------------------------------|------------------|--------------------------------------------------|---------|
| `USER_CREATE`      | Create a user in the maker's bank         | `user:admin`     | `POST /api/admin/users` → 202                    | `change:approve` |
| `USER_ROLE_CHANGE` | Change a user's role (mover, B5)          | `user:admin`     | `PUT /api/admin/users/{id}/role` → 202           | `change:approve` |
| `USER_DISABLE`     | Disable a user (leaver switch, PRIV-017)  | `user:admin`     | `PUT /api/admin/users/{id}/disabled` (true) → 202 | `change:approve` |
| `TEMPLATE_UPDATE`  | Rename/redescribe a template              | `template:write` | `PUT /Templates/Update` → 202                    | `change:approve` |
| `TEMPLATE_DELETE`  | Delete a template (+ fields, rules)       | `template:write` | `DELETE /Templates/{id}` → 202                   | `change:approve` |

Everything can also be submitted through the generic
`POST /api/admin/changes` (`{changeType, payload, comment}`); the use case
enforces the same per-type maker permission, so the queue is never a
privilege shortcut.

**Deliberately out of scope:** re-enabling a user
(`PUT /api/admin/users/{id}/disabled` with `false`) stays a direct,
single-admin operation. It only restores access that two users already
approved granting — the lower-risk direction — and keeping it direct means
an accidental disable can be undone without waiting for a second admin.

## Flow

```
maker                               checker (a DIFFERENT user)
-----                               ---------------------------
POST/PUT the operation
  └─ applier.prepare() validates     GET /api/admin/changes?status=REQUESTED
     (ownership, bank, role vocab,
      password policy, duplicates)   POST /api/admin/changes/{id}/approve
  └─ 202 + PendingChange REQUESTED     ├─ maker == checker?  → 409 +
     audit: CHANGE_REQUESTED           │   SELF_APPROVAL_BLOCKED (DENIED)
                                       ├─ maker/checker bank equality
POST .../{id}/cancel (maker only)      ├─ applier.apply() — the EXISTING
  └─ CANCELLED                         │   use case, re-running every
                                       │   invariant (last-admin guard...)
                                       │   + its own domain audit row
                                       └─ APPLIED; audit: CHANGE_APPROVED
                                     POST .../{id}/reject → REJECTED
```

- The appliers delegate to the pre-ws10 use cases (`UserAdminUseCase`,
  `UpdateTemplateUseCase`, `DeleteTemplateUseCase`), so there is exactly one
  implementation of each business rule, and every invariant is **re-run at
  apply time** — state may have changed between submit and approve. An apply
  that an invariant refuses (e.g. the last-admin guard) rolls the domain
  change back, answers the checker with the refusal, and bookkeeps the row
  as `FAILED` in an independent transaction.
- `PendingChange` carries `BankId` and is covered by the standard RLS tenant
  policy (the afterMigrate callback); the application additionally filters
  every read by the caller's bank and asserts maker/checker bank equality on
  approval (AppUsers is not under RLS).
- **No secrets in the queue:** `USER_CREATE` validates the temporary
  password against the policy at submit time and stores only its Argon2id
  hash; the hash is additionally stripped from every API response, so the
  checker reviews email/role/display name — never credential material.

## Self-approval proof (the B7 test)

`MakerCheckerIntegrationTest.changeStaysPendingSelfApprovalIsHardBlockedAndADifferentAdminApplies`
proves, over the real HTTP stack:

1. admin A submits a role change → 202; the target's role is **unchanged**
   and the target's live session keeps working (nothing applied);
2. admin A approves their own change → **409**, the row stays `REQUESTED`,
   and a `SELF_APPROVAL_BLOCKED` audit row with outcome `DENIED` exists —
   written in its own transaction, so the refusal cannot roll it back;
3. admin B approves → `APPLIED`, the role changed, and the target's
   pre-change access token is dead on its next request (the ws09 B5
   TokenVersion bump + refresh revocation ran inside the approval).

## Configuration

```yaml
modocument:
  security:
    maker-checker:
      # default true; a single-user dev setup may set false —
      # template update/delete then apply directly (ws09 behavior).
      template-changes: ${MODOCUMENT_MAKER_CHECKER_TEMPLATE_CHANGES:true}
```

User-security changes (`USER_CREATE`, `USER_ROLE_CHANGE`, `USER_DISABLE`)
are **never** exempt; there is deliberately no switch for them.

## Bank onboarding needs two admins (SEC-023)

There is no bank-creation endpoint yet, so the two-bank-admins rule is
enforced where it bites today: an onboarding's second administrator can only
come from an approved `USER_CREATE` with role `ADMIN`, which inherently
requires a different user's approval. When bank onboarding ships it MUST
create the bank with **two** named administrators (or one administrator plus
an approver), because a bank with a single admin cannot pass any
maker/checker gate for user-security changes — the first admin's requests
would have nobody to approve them. Keep that invariant in the onboarding
workstream's design review.

## Audit vocabulary

| Action                  | Outcome  | When                                      |
|-------------------------|----------|-------------------------------------------|
| `CHANGE_REQUESTED`      | SUCCESS  | maker submitted                            |
| `SELF_APPROVAL_BLOCKED` | DENIED   | maker tried to approve their own change    |
| `CHANGE_APPROVED`       | SUCCESS  | different-user approval, change applied    |
| `CHANGE_REJECTED`       | SUCCESS  | checker refused                            |
| `CHANGE_CANCELLED`      | SUCCESS  | maker withdrew                             |
| `CHANGE_APPLY_FAILED`   | FAILED   | approved apply hit a changed invariant     |

The applier's own domain rows (`USER_CREATED`, `USER_ROLE_CHANGED`,
`USER_DISABLED`, `TEMPLATE_UPDATED`, `TEMPLATE_DELETED`) are written in the
approval's transaction with the **checker** as actor; `CHANGE_REQUESTED`
names the maker. Together the trail answers "who asked, who approved, what
happened" for every critical change.
