# JML runbook — joiner / mover / leaver (ws09)

Scope: application accounts in MoDocument (`AppUsers`). Every account belongs
to exactly one bank; a bank **Admin** manages only their own bank's users
(gate B6 — MoDocument staff cross-bank administration is out of scope). All
operations below are available in the UI under **Administration → Users**
(`/admin/users`) and over the API under `/api/admin/users` (permission
`user:admin`, granted to the ADMIN role only). Every operation writes an
append-only audit row; the audit trail is the JML evidence.

## Joiner

Two front doors, depending on how the bank signs in:

1. **Local password accounts** — an Admin creates the account
   (`POST /api/admin/users` / the *Create user* dialog) with email, display
   name, role and a **temporary password**. The temporary password goes
   through the full password policy (length, breached-password and
   email-derivation checks), and the account is created with
   `MustChangePassword` set:
   * The joiner's first login succeeds but buys **only** self-service —
     every other route answers `403 PASSWORD_CHANGE_REQUIRED` until the
     joiner sets their own password (the UI routes them straight to the
     forced password-change page).
   * The password change clears the flag and ends the session; the joiner
     signs in again with a password only they know.
   * Evidence: `USER_CREATED` audit row (actor = the Admin, no password
     material), followed by the joiner's own `PASSWORD_CHANGED` row.
2. **IdP-owned accounts (OIDC)** — the account is JIT-provisioned on first
   SSO login into the provider's configured bank and default role (ws07).
   `MustChangePassword` stays **false**: the IdP owns the credential and
   MoDocument never issues one. Evidence: `USER_PROVISIONED_OIDC` /
   `IDENTITY_LINKED` audit rows.

Least privilege at the gate: pick the lowest role that does the job
(`READ_ONLY` unless there is a reason otherwise); the role→permission
matrix is `docs/security/role-permission-matrix.csv`.

## Mover

Role changes happen in place (`PUT /api/admin/users/{id}/role` / the role
dropdown). The change is immediate, not eventual:

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
* Evidence: `USER_ROLE_CHANGED` audit row with old/new role.

## Leaver

Disable the account the day the person leaves
(`PUT /api/admin/users/{id}/disabled` with `{"disabled": true}` / the
*Disable* button). The account is **inert the same moment**:

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
leaver test (`UserAdminIntegrationTest.disabledLeaverIsInertImmediately…`)
that pins all three failure paths. Accounts are disabled, not deleted —
their identity stays referenceable from the audit trail (B1).

Re-joining: re-enable with `{"disabled": false}` (`USER_ENABLED` audited);
no old session survives, the person signs in afresh. For a local account,
follow up with a role review and, if the credential is in doubt, a fresh
temporary password via your reset procedure.

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
