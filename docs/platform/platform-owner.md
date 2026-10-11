# Platform owner (ws21)

MO Intelligence runs MoDocument for many banks. Its own staff sign in as
**platform admins**: they manage banks and platform-wide defaults, and they
**never see a bank's data**. This page covers how the platform owner is set
up, what is guaranteed, and how each guarantee is enforced. Contracts:
[docs/phase2/contracts.md](../phase2/contracts.md) §1 and §3. Roles:
[role-catalogue-and-sod.md](../security/role-catalogue-and-sod.md).

## Model

- The platform owner lives in **one dedicated bank**: `Bank.Kind = PLATFORM`,
  code `modocument.platform.bank-code` (default `MOI-PLATFORM`), name
  "MO Intelligence Platform". Every other bank is `Kind = CUSTOMER`.
- Its users hold the role `PLATFORM_ADMIN`, and only that role. Because they
  belong to a bank like everyone else, RLS, tokens, user administration and
  maker/checker work for them unchanged.
- `PLATFORM_ADMIN` holds the `platform:*` permissions plus `lookup:admin`,
  managing its own bank's users, approving changes, its own bank's audit
  feed and the version. It holds **no domain permission**: no templates,
  ingestion, extraction, documents or exports of any bank.
- `Bank.Status` is `ACTIVE` or `SUSPENDED` (`SuspendedAt` records when).
  See [Bank lifecycle](#bank-lifecycle-wave-2) for onboarding, suspension,
  reactivation and the break-glass administrator.

## Bootstrap

`BootstrapPlatformRunner` runs at startup, right after the first-admin
bootstrap. Set both variables to seed the platform owner:

| Variable | Value |
|---|---|
| `MODOCUMENT_PLATFORM_ADMIN_EMAILS` | Two distinct emails, comma-separated. |
| `MODOCUMENT_PLATFORM_ADMIN_PASSWORDS_FILE` (preferred) or `MODOCUMENT_PLATFORM_ADMIN_PASSWORDS` | The two temporary passwords, in the same order: one per line in the file, or comma-separated in the variable. |
| `modocument.platform.bank-code` (optional) | The platform bank's code; default `MOI-PLATFORM`. |

What happens:

1. If neither variable is set, the runner logs and skips. This is the dev/test
   default, and the default for a deployment without a platform owner yet.
2. A half or malformed configuration fails startup. That covers one variable
   without the other, anything other than exactly two distinct emails or two
   passwords, a password that fails the password policy (there is no
   weak-password override for the platform owner), an email that already has
   an account, or a platform code that names a customer bank.
3. Otherwise, in **one transaction**, it does the following:
   - creates the platform bank if it is absent (audited `PLATFORM_BOOTSTRAPPED`);
   - creates the two `PLATFORM_ADMIN` accounts with `MustChangePassword`
     (audited `BOOTSTRAP_ADMIN_CREATED`, role `PLATFORM_ADMIN`, the same action
     as the first bank admin, so the `privileged-change` alert fires).
4. Seeding runs **once**. As soon as the platform bank has any `PLATFORM_ADMIN`,
   the variables are ignored. Leaving them set never resets a password.

On first login each platform admin must enrol TOTP (see "Hard MFA" in the
next section). They then change the temporary password before anything else
opens up. The dev-only `MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE=true`
(shared with the first-admin bootstrap below) drops the forced password
change — never the TOTP — and is refused at startup unless
`modocument.security.permit-dev-paths=true`.

## First run: two administrators in the default bank (OBS-01, OBS-02)

Every user change is maker/checker, so a bank with **one** administrator can
never add anyone. `BootstrapAdminRunner` therefore seeds, when `AppUsers` is
empty:

| Variable | Value |
|---|---|
| `MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` / `MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD` (or `_FILE`) | The first ADMIN of the `DEFAULT` bank (required on a fresh production install). |
| `MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL` / `MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD` (or `_FILE`) | Optional second ADMIN — the first one's checker. Both or neither; a different email; the password policy applies (same `MODOCUMENT_BOOTSTRAP_ADMIN_ALLOW_WEAK` dev override). |
| `MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE` | Dev only (see above). |

- Both accounts are created in one transaction, with `MustChangePassword`
  (the passwords came from deployment configuration) and the usual forced
  TOTP enrolment for ADMINs. A half or clashing configuration refuses
  startup and writes nothing.
- At **every** startup the runner logs a loud warning when the `DEFAULT`
  bank has users but fewer than two enabled ADMIN/APPROVER accounts. On a
  running install the fix is the platform owner's break-glass
  `BANK_ADMIN_ADD` (below); seeding never runs again once users exist.

## Guarantees and how they are enforced

| Guarantee | Enforcement |
|---|---|
| Exactly one platform bank | V210 unique partial index `ux_bank_single_platform`. A bank's `Kind` never changes (`trg_bank_kind_immutable`), so a customer bank cannot be turned into the platform bank. |
| `PLATFORM_ADMIN` exists only in the platform bank, and only `PLATFORM_ADMIN` exists there | `RoleAssignmentPolicy` (user admin, `USER_CREATE`/`USER_ROLE_CHANGE` payloads, bootstrap). DB backstop: the V210 `SECURITY DEFINER` trigger on `AppUsers` checks INSERT and every UPDATE of `Role`/`BankId`. It reads `Bank` past RLS, has a pinned `search_path`, and no role has EXECUTE on it. |
| Platform permissions only inside the platform bank | `PermissionChecker` requires two things for any `PLATFORM_*` permission (and `lookup:admin`) **and** for any permission held through the `PLATFORM_ADMIN` role: the role grant, and a token whose `bank_id` is the platform bank. Without a platform bank, nobody gets a platform permission. |
| No customer data for the platform owner | `PLATFORM_ADMIN` has no domain permissions (role matrix). RLS still scopes it to its own bank. The platform console reads cross-bank only in named use cases (`PlatformBankUseCase`), as `runAsSystem`, and returns **counts only**. |
| Hard MFA | `AuthUseCase` forces TOTP enrolment for `PLATFORM_ADMIN` at login, whatever `modocument.security.mfa.require-for-admin` says. |
| No IdP path into the platform | OIDC refuses three things: a provider mapped to the platform bank (at startup, under every profile, and at login), an identity that resolves to a platform account, and non-bank roles in role claims. |
| Two-person control survives | Disabling or demoting a `PLATFORM_ADMIN` that would leave fewer than two active ones is refused with 409. Every platform change is maker/checker between two platform admins. |

## Read API

`GET /api/platform/banks` and `GET /api/platform/banks/{id}` require
`platform:read` (the overview is described under the bank lifecycle below). They return `BankSummary` values:

```
{bankId, code, name, kind, status, users, mfaEnrolledPct, jobs7d, failures7d,
 aiShare7d, codeShare7d, lastActivityAt}
```

- `users` counts enabled accounts. `mfaEnrolledPct` is the share of them with
  active TOTP, from 0 to 100.
- `jobs7d` and `failures7d` count ingestion jobs created in the last 7 days.
- `lastActivityAt` is the latest sign-in.
- `aiShare7d` and `codeShare7d` stay `null` until parse provenance lands
  (ws23).

No email, file name, error text or other row content is returned. An unknown
id returns 404.

## Threats and mitigations

| Threat | Mitigation |
|---|---|
| A platform admin as a path into a bank's data | No domain permissions; RLS; counts-only cross-bank reads in named `runAsSystem` call sites (registry in `TenantContext`). |
| A bank admin escalating to platform admin | `RoleAssignmentPolicy` refuses `PLATFORM_ADMIN` in customer banks; the DB trigger refuses the row; `PermissionChecker` refuses platform permissions outside the platform bank; OIDC never maps to it. |
| A forged, stale or mis-provisioned `PLATFORM_ADMIN` claim for another bank | `PermissionChecker` bank check; `RevocationValidator` (token version, disabled). |
| A stolen platform admin password | Mandatory TOTP; lockout; forced password change on first login. |
| One rogue platform admin | Maker/checker on every platform write except reactivation; two-admin minimum; the `privileged-change` alert and the `platform-change` rule (contracts §5); every bank change is audited in the customer bank too, so its own admins see who touched it. |
| A suspended bank's users keep working | Suspension bumps every user's TokenVersion and revokes every refresh token in the same transaction; login, MFA verify, refresh and OIDC re-check the bank status uncached; `RevocationValidator` refuses tokens of a suspended bank (cached ≤ `bank-cache-ttl`). |
| Misconfiguration at startup | The bootstrap fails closed and writes nothing; the OIDC validator refuses platform mappings; the platform bank is only recognized when its `Kind` is `PLATFORM`. |

## Bank lifecycle (wave 2)

All routes need `platform:banks` (PLATFORM_ADMIN of the platform bank). The
three maker/checker routes answer **202** with the pending change; a
**second** platform admin approves it with
`POST /api/admin/changes/{id}/approve` (the maker approving their own gives
409). Refusals reach the maker at submit (400/404/409).

| Route | Change | Rules |
|---|---|---|
| `POST /api/platform/banks` `{code, name, users:[{email, displayName, role, temporaryPassword}]}` | `BANK_CREATE` | Code: 2–40 of `A–Z 0–9 - _` (upper-cased), unused, not the platform code. 2–20 users with unique, unused emails; bank roles only; **at least one ADMIN and at least two ADMIN/APPROVER** (otherwise the new bank could never approve its own changes). Each temporary password passes the password policy and is hashed at submit. |
| `POST /api/platform/banks/{id}/suspend` `{reason}` | `BANK_SUSPEND` | A customer bank that is not suspended; the platform bank is refused (409). |
| `POST /api/platform/banks/{id}/reactivate` | — (direct, 200 + `BankSummary`) | The lower-risk direction. Nobody's session comes back; users sign in afresh. |
| `POST /api/platform/banks/{id}/admins` `{email, displayName, temporaryPassword, reason}` | `BANK_ADMIN_ADD` | Break-glass: a new ADMIN for a customer bank (the platform bank's own admins are managed through `/api/admin/users`). |
| `GET /api/platform/overview` (`platform:read`) | — | Every CUSTOMER bank as a `BankSummary` plus `totals:{banks, activeBanks, suspendedBanks, users}`. Counts only. |

**Applying.** The approval transaction is bound to the platform bank, under
whose RLS no other bank is visible, so each applier runs its writes in a
transaction of its own begun as SYSTEM (`PlatformBankLifecycleUseCase`,
registered in the `TenantContext` call-site list):

- `BANK_CREATE` — creates the CUSTOMER bank and, in the same transaction
  (under `runAs(newBank)`), every first user with `MustChangePassword`
  (ADMINs must also enrol TOTP at first sign-in). One transaction: the bank
  exists with all of its first users or not at all.
- `BANK_SUSPEND` — `Status=SUSPENDED`, `SuspendedAt`, every user's
  `TokenVersion` + 1 and every refresh token revoked. From then on sign-in
  (password, MFA verify, OIDC) answers the generic 401 and audits
  `LOGIN_FAILED` with `reason: bank_suspended`; a refresh answers 401
  **without** the token-theft path (no `TOKEN_REFRESH_REUSE_DETECTED`
  storm); `RevocationValidator` also refuses any token of the bank.
- `BANK_ADMIN_ADD` — the ADMIN with `MustChangePassword`.

**Audit** (action, where):

| Action | Customer bank | Platform bank | Values |
|---|---|---|---|
| `BANK_ONBOARDED` | ✓ | ✓ | code, name, users, admins, makerUserId, checkerUserId |
| `USER_CREATED` (per user, `via`) | ✓ | — | email, displayName, role, mustChangePassword |
| `BANK_SUSPENDED` | ✓ | ✓ | reason, accountsSignedOut, refreshTokensRevoked, makerUserId, checkerUserId |
| `BANK_REACTIVATED` | ✓ | ✓ | status before/after |
| `BANK_ADMIN_ADDED` | ✓ | ✓ | email, userId, reason, makerUserId, checkerUserId |

The `CHANGE_REQUESTED` / `CHANGE_APPROVED` trail stays in the platform bank.
A refused login of a suspended bank is `LOGIN_FAILED` in that bank.

**Rate limit.** Onboarding and break-glass submissions hash passwords
(Argon2id) and share the `admin-security` bucket (10 per user per hour).

**Atomicity note.** The system transaction commits before the approval
bookkeeping does. Should that bookkeeping fail afterwards, the change stays
REQUESTED and approving it again fails on the taken code or email (FAILED) —
nothing is applied twice.

### Console (frontend)

`/platform` (overview: KPI tiles and the banks table), `/platform/banks`
(every bank, and the "Onboard bank" dialog with first-user rows — email,
display name, role, temporary password with Generate), `/platform/banks/:id`
(figures, "Suspend bank" with a reason, "Reactivate bank", "Add admin"
break-glass). A PLATFORM_ADMIN sees only the Platform, Administration
(Users, Approvals) and Account sections, and `/` redirects to `/platform`.

## Building blocks

- `BankDirectory` (`application/port`): `find(bankId)`, `kindOf(bankId)`,
  `isSuspended(bankId)` (cached for `modocument.platform.bank-cache-ttl`,
  default 30 s) and `isSuspendedUncached(bankId)` for session-opening
  decisions. Call `refresh(bankId)` after changing a bank.
- `PlatformBankLookup`: `platformBankId()` and `isPlatformBank(id)`.
- `RoleAssignmentPolicy`: `allowedRoles(kind)`, `isAssignable`,
  `requireAssignable` and `MIN_ACTIVE_PLATFORM_ADMINS`.
