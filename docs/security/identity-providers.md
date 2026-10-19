# Identity providers (OIDC single sign-on)

Workstream 07 — gate items **B2** (two login paths), **B3** provider side
(MFA enforced in the providers), **SEC-014** (SSO).

## The two login paths

| Path | Who | Provider | MFA enforcement |
|------|-----|----------|-----------------|
| **Entra ID** | Banks on Microsoft 365 / Azure | Microsoft Entra ID (Azure AD) | Entra **Conditional Access** policy requiring MFA |
| **Keycloak** | Everyone else | Self-hosted open-source Keycloak | Realm **Configure OTP** default required action (TOTP, 6 digits / 30 s) |

Local password login (`POST /api/auth/login`) is the third path. It is the
**only** path for platform accounts (`PLATFORM_ADMIN`: OIDC is refused for
them), the break-glass path for bank admins (bootstrap admin, recovery when an
IdP is down), and the normal path for a bank that uses no IdP. It has its own
built-in TOTP second factor (next section). The login page shows a provider
button for every configured registration (`GET /api/auth/providers`).

## MoDocument's own MFA on local sign-in (SEC-014, checked 2026-10-19)

A correct password returns only a 5-minute MFA challenge when a second factor
is owed; tokens are issued after the TOTP or recovery code verifies
(`AuthUseCase`, `MfaUseCase`). Who owes one (`AuthUseCase.mustEnrolMfa`):

| Role | Local sign-in | OIDC sign-in |
|---|---|---|
| `PLATFORM_ADMIN` | Always TOTP; no setting turns it off | Refused |
| `ADMIN` | TOTP: bank setting `security.mfa.requireForAdmins` is locked to `true` (yml `modocument.security.mfa.require-for-admin`, default `true`) | The IdP's MFA (Keycloak OTP / Entra Conditional Access) |
| `APPROVER`, `READ_WRITE`, `READ_ONLY` | TOTP when the bank turns on `security.mfa.requireForAllRoles` (default off); otherwise optional | The IdP's MFA |

Known gaps (tracked in `docs/compliance/controls/SEC-014.md`):

* MoDocument does **not** check the ID token's `amr`/`acr` claim, so an OIDC
  admin is let in on the IdP's word; a Conditional Access gap at the bank
  becomes a single-factor admin session.
