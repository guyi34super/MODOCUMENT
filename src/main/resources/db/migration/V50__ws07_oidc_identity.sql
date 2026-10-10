-- ws07 (OIDC login paths: Entra ID + Keycloak, gate B2/B3/SEC-014): external
-- identity link on AppUsers. The IdP only authenticates; MoDocument then
-- issues its own tokens — these columns tie an account to its IdP identity
-- so JIT provisioning can match returning users by (provider, subject)
-- before falling back to email:
--  * AuthProvider    — which front door the account signs in through: NULL
--                      means local password login (break-glass accounts),
--                      else the OIDC registrationId ('entra', 'keycloak').
--  * ProviderSubject — the IdP's stable subject (the sub claim); a renamed
--                      email at the IdP must not detach the account.
--
-- The partial unique index enforces one account per IdP identity without
-- constraining the (NULL, NULL) local accounts.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the new AppUser fields and the
-- @Table unique constraint ux_appusers_provider_subject must be kept in sync
-- with this file by hand (a plain unique constraint there — both engines
-- skip rows with NULLs, matching this partial index for real links).

ALTER TABLE AppUsers ADD COLUMN AuthProvider varchar(40);
ALTER TABLE AppUsers ADD COLUMN ProviderSubject varchar(255);

CREATE UNIQUE INDEX IF NOT EXISTS ux_appusers_provider_subject
    ON AppUsers (AuthProvider, ProviderSubject)
    WHERE ProviderSubject IS NOT NULL;
