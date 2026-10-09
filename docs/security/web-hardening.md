# Web and platform hardening (ws16)

Scope: gate item **E1** (web security — security headers, secure cookies,
CSRF protection, safe file uploads; header scan grade A) and the
application side of **C4** (the database refuses non-TLS connections).
Controls: SUP-S01, SUP-S06 (app side), SEC-028 (app side), SEC-018
(secrets), SUP-S04 (non-root containers).

Production topology this document assumes:

```
browser ──HTTPS──▶ TLS terminator ──▶ frontend nginx :8080 (non-root)
                                         ├─ static SPA (dist/)
                                         └─ /api, legacy API paths ──▶ app :8080 (non-root, not published)
                                                                          └──▶ PostgreSQL (sslmode=require)
```

## 1. Security-header inventory

Two layers emit headers, and each response gets exactly **one** copy of
every header: nginx for what it serves from disk, Spring Security for the
API. nginx never stacks its own headers on top of the backend's.

### 1a. SPA — nginx (`frontend/security-headers.conf`)

Applied to index.html, the hashed `/assets` bundles and every SPA route
(the `try_files … /index.html` fallback), with `always` so nginx-generated
4xx/5xx responses carry them too.

| Header | Value | Why |
|---|---|---|
| Content-Security-Policy | `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'` | Allow-list built from the real `npm run build` output (below) |
| X-Content-Type-Options | `nosniff` | No MIME sniffing of assets |
| X-Frame-Options | `DENY` | Legacy twin of `frame-ancestors 'none'` |
| Referrer-Policy | `no-referrer` | SPA URLs carry ids (job, template, document) |
| Strict-Transport-Security | `max-age=31536000; includeSubDomains` | **Only takes effect over HTTPS** — browsers ignore HSTS on plain HTTP, so local `http://localhost:3000` is unaffected. No `preload` (a domain-wide, one-way operator decision). |
| Permissions-Policy | `camera=(), microphone=(), geolocation=()` | The app needs none of them |
| Cross-Origin-Opener-Policy | `same-origin` | Severs `window.opener` links from other origins |
| Cross-Origin-Resource-Policy | `same-origin` | Other origins cannot embed the bundles |
| Server | `nginx` (no version) | `server_tokens off` |

**How the CSP was derived** (`npm run build`, Vite 6): `dist/index.html`
contains exactly one `<script type="module" src="/assets/index-*.js">` and
one `<link rel="stylesheet" href="/assets/index-*.css">` — no inline
script, no inline style, no remote origin. The bundle contains no `eval` /
`new Function`. The only non-`self` sources the app uses:

* `img-src data:` — the TOTP enrolment QR code is drawn client-side by
  `qrcode.toDataURL` (canvas → `data:` URL; no remote QR service), in
  `LoginPage.jsx` and `ProfilePage.jsx`;
* `img-src blob:` — `AuthedImage.jsx` fetches protected images with the
  Bearer header and shows them through `URL.createObjectURL`.

`style-src` has **no `'unsafe-inline'`**: React applies the `style` prop
through the CSSOM (`element.style[prop] = …`), which CSP does not govern;
nothing in the app renders a `<style>` element or a `style=""` attribute in
markup. `upgrade-insecure-requests` is deliberately absent (it would break
plain-HTTP local dev).

Verified in a real browser (headless Chromium 131) against the built `dist/`
served by this exact nginx config: the SPA loads and the real
login → forced-MFA-enrolment flow renders the QR code with **zero CSP
violations**; probes confirm `blob:` images load and CSSOM styles apply,
while a markup `style` attribute, an injected inline `<script>` and a
remote image are all blocked. Re-run recipe: §6.

### 1b. API — Spring Security (`SecurityConfig`)

| Header | Production value | Dev tooling on (`permit-dev-paths`, local/test) |
|---|---|---|
| Content-Security-Policy | `default-src 'none'; frame-ancestors 'none'` | relaxed for Swagger UI / H2 console |
| X-Frame-Options | `DENY` | `SAMEORIGIN` (H2 console uses frames) |
| X-Content-Type-Options | `nosniff` | same |
| Referrer-Policy | `no-referrer` | same |
| Cache-Control | `no-cache, no-store, max-age=0, must-revalidate` (Spring Security default) | same |
| X-XSS-Protection | `0` (Spring Security default: the legacy auditor is disabled, as current guidance recommends) | same |
| Strict-Transport-Security | `max-age=31536000 ; includeSubDomains` — only when the request is secure, i.e. nginx forwarded `X-Forwarded-Proto: https` (trusted-proxy model, ws08) | same |

