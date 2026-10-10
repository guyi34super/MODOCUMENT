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

On AWS (`infra/terraform`) the TLS terminator is CloudFront (WAF) → ALB, so
nginx runs in **trusted-proxy mode** (§5e) and the database is RDS reached
with `sslmode=verify-full` against the RDS CA bundle in the image (§5d).

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
| frontend (`frontend/Dockerfile`) | uid **101** (`nginxinc/nginx-unprivileged`) | 8080 (compose `3000:8080`) | pid and temp paths under `/tmp`. `nginx.conf` ships as a template rendered by the image's own entrypoint at start; the only substituted variable is `MODOCUMENT_API_UPSTREAM` (`NGINX_ENVSUBST_FILTER=^MODOCUMENT_`; default `app:8080`, the ECS task sets `127.0.0.1:8081` because awsvpc containers share one namespace — `infra/terraform/modules/ecs-app`). |

Compose additionally runs both with `cap_drop: [ALL]` and
`no-new-privileges:true`; the ECS task definition pins the same users
(`101`, `10001:10001`), drops all capabilities and sets
`no-new-privileges` (IaC, not yet applied). A numeric uid matters for orchestrators: a
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
  `MODOCUMENT_AI_API_KEY`, the two OIDC client secrets,
  `EXTRACTION_MONGO_URI` and the ws18 webhook signing keys
  `MODOCUMENT_ALERTS_WEBHOOK_SECRET` / `MODOCUMENT_SIEM_WEBHOOK_SECRET`:
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
* **AWS / ECS: `verify-full` against the RDS CA, baked into the image.**
  Both Terraform envs build `MODOCUMENT_DB_URL` as
  `jdbc:postgresql://<rds endpoint>/modocument?sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem`.
  The repo `Dockerfile`'s `rds-ca` stage downloads AWS's global bundle
  (`https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem`, every
  region's `rds-ca-rsa2048-g1`/`rsa4096-g1`/`ecc384-g1` roots — 111
  certificates on 2026-10-09) at **build** time and fails the build unless
  the download is nothing but parseable PEM certificates, at least one of
  them an Amazon RDS CA; the runtime image holds it root-owned and `0444`
  (the app user cannot swap in its own CA). Checked with pgjdbc 42.7.7
  against a local TLS PostgreSQL: with the bundle alone a non-RDS server is
  refused ("unable to find valid certification path" — the bundle, not the
  JVM trust store, is what is used); the bundle plus that server's CA (112
  certificates) connects over TLS 1.3, so every certificate in the file is
  read; a host-name mismatch and a missing file are refused. The same probe
  from inside the built app image, as uid 10001 with all capabilities
  dropped, against `/app/certs/rds-global-bundle.pem`: refused for the
  non-RDS server, connected with the control CA. Refresh when
  AWS adds or rotates CAs: rebuild (`--no-cache --pull`) and redeploy
  BEFORE changing the instance's CA — key register K14. Compose ignores the
  file (its PostgreSQL has no TLS; `prefer` stays the local default).
* **Server side** (the "database refuses" half of C4, infrastructure-owned):
  `hostssl`-only `pg_hba.conf` entries / `ssl = on`, or the managed
  service's force-SSL setting. For the AWS target this is `rds.force_ssl = 1`
  in the DB parameter group of
  [`infra/terraform/modules/rds`](../../infra/terraform/modules/rds/main.tf)
  (ws20 — written as IaC, not yet applied to an account). Client `require`
  plus server enforcement means neither side can be downgraded.

Kafka and Mongo (scale profile only) are not yet encrypted in transit — see
the TODOs at the top of `application-scale.yml`; the default deployment
never uses them (extraction data lives in PostgreSQL). On the scale profile
the Mongo URI is now **required** and authenticated (no auth-less default).

### 5e. Proxy chain behind ALB / CloudFront (trusted-proxy mode)

