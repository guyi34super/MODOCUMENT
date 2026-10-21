# Credential storage and password-reset design note

Controls: **SEC-020** (password visible to a system administrator — expected answer No), with
SEC-018 and SEC-019. Gate item B2. Written from the Flyway migrations and the code on 2026-10-19.

## 1. Schema: every column that holds a credential

The production schema is built only by the migrations in `src/main/resources/db/migration/`.
Searching them for password, secret, hash and token columns gives this complete list.

| Table.column | Migration | What is stored | Can an administrator or DBA recover the credential? |
|---|---|---|---|
| `AppUsers.PasswordHash` | `V1__baseline_schema.sql` (created), `V40__ws06_password_hash_and_lockout.sql` (widened to 255 for Argon2id) | `{argon2}$argon2id$v=19$m=16384,t=2,p=1$<salt>$<hash>`; legacy BCrypt rows are re-hashed to Argon2id at the next sign-in | No. One-way hash with a per-user salt |
| `RecoveryCode.CodeHash` | `V41__ws06_mfa_tables.sql` | Argon2id hash of an MFA recovery code | No |
| `UserMfa.SecretCiphertext` | `V41__ws06_mfa_tables.sql` | TOTP secret, AES-256-GCM; key `MODOCUMENT_MFA_KEY` lives outside the database | Not from the database alone; the key is in Secrets Manager |
| `RefreshToken.TokenHash` | `V15__refresh_and_revoked_tokens.sql` | SHA-256 of a 256-bit random token | No |
| `RevokedToken.Jti` | `V15__refresh_and_revoked_tokens.sql` | Token id of a logged-out access token (not a credential) | Not applicable |
| `PendingChange.PayloadJson` | `V80__ws10_pending_change.sql` | For user creation and password reset: the Argon2id hash of the temporary password, never plaintext; the hash is stripped from every API response | No |
| `ApiClientSecret.SecretHash` | `V260__ws26_machine_identity.sql` | Argon2id hash of an API client secret | No |
| `ApiClientInitialSecret.SecretCiphertext` | `V260__ws26_machine_identity.sql` | A new machine client's secret, sealed with the connector vault key, readable once by the maker within 24 h, then deleted | Only the maker, once, through the API; never a user password |
| `IntegrationAgent.EnrolmentTokenHash` / `EnrolmentTokenCiphertext` | `V261__ws26_integration_agents.sql` | SHA-256 of a 15-minute one-time enrolment token, and a sealed copy for the maker's single read | Same as above |
| `IntegrationConnection.SecretCiphertext` | `V250__ws25_integration_core.sql` | Credentials MoDocument presents to a bank's own systems (must be reversible), AES-256-GCM with a per-bank key; write-only in the API | Not from the database alone; never a MoDocument user password |

No table has a plaintext password column. The JPA entities mirror these columns
(`src/main/java/com/modocument/domain/entity/AppUser.java`).

## 2. No administrator view of a password

- The API views of a user, `UserDto` and `AdminUserDto`, have no password or hash field
  (`src/main/java/com/modocument/application/dto/UserDto.java`,
  `src/main/java/com/modocument/application/dto/AdminUserDto.java`).
- Pending changes strip `passwordHash` from every response
  (`src/main/java/com/modocument/application/usecase/PendingChangeUseCase.java`).
- Audit events for password events carry no old or new values
  (`src/main/java/com/modocument/application/usecase/AuthUseCase.java`).
- Logs mask password, secret and token fields (`src/main/java/com/modocument/application/privacy/PiiPatterns.java`,
  `LogRedactionCanaryTest`).

## 3. Reset design (no reset tokens or links)

MoDocument does not send password-reset links or codes. A forgotten password is reset like this:

1. A bank administrator (permission `USER_ADMIN`) requests a temporary password for another user
   of the same bank: `POST /api/admin/users/{id}/password-reset`. The request is a maker/checker
   change `USER_PASSWORD_RESET`; an administrator cannot reset their own password this way
   (`src/main/java/com/modocument/api/controller/UserAdminController.java`).
2. At submit, the temporary password is checked against the password policy and replaced by its
   Argon2id hash; the plaintext is not stored
   (`src/main/java/com/modocument/application/usecase/UserPasswordResetChangeApplier.java`).
3. A second administrator approves. The account gets the new hash and `MustChangePassword=true`;
   its `TokenVersion` is bumped and its refresh tokens are revoked, so every existing session ends
   (`src/main/java/com/modocument/application/usecase/UserAdminUseCase.java`).
4. Until the user chooses their own password, every route except profile, password change,
   logout and MFA answers 403 `PASSWORD_CHANGE_REQUIRED`
   (`src/main/java/com/modocument/infrastructure/security/PasswordChangeRequiredGate.java`,
   `src/main/resources/db/migration/V70__ws09_user_admin.sql`).
5. Accounts owned by an identity provider (Entra ID, Keycloak) are refused: their password is
   reset in the provider.

The maker who typed the temporary password knows it until the user changes it at first sign-in.
That is the accepted design for an admin-issued reset. Two weaknesses remain: the temporary
password does not expire, and it is handed to the user outside MoDocument (channel
`TO CONFIRM (organisational)`).

Bootstrap admin passwords come from deployment secrets and are also forced to change at first
sign-in (`src/main/java/com/modocument/infrastructure/bootstrap/BootstrapAdminRunner.java`).
