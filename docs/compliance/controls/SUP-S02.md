# SUP-S02 — Token architecture: JWT/OAuth hardening and revocation

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 03 Software Engineering | Software Engineering | P1 | Partially Implemented | SUP-S02-E01 | SUP-S02-T01 | R-SUP-S02 |

## Requirement
Tokens used by the SPA and the API are short-lived, pinned to one algorithm and revocable: refresh
tokens rotate with reuse detection, and logout or a role change ends existing tokens (gate B5).

## How MoDocument meets it today
- **Short-lived access tokens.** User access tokens live 15 minutes by default; the platform
  setting `security.jwt.ttlMinutes` allows 5–15 (`src/main/java/com/modocument/infrastructure/security/JwtService.java`,
  `src/main/java/com/modocument/infrastructure/settings/SettingDefinitions.java`). Client tokens
  for bank systems live 15 minutes, bank setting 5–15 minutes
  (`src/main/java/com/modocument/infrastructure/security/client/ClientTokenService.java`).
- **Minimal claims, no personal data.** `sub`, `jti`, `roles`, `bank_id`, `token_ver`. E-mail and
  display name were removed from the token in commit `1035294` (2026-10-03).
- **Algorithm pinned.** The decoder accepts only HS256 (`MacAlgorithm.HS256`) and checks
  `exp`/`nbf`, then revocation, then the MFA-challenge scope
  (`src/main/java/com/modocument/api/config/SecurityConfig.java`). The signing key must be at least
  32 bytes and the old public development key is refused outside dev.
- **Refresh rotation with reuse detection.** A 256-bit refresh token, stored only as SHA-256,
  rotates on every use. Presenting a token that was already rotated revokes the whole family and
  writes `TOKEN_REFRESH_REUSE_DETECTED`. Idle limit 12 h (bank setting 15 min–12 h), absolute
  limit 7 days (`src/main/java/com/modocument/application/usecase/AuthUseCase.java`).
- **Revocation.** Logout denylists the access token's `jti` (`RevokedToken`) and revokes every
  refresh token of the user. Password change, admin password reset, role change, disable, MFA
  reset, IdP role change and bank suspension bump `AppUsers.TokenVersion`; `RevocationValidator`
  refuses an older `token_ver` on the next request
  (`src/main/java/com/modocument/infrastructure/security/RevocationValidator.java`,
  `src/main/resources/db/migration/V15__refresh_and_revoked_tokens.sql`,
  `src/main/resources/db/migration/V10__bank_and_user_security_columns.sql`).
- **Cookie.** The refresh token travels only in `modoc_refresh`
  (`HttpOnly; Secure; SameSite=Strict; Path=/api/auth/refresh`, 7 days)
  (`src/main/java/com/modocument/api/controller/AuthController.java`).
- **SSO bridge.** Entra ID and Keycloak tokens are never API credentials; MoDocument issues its
  own pair after the OIDC handshake (`docs/security/identity-providers.md`).
- Full specification: [token-and-otp-specification.md](../policies/token-and-otp-specification.md).

## Evidence SUP-S02-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Token specification (TTLs, rotation, revocation) | `docs/compliance/policies/token-and-otp-specification.md` | repo |
| Issuance, validation and revocation code | `src/main/java/com/modocument/infrastructure/security/JwtService.java`, `src/main/java/com/modocument/infrastructure/security/RevocationValidator.java`, `src/main/java/com/modocument/application/usecase/AuthUseCase.java` | repo |
| Replay / reuse and role-downgrade tests, run 2026-10-19 | `docs/compliance/evidence/A6-test-runs.md` (batch 1) | run |

## Test SUP-S02-T01
- **Procedure:** run `AuthFlowIntegrationTest` (incl. `refreshRotatesAndReuseRevokesTheWholeFamily`,
  `logoutRevokesTheAccessTokenAndRefreshTokens`, `passwordChangeInvalidatesEveryOutstandingAccessToken`,
  `anIdleSessionIsRefusedAndAuditedEvenWhileTheFamilyIsYoung`),
  `UserAdminIntegrationTest#approvedRoleChangeKillsTheTargetsOutstandingTokensImmediately`,
  `RevocationValidatorTest`, `JwtServiceTest`, `AuthUseCaseTest` and `MfaUseCaseTest` on H2.
- **Expected:** a token issued before a role downgrade is rejected after it; a replayed refresh
  token is refused and kills its family.
- **Actual (2026-10-19):** all pass — AuthFlowIntegrationTest 10/10, UserAdminIntegrationTest
  10/10, RevocationValidatorTest 7/7, JwtServiceTest 4/4, AuthUseCaseTest 37/37, MfaUseCaseTest
  10/10 (batch 1: 254 tests, 0 failures). Design review found no `iss`/`aud` claims and a single
  shared HS256 key without `kid` rotation.
- **Result:** Pass (behaviour); the design gaps are recorded below.
- **Tester:** Agent A6 (automated re-performance); human sign-off required. **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| No `iss`/`aud` claims or validators | Build | Add both claims and `JwtIssuerValidator` / audience validator; separate audiences for user and client tokens | Software Engineering — Bhavish Nobeen | 2026-11-20 (proposed) | Day 60 |
| One HS256 key shared by user and client tokens; no `kid`, so rotation ends every session at once | Build | Move to asymmetric keys (RS256/EdDSA) held in Secrets Manager/KMS with `kid` and an overlap window | Software Engineering — Bhavish Nobeen | 2027-01-15 (proposed) | Day 90 |
| Access token is kept in `localStorage` (XSS exposure, mitigated by the strict CSP) | Build | Keep the token in memory only and rely on the refresh cookie at reload | Software Engineering — Bhavish Nobeen | 2026-11-20 (proposed) | Day 60 |

## Risk R-SUP-S02
A leaked token outlives the compromise it came from. Cause in the workbook: no revocation path.
Today the 15-minute lifetime, the `jti` denylist, `TokenVersion` and refresh-family revocation
close that cause. Remaining cause: missing audience separation and no key-rollover mechanism.
Likelihood, impact and residual are set in MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: `MODOCUMENT_JWT_SECRET` held in AWS Secrets Manager and its 180-day rotation record (key register K1).
