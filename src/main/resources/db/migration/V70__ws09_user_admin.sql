-- ws09 (bank-scoped user administration + JML, gates B1/B10, PRIV-017):
-- account lifecycle flags on AppUsers.
--
--  * Disabled — the leaver switch. Set by a bank Admin through
--    PUT /api/admin/users/{id}/disabled; the same action bumps TokenVersion
--    and revokes every refresh token, and the RevocationValidator rejects
--    tokens of disabled users on the very next request, so a disabled
--    account is inert immediately (login, refresh and existing access
--    tokens all die with the same generic 401).
--  * MustChangePassword — set when an administrator creates an account with
--    a temporary password. While set, login succeeds but every authenticated
--    route except self-service (profile/password/logout/MFA) answers 403
--    PASSWORD_CHANGE_REQUIRED; a successful password change clears it.
--    OIDC JIT-provisioned accounts never get the flag — the IdP owns their
--    credential.
--
-- AppUsers is deliberately NOT under RLS (see V13): bank scoping for user
-- administration is enforced in application code — every user-admin query
-- filters by the calling Admin's own BankId.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the AppUser entity must be kept in
-- sync with this file by hand.

ALTER TABLE AppUsers ADD COLUMN Disabled boolean NOT NULL DEFAULT false;
ALTER TABLE AppUsers ADD COLUMN MustChangePassword boolean NOT NULL DEFAULT false;