* `docker-compose.yml` defaults `MODOCUMENT_MFA_REQUIRE_FOR_ADMIN` to `false`
  (PR #54). The locked setting then only logs a WARN; ECS sets
  `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true`, which refuses to boot with it
  off. `application-local.yml` also turns it off for local development.
* No step-up re-authentication before critical actions; dual control
  (maker/checker) applies instead.

### Bridge pattern — the app keeps issuing its own tokens

The external IdP only **authenticates** the browser. After the
authorization-code handshake (`/oauth2/authorization/{entra|keycloak}` →
IdP → `/login/oauth2/code/{id}`), MoDocument:

1. JIT-provisions/links the `AppUsers` row (below),
2. opens a normal session through the same issuance path as a password
   login: rotating **refresh-token family in the `modoc_refresh` httpOnly
   cookie** (path-scoped to `/api/auth/refresh`) and our own short-lived
   HS256 access token,
3. 302-redirects to the SPA's `/login/complete` route **without any token in
   the URL** — the SPA calls `POST /api/auth/refresh` (the cookie is the
   credential) to obtain the access token, then `GET /api/auth/me`.

The resource-server side is untouched: the API accepts **only MoDocument's
own HS256 tokens** (with the jti/TokenVersion revocation checks). An Entra or
Keycloak token is never a valid API credential.

Everything is **optional**: with no `modocument.oidc.providers.*` configured
the app boots exactly as before (no OAuth2 endpoints, empty provider list).
Config shape: `application-oidc.yml.example`.

## JIT provisioning and role mapping

On every OIDC login (`OidcLoginService`):

1. **Match by identity**: `(AuthProvider, ProviderSubject)` — the IdP's
   stable `sub` claim (V50 columns, partial unique index). An email renamed
   at the IdP does not detach the account.
2. **Fallback by email**: an existing account with the ID token's email is
   **linked** to the identity (audited `IDENTITY_LINKED`). An account already
   linked to a *different* identity is never silently re-linked — the login
   is refused.
3. **Create**: otherwise a user is created in the provider's configured
   `bank-code` Bank with its `default-role` (audited `USER_PROVISIONED_OIDC`).
   An unknown `bank-code` fails at startup (fail fast) and at login time.
   OIDC accounts get an unusable random password hash.
4. **Role claim** (optional, `role-claim`): a valid value
   (`ADMIN`/`APPROVER`/`READ_WRITE`/`READ_ONLY`; string or list — the most
   privileged valid value wins) maps onto the account on every login, so IdP
   role changes propagate; a change bumps `TokenVersion` (old access tokens
   die) and is audited `USER_ROLE_MAPPED_OIDC`. Absent/unknown values keep
   the existing/default role.
5. Display name is refreshed from the ID token; email is never changed by
   the IdP (unique account key).
6. **Refusals (ws21):** a provider whose `bank-code` names the platform bank
   provisions nobody; an identity that resolves to a `PLATFORM_ADMIN` or any
   platform-bank account is refused; a user of a SUSPENDED bank is refused
   (audited `LOGIN_FAILED`, reason `bank_suspended`).

Successful logins are audited `LOGIN_SUCCESS` with
`NewValues {"method":"oidc","provider":"<id>"}`; every failure (handshake or
provisioning) is `LOGIN_FAILED` and the browser only ever sees
`/login?error=oidc` — no detail leaks.

## Entra ID setup (Microsoft banks)

1. **App registration** — Entra admin center → Identity → Applications →
   App registrations → *New registration*:
   * Name: `MoDocument`; supported account types: *single tenant*.
   * Redirect URI (type **Web**):
     `https://<app-host>/login/oauth2/code/entra`
     (local dev: `http://localhost:8080/login/oauth2/code/entra`, plus
     `http://localhost:3000/...` when the SPA dev server proxies).
2. **Client secret** — *Certificates & secrets* → *New client secret*. Copy
   the **Value** immediately; store it only in a secret store / environment
   variable (`MODOCUMENT_OIDC_ENTRA_CLIENT_SECRET`), never in git. Set a
   calendar reminder for its expiry.
3. **Issuer URI** — from the overview page's *Directory (tenant) ID*:
   `https://login.microsoftonline.com/<tenant-id>/v2.0`
   (the app derives the v2.0 authorize/token/JWKS endpoints from this —
   no discovery call at boot).
4. **Claims** — scopes `openid,profile,email` are requested automatically.
   Ensure the users have an email (add the optional `email` claim under
   *Token configuration* if your tenant strips it). Optionally define **app
   roles** named `ADMIN`/`APPROVER`/`READ_WRITE`/`READ_ONLY`, assign them to
   users, and set `role-claim: roles` so Entra assignments drive MoDocument
   roles.
5. **MFA (gate B3)** — Entra admin center → Protection → **Conditional
   Access** → *New policy*: target *Cloud apps* = the MoDocument app
   registration, *Users* = the bank's user group, *Grant* = **Require
   multifactor authentication**. (Security defaults also work for small
   tenants, but a per-app Conditional Access policy is the auditable
   setting.)
6. App config (see `application-oidc.yml.example`): `issuer-uri`,
   `client-id` (Application (client) ID), `client-secret`, `bank-code` (the
   Bank this tenant's users belong to), `default-role`.

## Keycloak setup (everyone else)

1. **Start it**: `docker compose --profile keycloak up -d` → Keycloak 26 on
   `http://localhost:8081`, admin console creds from
   `KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` (defaults `admin`/`admin`,
   **local dev only**).
2. **Realm import** — the `modocument` realm is imported automatically from
   `docker/keycloak/modocument-realm.json` (`start-dev --import-realm`;
   import runs on first start only). It ships with:
   * password policy `length(12) and notUsername and notEmail` (no
     breached-password list yet — the app's own list,
     `src/main/resources/security/common-passwords.txt`, does not apply to
     Keycloak users; gap SEC-021), brute-force protection on,
   * TOTP policy 6 digits / 30 s and **Configure OTP as a DEFAULT required
     action** — every user must enroll an authenticator on first login
     (MFA enforced, gate B3),
   * confidential client `modocument-app` (authorization code flow,
     redirect URIs for `localhost:8080`/`:3000`) — **rotate the client
     secret** in *Clients → modocument-app → Credentials* for anything
     non-local and put it in `MODOCUMENT_OIDC_KEYCLOAK_CLIENT_SECRET`,
   * realm roles `ADMIN`/`APPROVER`/`READ_WRITE`/`READ_ONLY`, exposed in the
     ID token as the `modocument_roles` claim (protocol mapper),
   * a **disabled** template user (`example.analyst`) documenting the
     onboarding steps in its `comment` attribute.
3. **Bank-code mapping** — one realm serves one bank community; the
   provider's `bank-code` names the Bank its users are provisioned into
   (create the Bank first — the app refuses to start otherwise outside dev
   profiles). Run separate realms (and separate
   `modocument.oidc.providers.<id>` entries) for tenant-separated banks.
4. **Verify the OTP enforcement** — Authentication → Required actions:
   *Configure OTP* must be *Enabled* and *Set as default action*. (The
   imported realm already sets this; re-check after upgrades.)
5. App config: `issuer-uri: http(s)://<keycloak-host>/realms/modocument`,
   `client-id: modocument-app`, `client-secret`, `bank-code`,
   `role-claim: modocument_roles`.

## B2/B3 evidence checklist

* **B2 — a test user signs in through each path**
  * [ ] Entra: test user in the tenant signs in via the "Sign in with
        Microsoft" button; capture the Entra sign-in log entry and the
        MoDocument `LOGIN_SUCCESS` audit row with
        `{"method":"oidc","provider":"entra"}` (plus `USER_PROVISIONED_OIDC`
        on first login).
  * [ ] Keycloak: test user in the `modocument` realm signs in via the
        "Sign in with Keycloak" button; capture the same pair of audit rows
        with `"provider":"keycloak"`.
  * [ ] Both land on `/login/complete`, obtain a session, and
        `GET /api/auth/me` shows the provider's bank and role.
  * [ ] Local break-glass login still works for the bootstrap admin.
* **B3 — MFA challenge on both paths (screenshots)**
  * [ ] Entra: screenshot of the Conditional Access policy (Require MFA →
        MoDocument app) and of the MFA prompt during the test login.
  * [ ] Keycloak: screenshot of *Required actions → Configure OTP (default)*
        and of the TOTP enrollment + OTP prompt during the test login.
* [ ] Negative check: calling the API with the raw Entra/Keycloak token is
      rejected (401) — only MoDocument-issued tokens work.
