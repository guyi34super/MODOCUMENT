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
  Suspension itself — maker/checker `BANK_SUSPEND`, token kill and
  login/refresh refusal — arrives in wave 2.

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
opens up.

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

## Read API (wave 1)

`GET /api/platform/banks` and `GET /api/platform/banks/{id}` require
`platform:read`. They return `BankSummary` values:

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
| One rogue platform admin | Maker/checker on every platform change; two-admin minimum; the `privileged-change` alert (and the planned `platform-change` rule, contracts §5). |
| Misconfiguration at startup | The bootstrap fails closed and writes nothing; the OIDC validator refuses platform mappings; the platform bank is only recognized when its `Kind` is `PLATFORM`. |

## For wave 2

- `BankDirectory` (`application/port`): `find(bankId)`, `kindOf(bankId)` and
  `isSuspended(bankId)`, cached for `modocument.platform.bank-cache-ttl`
  (default 30 s). Call `refresh(bankId)` after changing a bank.
- `PlatformBankLookup`: `platformBankId()` and `isPlatformBank(id)`.
- `RoleAssignmentPolicy`: `allowedRoles(kind)`, `isAssignable`,
  `requireAssignable` and `MIN_ACTIVE_PLATFORM_ADMINS`.
