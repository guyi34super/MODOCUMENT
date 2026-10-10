# JML runbook — joiner / mover / leaver (ws09)

Scope: application accounts in MoDocument (`AppUsers`). Every account belongs
to exactly one bank; a bank **Admin** manages only their own bank's users
(gate B6 — MoDocument staff cross-bank administration is out of scope). All
operations below are available in the UI under **Administration → Users**
(`/admin/users`) and over the API under `/api/admin/users` (permission
`user:admin`, granted to the ADMIN role only). Every operation writes an
append-only audit row; the audit trail is the JML evidence.

**Dual control (ws10, SEC-023):** creating a user, changing a role and
disabling an account are maker/checker-gated — the request answers
`202 Accepted` with a pending change, and a **different** user holding
`change:approve` must approve it (**Administration → Approvals**,
`/api/admin/changes`) before anything happens. Self-approval is a hard 409.
Only re-enabling applies directly. Details: `docs/security/maker-checker.md`.

## Joiner

Two front doors, depending on how the bank signs in:

1. **Local password accounts** — an Admin requests the account
   (`POST /api/admin/users` / the *Create user* dialog) with email, display
   name, role and a **temporary password**; a second admin approves, and
   only then does the account exist. The temporary password goes through
   the full password policy (length, breached-password and email-derivation
   checks) at request time and is stored only as a hash on the pending
   change; the account is created with `MustChangePassword` set:
   * The joiner's first login succeeds but buys **only** self-service —
     every other route answers `403 PASSWORD_CHANGE_REQUIRED` until the
     joiner sets their own password (the UI routes them straight to the
     forced password-change page).
   * The password change clears the flag and ends the session; the joiner
     signs in again with a password only they know.
   * Evidence: `CHANGE_REQUESTED` (the requesting Admin) and
     `CHANGE_APPROVED` + `USER_CREATED` rows (the approving Admin, no
     password material), followed by the joiner's own `PASSWORD_CHANGED`
     row.
2. **IdP-owned accounts (OIDC)** — the account is JIT-provisioned on first
   SSO login into the provider's configured bank and default role (ws07).
   `MustChangePassword` stays **false**: the IdP owns the credential and
   MoDocument never issues one. Evidence: `USER_PROVISIONED_OIDC` /
   `IDENTITY_LINKED` audit rows.

Least privilege at the gate: pick the lowest role that does the job
(`READ_ONLY` unless there is a reason otherwise); the role→permission
matrix is `docs/security/role-permission-matrix.csv`.

## Mover

Role changes are requested in place (`PUT /api/admin/users/{id}/role` / the
role dropdown) and apply the moment a second admin approves the pending
change. On approval the change is immediate, not eventual:

* The target's `TokenVersion` is bumped — the `RevocationValidator` rejects
  **every outstanding access token** on its very next request — and every
  refresh-token family is revoked, so the old privileges cannot be used nor
  silently re-minted. The person signs in again and receives the new role.
* Guards: an Admin cannot change their own role, and the only remaining
  active Admin of a bank cannot be demoted (409 — promote a successor
  first).
* For IdP-managed banks with a configured role claim, the role also follows
  the IdP on each SSO login (`USER_ROLE_MAPPED_OIDC`); the mover procedure
  is then performed in the IdP and verified here.
* Evidence: `CHANGE_REQUESTED`/`CHANGE_APPROVED` rows plus the
  `USER_ROLE_CHANGED` audit row with old/new role.

## Leaver

Request the disable the day the person leaves
(`PUT /api/admin/users/{id}/disabled` with `{"disabled": true}` / the
*Disable* button) and have the second admin approve it right away — the
four-eyes gate is two clicks, not a delay. The account is **inert the
moment the approval lands**:

* the existing access token dies at the `RevocationValidator` on its next
  request (the `Disabled` flag is checked per request, plus a
  `TokenVersion` bump),
* every refresh token is revoked, so the session cannot be refreshed,
* a fresh login — password **or** SSO — is refused with the same generic
  401 as any bad credential (no signal to the leaver),
* MFA's second phase and in-flight challenges are refused too.

Guards: you cannot disable yourself, and you cannot disable the bank's only
remaining active Admin.

Evidence (PRIV-017): the `USER_DISABLED` audit row, plus the automated
leaver test
(`UserAdminIntegrationTest.disabledLeaverIsInertImmediatelyAfterApproval…`)
that pins all three failure paths. Accounts are disabled, not deleted —
their identity stays referenceable from the audit trail (B1).

Re-joining: re-enable with `{"disabled": false}` (`USER_ENABLED` audited) —
this direction applies directly, without a second approval (ws10: restoring
access that two users already approved granting is the lower-risk
direction); no old session survives, the person signs in afresh. For a local account,
follow up with a role review and, if the credential is in doubt, a fresh
temporary password via your reset procedure.

## Helpdesk actions (phase 2, ws22)

From *Administration → Users*, on any user of your bank except yourself:

* **Unlock** (`POST /api/admin/users/{id}/unlock`, direct): ends a login
  lockout early and resets the failure counters; the password is unchanged.
  Evidence: `USER_UNLOCKED`.
* **Reset password** (`POST /api/admin/users/{id}/password-reset`,
  maker/checker `USER_PASSWORD_RESET`): the temporary password must pass
  the password policy and only its hash is queued. On approval every
  session of the user ends, any lockout is lifted and the user must choose
  a new password at the next sign-in. Hand the temporary password over a
  separate channel. Not available for SSO (IdP-managed) accounts — reset
  those at the IdP. Evidence: `USER_PASSWORD_RESET`.
* **Reset MFA** (`POST /api/admin/users/{id}/mfa-reset` with a reason,
  maker/checker `USER_MFA_RESET`): for a lost or replaced authenticator.
  On approval the enrolment and recovery codes are deleted and every
  session ends; administrators are forced to enrol again at the next
  sign-in. Evidence: `USER_MFA_RESET` (with the reason).
* **Edit** (`PATCH /api/admin/users/{id}`, direct): display name only — the
  email is the sign-in identity and cannot be changed. Evidence:
  `USER_UPDATED` with old/new name.

## Quarterly access recertification (SEC-013)

Once per quarter, each bank Admin:

1. Opens **Administration → Users → Access review**
   (`GET /api/admin/users/access-review`). The worksheet lists every
   account of the bank with role, disabled state, MFA state, last login,
   days since last login and the `inactive90d` flag (no login for 90 days;
   a never-used account older than 90 days is flagged too). Generating the
   worksheet is itself audited (`ACCESS_REVIEW_GENERATED`) — that row is
   the "review happened" evidence.
2. Downloads the user list (`GET /api/admin/users/export`, gate B10) —
   `users-{bankCode}-{date}.csv` with last-login dates — and archives it
   with the review sign-off.
3. For every row, confirms: the person is still employed and still needs
   the role. `inactive90d` rows are disable-by-default unless justified.
4. Executes the outcomes with the mover/leaver procedures above; the
   resulting `USER_ROLE_CHANGED` / `USER_DISABLED` rows close the loop.

## Drift note

The role→permission matrix is **code-pinned**: `RolePermissionMatrixTest`
fails the build if `RolePermissions` and
`docs/security/role-permission-matrix.csv` diverge, and
`RoutePermissionCoverageTest` fails it if any route ships unguarded. There
is therefore no runtime permission state that can drift — "access drift"
reduces to exactly what the quarterly review inspects: **who holds which
role**, and whether dormant accounts are still enabled.