Pinned by `HeaderContractTest` (production mode: public endpoint,
authenticated endpoint, 401 response; each header exactly once), by
`ProductionBootPgIT` on a real Tomcat, and, for HSTS behind TLS,
`TrustedProxyIntegrationTest`.

### 1c. Proxied API responses through nginx

The API location in `frontend/nginx.conf` does **not** include the SPA
snippet: the backend's stricter set already arrives on every response, and
nginx's `add_header` appends — a second, different CSP would be enforced
as the intersection and read as a misconfiguration by scanners. Responses
nginx generates itself on those paths (413 body too large, 502/504 while the
app is down or slow) never reach the backend, so four `map`s supply the API
set (CSP `default-src 'none'; frame-ancestors 'none'`, nosniff, `DENY`,
`no-referrer`) **only when the upstream response lacks the header** (an
empty `add_header` value emits nothing). Verified with nginx 1.24: backend
up → one copy of each header (the backend's); backend down (502) and
oversized body (413) → the fallback set.

### 1d. Guarding nginx's inheritance trap

An `add_header` inside a `location` silently discards every inherited one.
Hence the snippet (one source of truth, `include`d per static location) and
`scripts/check-nginx-headers.sh`, run in CI (frontend job): every static
location must carry the full set above (inline or via the snippet), with a
CSP that keeps `default-src 'none'`, `frame-ancestors 'none'`,
`object-src 'none'`, `base-uri 'self'` and no inline/eval script; every
proxied location must carry the four API fallbacks; `server_tokens off` must
be present. Adding, say, a caching `location /assets/` without the include
fails the build.

## 2. CSRF posture

* **The API is Bearer-authenticated.** Every API call carries the access
  token in the `Authorization` header, set by `frontend/src/api/client.js`.
  Browsers never attach that header on their own, so a cross-site page
  cannot make an authenticated request — classic CSRF does not apply, and
  Spring's CSRF filter is disabled deliberately for the stateless API
  (`SessionCreationPolicy.STATELESS`; no session cookie exists).
* **The one cookie** is the refresh token (table below). It is only sent to
  `/api/auth/refresh` (path-scoped), never cross-site (`SameSite=Strict`),
  and that endpoint only rotates the token and returns a new access token in
  the JSON body — which a cross-origin caller could not read anyway (CORS
  allow-list `MODOCUMENT_CORS_ALLOWED_ORIGINS`).
* **OIDC sign-in** (ws07) uses Spring Security's `oauth2Login`, whose
  `state` parameter binds the provider callback to the browser that started
  it (login-CSRF protection); the success handler issues the same refresh
  cookie.
* Defence in depth: `form-action 'self'` and `frame-ancestors 'none'` on the
  SPA, `frame-ancestors 'none'` + `X-Frame-Options: DENY` on the API
  (no clickjacking).

The access token lives in `localStorage` (15-minute TTL by default,
revocable — jti denylist + TokenVersion). That makes XSS the relevant
threat rather than CSRF, which is exactly what the script-tight CSP above
addresses.

## 3. Cookies

| Cookie | Flags | Set by |
|---|---|---|
| `modoc_refresh` | `HttpOnly; Secure; SameSite=Strict; Path=/api/auth/refresh; Max-Age=604800` (7 days, absolute session bound enforced server-side) | `AuthController.refreshCookie` (login, refresh rotation, MFA verify), `OidcLoginSuccessHandler` |

Logout, password change and rejected credentials clear it with the same
flags and `Max-Age=0`.
No other cookie is issued (stateless API; nginx sets none).

## 4. Upload safety (pointers)

| Control | Where |
|---|---|
| Extension allow-list (which formats exist at all) | `SupportedDocumentTypes` |
| Content must match the extension (magic bytes; NUL scan for text formats), SEC-030 | `UploadContentChecker`, `UploadContentGateSec030Test` |
| Duplicate upload (same owner + content SHA-256 + name) refused with 409 before touching disk (ws05, V31) | `FileUploadController` |
| Size caps: 50 MB per file, 200 MB per request; nginx `client_max_body_size 200m` | `application.yml` (`spring.servlet.multipart`), `frontend/nginx.conf` |
| Stored under a server-chosen name (last path segment only, truncated, timestamp-suffixed); resolution normalised inside the uploads folder (no traversal) | `UploadStorage` |
| Per-owner access to stored files (ws02) | `UploadOwnershipEnforcementTest` |
| Exports and data downloads served as `Content-Disposition: attachment` | `IngestionExportController`, `DataSubjectController`, `UserAdminController` |
| Image previews served `inline` only with a content type sniffed from the bytes (image formats only), Bearer-only (not navigable), under the API's `default-src 'none'` CSP + `nosniff` | `DocInstanceController#assetContent`, `DocInstanceUseCase` |
| Uploads deleted after 30 days | `RetentionPurgeJob` — `docs/security/retention-and-dsr.md` |

## 5. Platform hardening

### 5a. Non-root containers (SUP-S04)

| Image | Runs as | Listens | Notes |
|---|---|---|---|
| app (`Dockerfile`) | uid/gid **10001** (`modocument`), numeric `USER 10001:10001` | 8080 (not published) | `/app` and `app.jar` root-owned (the app cannot replace its own code); writable: `/app/wwwroot` (storage), `/home/modocument`, `/tmp` (soffice gets a per-conversion profile there). Entrypoint no longer starts as root / chowns / `runuser`s. |
| frontend (`frontend/Dockerfile`) | uid **101** (`nginxinc/nginx-unprivileged`) | 8080 (compose `3000:8080`) | pid and temp paths under `/tmp`. |

Compose additionally runs both with `cap_drop: [ALL]` and
`no-new-privileges:true`. A numeric uid matters for orchestrators: a
Kubernetes `runAsNonRoot` policy refuses a named `USER` it cannot resolve.

**Volume ownership.** The storage directories are created and chowned to
10001 at **build** time. Docker seeds a *new*, empty named volume from the
image directory it is mounted over — ownership included — so
`modocument-files` comes up writable without any root step. Two cases need
a one-time fix, and the entrypoint fails fast with the command when it
detects them:

* a volume created by an older image (its root entrypoint chowned files to
  the old, auto-assigned system uid);
* a bind mount (keeps host ownership).

```bash
docker compose run --rm --user root --entrypoint chown app -R 10001:10001 /app/wwwroot
# bind mount instead:  sudo chown -R 10001:10001 /path/on/host
```

### 5b. Exposure

Only the frontend (3000) is published on all interfaces. The app port is not
published at all (trusted-proxy model, ws08). PostgreSQL, MongoDB, Kafka,
Ollama, docling and Keycloak publish on `127.0.0.1` only, for local
tooling; services talk over the compose network.

### 5c. Secrets (SEC-018)

* **No insecure defaults** for credentials the stack depends on:
  `MODOCUMENT_JWT_SECRET`, `MODOCUMENT_MFA_KEY`, `POSTGRES_PASSWORD` and
  `MODOCUMENT_APP_DB_PASSWORD` are `${VAR:?…}`-required in compose (compose
  refuses to start without them); the postgres first-boot init script lost
  its `modocument_app` fallback too. Mongo (extraction profile only) keeps
  local-dev defaults with a warning, because compose interpolates the whole
  file even for inactive profiles — a hard requirement there would break
  every plain `docker compose up`.
* **`<NAME>_FILE` indirection** — `SecretFileEnvironmentPostProcessor`
  (registered in `META-INF/spring.factories`, where Spring Boot 3.5 loads
  environment post-processors). For `MODOCUMENT_JWT_SECRET`,
  `MODOCUMENT_MFA_KEY`, `MODOCUMENT_DB_PASSWORD`,
  `MODOCUMENT_FLYWAY_PASSWORD`, `SPRING_DATASOURCE_PASSWORD`,
  `SPRING_FLYWAY_PASSWORD`, `MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD`,
  `MODOCUMENT_AI_API_KEY`, the two OIDC client secrets and
  `EXTRACTION_MONGO_URI`:
  * only `NAME_FILE` set (or `NAME` blank — compose passes `${NAME:-}`
    through as `""`) → the file's content, whitespace-trimmed, becomes
    `NAME` at environment-variable precedence (so `${NAME:}` placeholders
    and Spring's relaxed binding resolve it unchanged);
  * both set → allowed only when identical; different values fail startup;
  * unreadable, empty or > 64 KiB file → startup fails;
  * the log line names the variable and the path, never the value; no
    exception message contains a value.
  The postgres image reads `POSTGRES_PASSWORD_FILE` natively and
  `docker/postgres-init/01-app-role.sh` honours
  `MODOCUMENT_APP_DB_PASSWORD_FILE`.
* **Docker secrets** — `docker-compose.yml` carries a commented,
  file-based `secrets:` example (top level + per service + the `_FILE`
  variables). Compose bind-mounts secret files with their host owner and
  mode, and the readers are non-root (app uid 10001, the postgres init
  script uid 999): keep the files in a directory only the deploying user can
  enter (`chmod 0700`) and make the files themselves readable (`0444`).
* `.dockerignore` keeps `.env*` and `application-local-secrets.yml` out of
  the backend build context; `frontend/.dockerignore` keeps the host's
  `node_modules`/`dist` out of the frontend image build.

### 5d. Database TLS (C4 / SEC-028, application side)

**Decision:** `sslmode` rides on the JDBC URL —
`jdbc:postgresql://…/modocument?sslmode=${MODOCUMENT_DB_SSLMODE:prefer}` in
`application.yml` and the compose `MODOCUMENT_DB_URL` — and
`spring.flyway.url` is set to `${spring.datasource.url}`, so migrations and
the runtime pool always negotiate identically (`DatabaseTlsConfigTest`).

Setting the Flyway URL explicitly also fixed a pre-existing startup failure:
with separate Flyway credentials (always the case — `application.yml`
defaults them), Spring Boot derived the migration URL by reflecting on the
datasource bean, which the RLS `TenantAwareDataSource` wrapper hides
— every non-H2 boot died with "Unable to find suitable method for url". The
real-PG suite never noticed because it migrates through its own Flyway
with Spring's disabled; `ProductionBootPgIT` now boots the app the way
docker-compose does (empty database, Spring's Flyway as owner, runtime as
`modocument_app`, secrets via `_FILE`).

* **Default `prefer`**: TLS whenever the server offers it, plaintext
  otherwise. The local/compose PostgreSQL offers no TLS, and the real-PG
  integration suite runs against a non-TLS server; both keep working.
* **Production: `MODOCUMENT_DB_SSLMODE=require`** (encrypt; refuse a
  plaintext connection — checked against a TLS-less server: startup fails
  with "The server does not support SSL") or **`verify-full`** (also verifies the server
  certificate and host name; supply the CA with `&sslrootcert=/path/ca.pem`
  or the default `~/.postgresql/root.crt`). An operator-supplied
  `MODOCUMENT_DB_URL` replaces the default URL entirely and must carry
  its own `?sslmode=require` (the README's hosted-PostgreSQL example does).
* **Server side** (the "database refuses" half of C4, infrastructure-owned):
  `hostssl`-only `pg_hba.conf` entries / `ssl = on`, or the managed
  service's force-SSL setting. Client `require` plus server enforcement means
  neither side can be downgraded.

Kafka and Mongo (scale profile only) are not yet encrypted in transit — see
the TODOs at the top of `application-scale.yml`; the default deployment
never uses them (extraction data lives in PostgreSQL). On the scale profile
the Mongo URI is now **required** and authenticated (no auth-less default).

## 6. E1 evidence plan

| Evidence | How | Status |
|---|---|---|
| API header contract | `HeaderContractTest` (in `mvn verify`) | automated |
| SPA header coverage per nginx location | `scripts/check-nginx-headers.sh` (CI frontend job) | automated |
| `<NAME>_FILE` behaviour, no value in logs | `SecretFileEnvironmentPostProcessorTest` | automated |
| DB URL / sslmode shared by app and Flyway | `DatabaseTlsConfigTest`; production-style first boot on real PostgreSQL: `ProductionBootPgIT` (`-Ppostgres-it`) | automated |
| CSP does not break the SPA | nginx + headless Chromium against the built `dist/` (recipe below) | manual, done for ws16 |
| Header scan grade **A** | Run securityheaders.com and Mozilla HTTP Observatory against the deployed HTTPS URL. With the set above (all six graded headers, CSP without `unsafe-inline`/`unsafe-eval`, HSTS ≥ 1 year, secure cookie flags) the expected result is A or better on both. Archive the report with the release evidence. | on first HTTPS deployment |
| DAST baseline | The ws19 ZAP baseline workflow (separate branch) scans the running stack. Acceptance: no Medium/High alerts. Expected informational/low noise: "Storable and Cacheable Content" on the hashed assets, Spectre-isolation hints (no `Cross-Origin-Embedder-Policy` — deliberately unset; nothing needs cross-origin isolation and COEP risks breaking image loads). | with ws19 |

**CSP re-check recipe** (no Docker needed): `npm run build`; run
`nginx -c` with `frontend/nginx.conf` (proxy target pointed at a stub or the
real app, `root` at `frontend/dist`) and the snippet under
`<conf dir>/snippets/`; drive `/login` with Playwright/Chromium, listen for
`securitypolicyviolation` events, and walk any new screen that adds images,
frames, fonts or third-party scripts. Any new source must be added to the
snippet explicitly — never widen `default-src`.

### What is not verified without Docker

The image builds, container start-up as uid 10001 / 101, named-volume
seeding with 10001 ownership, `cap_drop`/`no-new-privileges` at runtime and
the compose healthchecks under the new users were not exercised in the ws16
sandbox (no Docker daemon). The compose file was validated with
`docker compose config` (schema + interpolation) and the nginx config with
`nginx -t` plus live requests against nginx 1.24. First `docker compose up
--build` on a Docker host should confirm: `docker compose exec app id`
→ `uid=10001`, `docker compose exec frontend id` → `uid=101`, and a
successful upload (volume writable).