Locally nginx is the edge: it overwrites `X-Forwarded-For`/`-Proto` with its
own `$remote_addr`/`$scheme` and the app trusts exactly that one hop
(`server.forward-headers-strategy: framework`, `ClientIps`). Behind the ALB
the same overwrite would record the ALB node as every client (per-IP login,
MFA and probe throttles, audit IPs), send `http` (no HSTS) and make the app
build `http://` OIDC redirect URIs. The frontend image therefore has two
modes, chosen at container start by
`frontend/docker-entrypoint.d/19-modocument-trusted-proxy.envsh` (sourced by
the nginx image's entrypoint just before its envsubst step):

| | Edge mode (default; docker-compose) | Trusted-proxy mode (ECS) |
|---|---|---|
| Switch | `MODOCUMENT_TRUSTED_PROXY_CIDRS` unset | set (Terraform: the VPC CIDR = the ALB) |
| `X-Forwarded-For` to the app | `$remote_addr` (the TCP peer) | the client: realip `set_real_ip_from <cidr>` + `real_ip_header X-Forwarded-For` + `real_ip_recursive on` (rightmost hop that is not a trusted proxy); with `MODOCUMENT_CLIENT_IP_HEADER=CloudFront-Viewer-Address` the viewer address CloudFront stamps (production) |
| `X-Forwarded-Proto` to the app | `$scheme` | the ALB's value (`http`/`https` only) when the TCP peer is trusted, else `$scheme` |
| `Host` and `X-Forwarded-Host` to the app | `MODOCUMENT_PUBLIC_HOST` (default `localhost:3000`) | `MODOCUMENT_PUBLIC_HOST` = the ecs-app module's `public_host` (`app_domain` in both envs) |

Both modes still OVERWRITE the headers towards the app. Untrusted peers
cannot spoof these: a client-prepended `X-Forwarded-For` entry is never reached
(recursion stops at the first untrusted address), and `X-Forwarded-Proto` /
`CloudFront-Viewer-Address` are only read when the TCP peer
(`$realip_remote_addr`) is inside the trusted CIDRs. Why the CloudFront
header and not CloudFront's IP ranges: [`infra/terraform/README.md`](../../infra/terraform/README.md)
"Proxy chain, bootstrap admin, DB CA". The header is parsed with a strict
`map` (IPv4 `a.b.c.d:port`, IPv6 unbracketed or bracketed `…:port` — realip
cannot parse CloudFront's unbracketed IPv6 form); anything else, including a
duplicated header (nginx joins duplicates with `, `), falls back to the
realip result. Every CIDR is validated in POSIX sh before it is written into
nginx config (IPv4/IPv6 with a 1–32/1–128 prefix, no `/0`, no leading-zero
octets) and any bad value — or `MODOCUMENT_CLIENT_IP_HEADER` without CIDRs —
stops the container.

**Other forwarding headers are cleared (fixed 9 Oct 2026; affected both
modes, compose included).** nginx passes through request headers it does
not set itself, and Spring's `ForwardedHeaderFilter` PREFERS the RFC 7239
`Forwarded` header over `X-Forwarded-For` (checked with spring-web 6.2.8:
`Forwarded: for=6.6.6.6` beats the overwritten `X-Forwarded-For`). Before
the fix a client could choose its own IP, proto and host that way, and
steer `X-Forwarded-Port` / `X-Forwarded-Prefix`. The API location of
`frontend/nginx.conf` now sets `Forwarded`, `X-Forwarded-Port`,
`X-Forwarded-Prefix` and `X-Forwarded-Ssl` to `""`, which drops them from
the proxied request, so the app only ever sees the three headers nginx
sets. `scripts/check-nginx-headers.sh` (CI frontend job) fails if a proxied
location stops clearing any of them or uses `$proxy_add_x_forwarded_for`.
Verified through a stub upstream (nginx 1.24): a client sending
`Forwarded: for=6.6.6.6;proto=https`, `X-Forwarded-For: 6.6.6.6`,
`X-Forwarded-Port: 443`, `X-Forwarded-Prefix: /evil` and
`X-Forwarded-Ssl: on` reached the app as only `X-Forwarded-For: 127.0.0.1`,
`X-Forwarded-Proto: http`, `X-Forwarded-Host: 127.0.0.1`.

**The host the app sees is configured, not taken from the client (fixed
10 Oct 2026; Semgrep `generic.nginx.security.request-host-used`).** nginx
used to forward its `$host` (the client's `Host` header) as `Host` and
`X-Forwarded-Host`, and the app builds OIDC redirect URIs and other absolute
URLs from `X-Forwarded-Host`. The IdPs reject unregistered redirect URIs, so
this was not a token leak, but the app should never build URLs from a
client-chosen host. `$host` also drops the port, so compose built
`http://localhost/login/oauth2/code/keycloak` instead of the
`http://localhost:3000/…` URI the shipped realm registers. Both headers are
now `MODOCUMENT_PUBLIC_HOST` (frontend image default `localhost:3000`, set
in `docker-compose.yml`; ECS: the ecs-app module's `public_host`, which both
envs set to `app_domain`). The entrypoint script accepts only a DNS name or
IPv4 address with an optional `:port`, and stops the container on anything
else. Verified (10 Oct 2026) in all three proxy modes, by rendering the
template with the real entrypoint script and sending requests through a
stub upstream: a
client sending `Host: evil.example:9999`, `X-Forwarded-Host: evil2.example`
and `Forwarded: host=evil3` reached the app with `Host` and
`X-Forwarded-Host` equal to the configured value; `MODOCUMENT_PUBLIC_HOST=evil;`
was refused at start; `scripts/check-nginx-headers.sh` green on each render.

Verified (9 Oct 2026) twice: nginx 1.24 with the image's own entrypoint,
`15-local-resolvers` and `20-envsubst-on-templates` scripts under busybox
ash and dash, and the built frontend image itself (`nginx-unprivileged:alpine`,
nginx 1.31.6 — `--with-http_realip_module` — uid 101, capabilities
dropped): edge mode renders the template **directive-for-directive identical**
to the previous config and writes no extra file; `nginx -t` passes in all
three modes; through a stub upstream — an untrusted client's
`X-Forwarded-For: 6.6.6.6` / `X-Forwarded-Proto: https` /
`CloudFront-Viewer-Address` are ignored (`127.0.0.1`, `http`); from a
trusted address `X-Forwarded-For: 6.6.6.6, 203.0.113.7` (+ an extra trusted
VPC hop) → `203.0.113.7`, `https`, junk proto → `http`; CloudFront mode →
`198.51.100.10` and `2001:db8::1` from the header, the edge address when the
header is missing or duplicated; `scripts/check-nginx-headers.sh` green on
the template and on the rendered config of each mode.

## 6. E1 evidence plan

| Evidence | How | Status |
|---|---|---|
| API header contract | `HeaderContractTest` (in `mvn verify`) | automated |
| SPA header coverage per nginx location | `scripts/check-nginx-headers.sh` (CI frontend job) | automated |
| `<NAME>_FILE` behaviour, no value in logs | `SecretFileEnvironmentPostProcessorTest` | automated |
| DB URL / sslmode shared by app and Flyway | `DatabaseTlsConfigTest`; production-style first boot on real PostgreSQL: `ProductionBootPgIT` (`-Ppostgres-it`) | automated |
| RDS CA bundle in the image (`verify-full`) | build-time check in the `rds-ca` stage (fails on anything but PEM Amazon RDS CA certificates); pgjdbc probe against a local TLS server (§5d) | build gate; probe manual, done 9 Oct 2026 |
| Proxy chain behind ALB/CloudFront | rendered-config diff + `nginx -t` + live spoofing matrix in all three modes (§5e) | manual, done 9 Oct 2026; live AWS confirmation pending |
| CSP does not break the SPA | nginx + headless Chromium against the built `dist/` (recipe below) | manual, done for ws16 |
| Header scan grade **A** | Run securityheaders.com and Mozilla HTTP Observatory against the deployed HTTPS URL. With the set above (all six graded headers, CSP without `unsafe-inline`/`unsafe-eval`, HSTS ≥ 1 year, secure cookie flags) the expected result is A or better on both. Archive the report with the release evidence. | on first HTTPS deployment |
| DAST baseline | The ws19 ZAP baseline workflow (separate branch) scans the running stack. Acceptance: no Medium/High alerts. Expected informational/low noise: "Storable and Cacheable Content" on the hashed assets, Spectre-isolation hints (no `Cross-Origin-Embedder-Policy` — deliberately unset; nothing needs cross-origin isolation and COEP risks breaking image loads). | with ws19 |

**CSP re-check recipe** (no Docker needed): `npm run build`; render
`frontend/nginx.conf` first, as the image does in edge mode
(`MODOCUMENT_API_UPSTREAM=127.0.0.1:<port> MODOCUMENT_NGX_FORWARDED_FOR='$remote_addr'
MODOCUMENT_NGX_FORWARDED_PROTO='$scheme' envsubst '${MODOCUMENT_API_UPSTREAM}
${MODOCUMENT_NGX_FORWARDED_FOR} ${MODOCUMENT_NGX_FORWARDED_PROTO}' < frontend/nginx.conf`,
pointing at a
stub or the real app), then run `nginx -c` with it (`root` at `frontend/dist`) and the snippet under
`<conf dir>/snippets/`; drive `/login` with Playwright/Chromium, listen for
`securitypolicyviolation` events, and walk any new screen that adds images,
frames, fonts or third-party scripts. Any new source must be added to the
snippet explicitly — never widen `default-src`.

### What is not verified without Docker

The image builds, container start-up as uid 10001 / 101, named-volume
seeding with 10001 ownership, `cap_drop`/`no-new-privileges` at runtime and
the compose healthchecks under the new users were not exercised in the ws16
sandbox (no Docker daemon). The go-live blocker fixes (9 Oct 2026) WERE
checked in real images, built with a local daemon: the frontend image
(final stage unchanged; only the `npm ci` stage swapped for the locally
built `dist/`, as npm cannot trust the sandbox's TLS interception) ran as
uid 101 with all capabilities dropped and `no-new-privileges` in edge,
X-Forwarded-For and CloudFront modes (§5e); the app image (runtime stage
unchanged; test-only: the sandbox CA for the `rds-ca` curl and the jar
from the local `mvn verify`) carries the bundle root-owned `0444`, and a
pgjdbc probe run inside it as uid 10001 enforces it (§5d). Still not run:
a full `docker compose up --build` with the real build stages. The compose file was validated with
`docker compose config` (schema + interpolation) and the nginx config with
`nginx -t` plus live requests against nginx 1.24. First `docker compose up
--build` on a Docker host should confirm: `docker compose exec app id`
→ `uid=10001`, `docker compose exec frontend id` → `uid=101`, and a
successful upload (volume writable).
